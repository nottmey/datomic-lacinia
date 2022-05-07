(ns datomic-lacinia.testing
  (:require [datomic.client.api :as d]))

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