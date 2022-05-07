(ns user
  (:require [datomic-lacinia.testing :as testing]
            [datomic-lacinia.datomic :as datomic]
            [datomic-lacinia.schema :as schema]
            [datomic-lacinia.mbrainz-example-test :as mbrainz]
            [com.walmartlabs.lacinia.schema :as ls]
            [com.walmartlabs.lacinia.pedestal2 :as lp]
            [io.pedestal.http :as http]
            [clojure.java.browse :refer [browse-url]]
            [datomic.client.api :as d]))

(defonce server nil)

(defn start! [tx-attributes tx-data]
  (alter-var-root
    #'server
    (fn [prev-server]
      (when prev-server
        (http/stop prev-server))
      (let [relevant-idents (set (map :db/ident tx-attributes))
            conn            (testing/local-temp-conn "repl")
            _               (d/transact conn {:tx-data tx-attributes})
            _               (d/transact conn {:tx-data tx-data})
            attributes      (->> (datomic/attributes (d/db conn))
                                 (filter #(contains? relevant-idents (:db/ident %))))
            compile-schema  (fn [] (ls/compile (schema/gen-schema {:resolve-db #(d/db conn)
                                                                   :attributes attributes})))
            server          (-> compile-schema
                                (lp/default-service nil)
                                http/create-server
                                http/start)]
        (browse-url "http://localhost:8888/ide")
        server)))
  :started)

(defn start-mbrainz! []
  (start! mbrainz/tx-attributes mbrainz/tx-data))

(defn stop! []
  (alter-var-root
    #'server
    (fn [prev-server]
      (when prev-server
        (http/stop prev-server))
      nil))
  :stopped)
