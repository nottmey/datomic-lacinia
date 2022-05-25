(ns datomic-lacinia.datomic
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest- is]]
            [datomic-lacinia.testing :as testing]
            [datomic.client.api :as d]))

(defn back-ref [k]
  (keyword (namespace k) (str "_" (name k))))

(deftest- back-ref-test
  (is (= :x.y/_z (back-ref :x.y/z))))

(defn back-ref? [k]
  (str/starts-with? (name k) "_"))

(deftest- back-ref?-test
  (is (back-ref? :x.y/_z)))

(def default-id-attribute
  {:db/ident       :db/id
   :db/valueType   {:db/ident :db.type/long}
   :db/cardinality {:db/ident :db.cardinality/one}
   :db/unique      {:db/ident :db.unique/identity},
   :db/doc         "Attribute used to uniquely identify an entity, managed by Datomic."})

(def default-ident-attribute
  {:db/ident       :db/ident,
   :db/valueType   {:db/ident :db.type/keyword},
   :db/cardinality {:db/ident :db.cardinality/one},
   :db/unique      {:db/ident :db.unique/identity},
   :db/doc         "Attribute used to uniquely name an entity."})

(def default-attributes
  [default-id-attribute
   default-ident-attribute])

(def relevant-attribute-keys
  [:db/ident
   :db/valueType
   :db/cardinality
   :db/unique
   :db/doc])

(defn attributes
  ([db] (attributes db nil))
  ([db nss]
   (->> (if nss
          (d/q '[:find (pull ?e pattern) ?tx
                 :in $ pattern ?nss
                 :where
                 [?e :db/valueType _]
                 [?e :db/cardinality _]
                 [?e :db/ident ?ident ?tx]
                 [(namespace ?ident) ?ns]
                 [(contains? ?nss ?ns)]]
               db
               relevant-attribute-keys
               (set nss))
          (d/q '[:find (pull ?e pattern) ?tx
                 :in $ pattern
                 :where
                 [?e :db/valueType _]
                 [?e :db/cardinality _]
                 [?e :db/ident _ ?tx]]
               db
               relevant-attribute-keys))
        (sort (fn [[a1 t1] [a2 t2]]
                (let [by-tx (compare t1 t2)]
                  (if (= by-tx 0)
                    (compare (:db/ident a1) (:db/ident a2))
                    by-tx))))
        (map first))))

(deftest- attributes-test
  (let [conn       (testing/local-temp-conn)
        _          (d/transact conn {:tx-data [{:db/ident       :something
                                                :db/valueType   :db.type/ref
                                                :db/cardinality :db.cardinality/one}]})
        as         (attributes (d/db conn))
        idents     (->> as (map #(:db/ident %)) (set))
        succession (->> as (map #(:db/ident %)) (filter #{:something :db/valueType :db/ident}))]
    ; :db/ident and :db/valueType are in the same tx, :something is a later one
    ; (we want to sort by tx then by ident)
    (is (= succession '(:db/ident :db/valueType :something)))
    (is (contains? idents :db/ident))
    (is (contains? idents :db.entity/attrs))
    (is (contains? idents :fressian/tag)))

  (let [as     (attributes (d/db (testing/local-temp-conn)) ["db"])
        idents (->> as (map #(:db/ident %)) (set))]
    (is (contains? idents :db/ident))
    (is (contains? idents :db/valueType))
    (is (contains? idents :db/cardinality))
    (is (not (contains? idents :db.entity/attrs)))
    (is (not (contains? idents :fressian/tag)))
    (is (every? #(= (namespace %) "db") idents))
    (is (every? #(map? (:db/valueType %)) as))
    (is (every? #(map? (:db/cardinality %)) as))))

(defn id-exists? [db eid]
  (boolean
    (when (int? eid)
      (seq (d/datoms db {:index :eavt :components [eid]})))))

(deftest- id-exists?-test
  (let [db (d/db (testing/local-temp-conn))]
    (is (id-exists? db 0))
    (is (not (id-exists? db 123123)))
    (is (not (id-exists? db "0")))
    (is (not (id-exists? db 0.0)))
    (is (not (id-exists? db nil)))
    (is (not (id-exists? db [])))))

(defn value [db eid attribute]
  (if (= attribute :db/id)
    eid
    (->
      (d/pull db {:eid eid :selector [attribute]})
      (get attribute))))

(defn filter-query [paths]
  (let [reverse-inverse-rule (fn [[e a v :as rule]]
                               (if (str/starts-with? (name a) "_")
                                 (list v (keyword (namespace a) (subs (name a) 1)) e)
                                 rule))
        optimize-rules       (fn [rules] (reverse rules))   ; better query ordering
        path->filter         (fn [path-index [attributes value]]
                               (let [checked-attributes (if (= (last attributes) :db/id)
                                                          (drop-last attributes)
                                                          attributes)
                                     temp-binding-name  (fn [nesting-depth]
                                                          (symbol (str "?path" path-index "depth" nesting-depth)))
                                     temp-bindings      (map temp-binding-name (range 0 (dec (count checked-attributes))))
                                     query-path         (-> (cons '?e temp-bindings)
                                                            (interleave checked-attributes)
                                                            (vec)
                                                            (conj value))]
                                 (->> (partition 3 2 query-path)
                                      (map reverse-inverse-rule)
                                      (optimize-rules))))
        filter-rules         (map-indexed path->filter paths)]
    (concat [:find '?e :where]
            (apply concat filter-rules))))

(deftest- filter-query-test
  (is (= (filter-query [[[:db/cardinality :x :y :db/id] 36]
                        [[:a :b :c] :d]])
         '[:find ?e
           :where
           [?path0depth1 :y 36]
           [?path0depth0 :x ?path0depth1]
           [?e :db/cardinality ?path0depth0]
           [?path1depth1 :c :d]
           [?path1depth0 :b ?path1depth1]
           [?e :a ?path1depth0]]))

  (is (= (filter-query [[[:db/cardinality :db/id] 36]])
         '[:find ?e :where [?e :db/cardinality 36]]))

  (is (= (filter-query [[[:x :y :z :artist/name] "Led Zeppelin"]])
         '[:find ?e
           :where
           [?path0depth2 :artist/name "Led Zeppelin"]
           [?path0depth1 :z ?path0depth2]
           [?path0depth0 :y ?path0depth1]
           [?e :x ?path0depth0]]))

  (is (= (filter-query [[[:track/_artists :track/name] "Moby Dick"]
                        [[:track/_artists :track/name] "Ramble On"]])
         '[:find ?e
           :where
           [?path0depth0 :track/name "Moby Dick"]
           [?path0depth0 :track/artists ?e]
           [?path1depth0 :track/name "Ramble On"]
           [?path1depth0 :track/artists ?e]])))

(defn matches [db paths]
  (if-let [[_ value] (first (filter (fn [[[ffa]]] (= ffa :db/id)) paths))]
    ;; TODO check id
    ;; TODO also apply filters (they still must match)
    [value]
    (->> db
         (d/q (filter-query paths))
         (map first))))

(comment
  (let [paths '([[:db/cardinality :db/id] "36"])]
    (matches (current-db) paths)))
