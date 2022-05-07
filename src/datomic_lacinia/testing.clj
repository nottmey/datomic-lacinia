(ns datomic-lacinia.testing
  (:require [datomic.client.api :as d]
            [com.walmartlabs.lacinia :as l]
            [clojure.java.io :as io]))

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

(defn execute [schema query-file variables]
  (l/execute schema (slurp (io/resource query-file)) variables nil))