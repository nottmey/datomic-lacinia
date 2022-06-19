(ns user
  (:require [clojure.java.browse :refer [browse-url]]
            [com.walmartlabs.lacinia.pedestal2 :as lp]
            [com.walmartlabs.lacinia.schema :as ls]
            [datomic-lacinia.datomic :as datomic]
            [datomic-lacinia.mbrainz-example-test :as mbrainz]
            [datomic-lacinia.schema :as schema]
            [datomic-lacinia.testing :as testing]
            [datomic.client.api :as d]
            [io.pedestal.http :as http]))

(defonce server nil)

(defonce ^:dynamic browser? true)

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
            compile-schema  (fn [] (ls/compile (schema/gen-schema {:datomic/resolve-db          #(d/db conn)
                                                                   :datomic/attributes          attributes
                                                                   :lacinia/filled-fields-field :_fields})))
            server          (-> compile-schema
                                (lp/default-service nil)
                                http/create-server
                                http/start)]
        (when browser?
          (browse-url "http://localhost:8888/ide"))
        server)))
  :started)

(defn start-mbrainz! []
  (start! mbrainz/tx-attributes mbrainz/tx-data))

(defn start-mbrainz-via-deps! [_]
  (with-bindings {#'browser? false}
    (start-mbrainz!)))

(defn stop! []
  (alter-var-root
    #'server
    (fn [prev-server]
      (when prev-server
        (http/stop prev-server))
      nil))
  :stopped)
