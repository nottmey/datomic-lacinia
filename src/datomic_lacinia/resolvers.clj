(ns datomic-lacinia.resolvers
  (:require [com.walmartlabs.lacinia.resolve :as resolve]
            [datomic-lacinia.datomic :as datomic]
            [datomic-lacinia.utils :as utils]
            [datomic-lacinia.types :as types]
            [clojure.test :refer [deftest- is]]
            [datomic-lacinia.testing :as testing]
            [datomic.client.api :as d]))

(defn field-resolver [attribute-ident attribute-type]
  (fn [{:keys [db eid]} _ _]
    (let [db-value      (datomic/value db eid attribute-ident)
          resolve-value #(if (map? %)
                           (resolve/with-context {} {:eid (:db/id %)})
                           (types/parse-db-value % attribute-type attribute-ident))]
      ; TODO optimize execution (goal: no n+1 calls for each array field) -> benchmark!
      (if (sequential? db-value)
        (map resolve-value db-value)
        (resolve-value db-value)))))

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

(comment
  (let [input-object {:db {:id "130" :cardinality {:db {:ident ":db.cardinality/many"}}}}
        db           (d/db (testing/local-temp-conn))
        response-objects  (gen-result-objects (datomic/attributes db) :Entity)]
    (db-paths-with-values input-object response-objects :Entity))

  (let [input-object {:referencedBy {:track {:artists [{:track {:name "Moby Dick"}}]}}}
        db           (d/db (testing/local-temp-conn))
        response-objects  (gen-result-objects (datomic/attributes db) :Entity)]
    (db-paths-with-values input-object response-objects :Entity)))

(defn match-resolver [resolve-db response-objects entity-type-key]
  (fn [_ {:keys [template]} _]
    (let [db       (resolve-db)
          db-paths (db-paths-with-values template response-objects entity-type-key)
          results  (datomic/matches db db-paths)]
      (map #(resolve/with-context {} {:eid % :db db}) results))))
