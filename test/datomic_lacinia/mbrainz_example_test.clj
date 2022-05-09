(ns datomic-lacinia.mbrainz-example-test
  (:require [datomic-lacinia.schema :as schema]
            [datomic-lacinia.datomic :as datomic]
            [datomic-lacinia.testing :as testing]
            [datomic.client.api :as d]
            [com.walmartlabs.lacinia.schema :as ls]
            [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]))

; Testing
; - schema generation
; - fetch by id
; - fetch by input object
; - simple namespaces
; - multiple results
; - strings
; - refs
; - keywords

(def tx-attributes
  [{:db/ident       :artist/name,
    :db/valueType   :db.type/string,
    :db/cardinality :db.cardinality/one,
    :db/doc         "The artist's name"}
   {:db/ident       :artist/type,
    :db/valueType   :db.type/ref,
    :db/cardinality :db.cardinality/one,
    :db/doc         "Enum, one of :artist.type/person, :artist.type/other, :artist.type/group."}])

(def tx-data
  [{:db/id       "1"
    :artist/name "Led Zeppelin"
    :artist/type {:db/ident :artist.type/group}}
   {:db/id       "2"
    :artist/name "John Lennon"
    :artist/type {:db/ident :artist.type/person}}
   {:db/id       "3"
    :artist/name "The Beatles"
    :artist/type {:db/ident :artist.type/group}}])

(deftest execute-example-schema
  (let [conn    (testing/local-temp-conn)
        _       (d/transact conn {:tx-data tx-attributes})
        data-tx (d/transact conn {:tx-data tx-data})
        id      (fn [temp-id] (str (get-in data-tx [:tempids temp-id])))
        as      (datomic/attributes (d/db conn) ["artist"])
        s       (ls/compile (schema/gen-schema {:attributes as
                                                :resolve-db #(d/db conn)}))]

    ; TODO test introspection

    (let [r (testing/execute s "mbrainz/query-artist-by-id.graphql" {:id (id "1")})]
      (println "Example 1:")
      (json/pprint r)
      (is (= (get-in r [:data :get :db_ :id]) (id "1")))
      (is (= (get-in r [:data :get :artist_ :name]) "Led Zeppelin"))
      (is (= (get-in r [:data :get :artist_ :type :db_ :ident]) ":artist.type/group")))

    (let [r (testing/execute s "mbrainz/query-john-lennon.graphql" nil)]
      (println "Example 2:")
      (json/pprint r)
      (is (= (count (get-in r [:data :match])) 1))
      (is (= (get-in r [:data :match 0 :db_ :id]) (id "2")))
      (is (= (get-in r [:data :match 0 :artist_ :name]) "John Lennon"))
      (is (= (get-in r [:data :match 0 :artist_ :type :db_ :ident]) ":artist.type/person")))

    (let [r (testing/execute s "mbrainz/query-group-artists.graphql" nil)]
      (is (= (count (get-in r [:data :match])) 2))
      (is (= (get-in r [:data :match 0 :artist_ :name]) "Led Zeppelin"))
      (is (= (get-in r [:data :match 1 :artist_ :name]) "The Beatles")))))

