(ns datomic-lacinia.mbrainz-example-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [com.walmartlabs.lacinia.schema :as ls]
            [datomic-lacinia.datomic :as datomic]
            [datomic-lacinia.schema :as schema]
            [datomic-lacinia.testing :as testing]
            [datomic.client.api :as d]))

; Testing
; - schema generation
; - fetch by id
; - fetch by input object
; - simple namespaces
; - multiple results
; - strings
; - refs
; - keywords

; TODO test db.cardinality/many attributes

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
        s       (ls/compile (schema/gen-schema {:datomic/resolve-db #(d/db conn)
                                                :datomic/attributes as}))]

    ; TODO test introspection
    ; TODO edge cases (expected data missing, id missing, etc.)

    (let [file "mbrainz/query-artist-by-id.graphql"]
      (testing file
        (let [r (testing/execute s file {:id (id "1")})]
          (println "Example 1:")
          (json/pprint r)
          (is (= (get-in r [:data :get :db_ :id]) (id "1")))
          (is (= (get-in r [:data :get :artist_ :name]) "Led Zeppelin"))
          (is (= (get-in r [:data :get :artist_ :type :db_ :ident]) ":artist.type/group")))))

    (let [file "mbrainz/match-artist-by-id.graphql"]
      (testing file
        (let [r (testing/execute s file {:id (id "1")})]
          (is (= (get-in r [:data :match 0 :db_ :id]) (id "1")))
          (is (= (get-in r [:data :match 0 :artist_ :name]) "Led Zeppelin")))))

    (let [file "mbrainz/query-john-lennon.graphql"]
      (testing file
        (let [r (testing/execute s file nil)]
          (println "Example 2:")
          (json/pprint r)
          (is (= (count (get-in r [:data :match])) 1))
          (is (= (get-in r [:data :match 0 :db_ :id]) (id "2")))
          (is (= (get-in r [:data :match 0 :artist_ :name]) "John Lennon"))
          (is (= (get-in r [:data :match 0 :artist_ :type :db_ :ident]) ":artist.type/person")))))

    (let [file "mbrainz/query-group-artists.graphql"]
      (testing file
        (let [r (testing/execute s file nil)]
          (is (= (count (get-in r [:data :match])) 2))
          (is (= (get-in r [:data :match 0 :artist_ :name]) "Led Zeppelin"))
          (is (= (get-in r [:data :match 1 :artist_ :name]) "The Beatles")))))))

