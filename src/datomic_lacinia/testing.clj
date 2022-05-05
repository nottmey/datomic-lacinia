(ns datomic-lacinia.testing
  (:require [datomic.client.api :as d]))

(defn local-temp-conn []
  (let [client   (d/client {:server-type :dev-local
                            :storage-dir :mem
                            :system      "testing"})
        database {:db-name "testing"}
        _        (d/delete-database client database)
        _        (d/create-database client database)
        conn     (d/connect client database)]
    conn))