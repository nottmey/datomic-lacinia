(ns datomic-lacinia.datomic
  (:require [datomic.client.api :as d]
            [clojure.string :as str]
            [clojure.test :refer [deftest- is]]
            [datomic-lacinia.testing :as testing]))


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
          (d/q '[:find (pull ?e pattern)
                 :in $ pattern
                 :where
                 [?e :db/valueType _]
                 [?e :db/cardinality _]]
               db
               relevant-attribute-keys))
        ;; TODO sort by tx -> collision prevention
        (map first))))

(deftest- attributes-test
  (let [as     (attributes (d/db (testing/local-temp-conn)))
        idents (->> as (map #(:db/ident %)) (set))]
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
                                     temp-bindings      (map temp-binding-name (range 1 (count checked-attributes)))
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

(comment
  (let [paths '([[:db/cardinality :x :y :db/id] "36"] [[:a :b :c] :d])]
    (filter-query paths))

  (let [paths '([[:db/cardinality #_:x #_:y :db/id] "36"] #_[[:a :b :c] :d])]
    (filter-query paths))

  (let [paths '([[:x :y :z :artist/name] "Led Zeppelin"])]
    (filter-query paths))

  (filter-query '([[:track/_artists :track/name] "Moby Dick"]
                  [[:track/_artists :track/name] "Ramble On"])))

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
