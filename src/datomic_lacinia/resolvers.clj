(ns datomic-lacinia.resolvers
  (:require [com.walmartlabs.lacinia.resolve :as resolve]
            [datomic-lacinia.datomic :as datomic]
            [datomic-lacinia.utils :as utils]
            [datomic-lacinia.types :as types]
            [clojure.test :refer [deftest- is]]
            [datomic-lacinia.testing :as testing]
            [datomic.client.api :as d]))

(defn value-field-resolver [attribute-ident attribute-type]
  (fn [{:keys [db eid]} _ _]
    (let [db-value      (datomic/value db eid attribute-ident)
          resolve-value #(if (map? %)
                           (resolve/with-context {} {:eid (:db/id %)})
                           (types/parse-db-value % attribute-type attribute-ident))]
      ; TODO optimize execution (goal: no n+1 calls for each array field) -> benchmark!
      (if (sequential? db-value)
        (map resolve-value db-value)
        (resolve-value db-value)))))

(defn context-field-resolver []
  (fn [_ _ _] {}))

; TODO generate resolvers for every other identity attribute (see https://docs.datomic.com/on-prem/schema/identity.html)

(defn get-resolver [resolve-db]
  (fn [_ {:keys [id]} _]
    (let [eid (Long/valueOf ^String id)
          db  (resolve-db)]
      ; TODO optimize execution (goal: one round-trip, no more) -> benchmark!
      (when (datomic/id-exists? db eid)
        (resolve/with-context {} {:eid eid :db db})))))

(deftest- get-resolver-test
  (let [conn       (testing/local-temp-conn)
        resolve-db #(d/db conn)
        resolver   (get-resolver resolve-db)]
    (let [valid-id (resolver nil {:id "0"} nil)]
      (is (= (get-in valid-id [:value]) {}))
      (is (= (get-in valid-id [:data :eid]) 0))
      (is (= 0 (:db/id (d/pull (get-in valid-id [:data :db]) '[*] 0)))))

    (let [invalid-id (resolver nil {:id "1000"} nil)]
      (is (nil? invalid-id)))))

(defn- db-paths-with-values [input-object response-objects default-entity-type]
  (->>
    (utils/paths input-object)
    (map
      (fn [[ks v]]
        (loop [graphql-context (get response-objects default-entity-type)
               graphql-path    ks
               db-path         []
               db-value-type   nil]
          (if-let [current-field (first graphql-path)]
            (let [current-attribute   (get-in graphql-context [:fields current-field :db/attribute])
                  next-type           (get-in graphql-context [:fields current-field :type]) ; potentially '(list <type>)
                  next-type-unwrapped (if (list? next-type) (second next-type) next-type)]
              (recur
                (get response-objects next-type-unwrapped)
                (rest graphql-path)
                (if current-attribute
                  (conj db-path current-attribute)
                  db-path)
                (get-in graphql-context [:fields current-field :db/type])))
            [db-path
             (types/parse-graphql-value v db-value-type (last db-path))]))))))

(deftest- db-paths-with-values-test
  (let [artist-name      "<some artist>"
        song-name        "<some song artist was part of>"
        input-object     {:artist       {:name artist-name}
                          :referencedBy {:track {:artists [{:track {:name song-name}}]}}}
        response-objects {:Entity                  {:fields {:artist       {:type :EntityArtist},
                                                             :track        {:type :EntityTrack},
                                                             :referencedBy {:type :EntityReferencedBy}}},
                          :EntityArtist            {:fields {:name {:type         :String,
                                                                    :db/attribute :artist/name,
                                                                    :db/type      :db.type/string}}},
                          :EntityTrack             {:fields {:name    {:type         :String,
                                                                       :db/attribute :track/name,
                                                                       :db/type      :db.type/string},
                                                             :artists {:type         '(list :Entity),
                                                                       :db/attribute :track/artists,
                                                                       :db/type      :db.type/ref}}},
                          :EntityReferencedBy      {:fields {:track {:type :EntityReferencedByTrack}}},
                          :EntityReferencedByTrack {:fields {:artists {:type         '(list :Entity),
                                                                       :db/attribute :track/_artists,
                                                                       :db/type      :db.type/ref}}}}]
    (is (= (db-paths-with-values input-object response-objects :Entity)
           (list [[:artist/name] artist-name] [[:track/_artists :track/name] song-name])))))

(defn match-resolver [resolve-db response-objects entity-type-key]
  (fn [_ {:keys [template]} _]
    (let [db       (resolve-db)
          db-paths (db-paths-with-values template response-objects entity-type-key)
          results  (datomic/matches db db-paths)]
      (map #(resolve/with-context {} {:eid % :db db}) results))))
