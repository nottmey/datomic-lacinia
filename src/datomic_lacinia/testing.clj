(ns datomic-lacinia.testing
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest- is]]
            [clojure.walk :as w]
            [com.walmartlabs.lacinia :as l]
            [datomic.client.api :as d]))

(defn local-temp-conn
  ([] (local-temp-conn "testing"))
  ([db-name]
   (let [client   (d/client {:server-type :dev-local
                             :storage-dir :mem
                             :system      db-name})
         database {:db-name db-name}
         _        (d/delete-database client database)
         _        (d/create-database client database)]
     (d/connect client database))))

(defn clean [nested-maps remove-keys]
  (w/prewalk
    (fn [node]
      (if (map? node)
        (apply dissoc node remove-keys)
        node))
    nested-maps))

(deftest- clean-test
  (is (= (clean [] [:x]) []))
  (is (= (clean [:a :x] [:x]) [:a :x]))
  (is (= (clean {:x 1} [:x]) {}))
  (is (= (clean {:a {:x 1}, :x 2} [:x]) {:a {}})))

(defn execute [schema query-file variables]
  (l/execute schema (slurp (io/resource query-file)) variables nil))