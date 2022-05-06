(ns datomic-lacinia.schema-test
  (:require [datomic-lacinia.schema :as schema]
            [datomic-lacinia.datomic :as datomic]
            [datomic-lacinia.testing :as testing]
            [datomic.client.api :as d]
            [com.walmartlabs.lacinia.schema :as ls]
            [com.walmartlabs.lacinia :as l]
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

(def get-artist-by-id
  "query($id: ID!) {
    get(id: $id) {
      db {
        id
      }
      artist {
        name
        type {
          db {
            ident
          }
        }
      }
    }
  }")

(def match-john-lennon
  "query {
    match(template: {
      artist: {
        name: \"John Lennon\"
      }
    }) {
      db {
        id
      }
      artist {
        name
        type {
          db {
            ident
          }
        }
      }
    }
  }")

(def match-artist-groups
  "query {
    match(template: {
      artist: {
        type: {
          db: {
            ident: \":artist.type/group\"
          }
        }
      }
    }) {
      artist {
        name
      }
    }
  }")

(deftest execute-example-schema
  (let [conn    (testing/local-temp-conn)
        _       (d/transact conn {:tx-data tx-attributes})
        data-tx (d/transact conn {:tx-data tx-data})
        id      (fn [temp-id] (str (get-in data-tx [:tempids temp-id])))
        as      (datomic/attributes (d/db conn) ["artist"])
        s       (ls/compile (schema/gen-schema {:attributes as
                                                :resolve-db #(d/db conn)}))]

    ; TODO test introspection

    (let [r (l/execute s get-artist-by-id {:id (id "1")} nil)]
      (println "Example 1:")
      (clojure.data.json/pprint r)
      (is (= (get-in r [:data :get :db :id]) (id "1")))
      (is (= (get-in r [:data :get :artist :name]) "Led Zeppelin"))
      (is (= (get-in r [:data :get :artist :type :db :ident]) ":artist.type/group")))

    (let [r (l/execute s match-john-lennon nil nil)]
      (println "Example 2:")
      (clojure.data.json/pprint r)
      (is (= (count (get-in r [:data :match])) 1))
      (is (= (get-in r [:data :match 0 :db :id]) (id "2")))
      (is (= (get-in r [:data :match 0 :artist :name]) "John Lennon"))
      (is (= (get-in r [:data :match 0 :artist :type :db :ident]) ":artist.type/person")))

    (let [r (l/execute s match-artist-groups nil nil)]
      (is (= (count (get-in r [:data :match])) 2))
      (is (= (get-in r [:data :match 0 :artist :name]) "Led Zeppelin"))
      (is (= (get-in r [:data :match 1 :artist :name]) "The Beatles")))))

