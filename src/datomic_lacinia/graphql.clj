(ns datomic-lacinia.graphql
  (:require [datomic-lacinia.utils :as utils]
            [clojure.string :as str]
            [clojure.test :refer [deftest- is]]))

(defn response-type [& type-or-field-keys]
  ;; TODO ensure PascalCase
  ;; TODO ensure valid characters are used
  (->> type-or-field-keys
       (map name)
       (map utils/uppercase-first)
       (str/join)
       (keyword)))

(deftest- response-type-test
  (is (= (response-type :Entity :abstractRelease) :EntityAbstractRelease)))

(defn input-type [response-type]
  (keyword (str (name response-type) "Request")))

(deftest- input-type-test
  (is (= (input-type :Entity) :EntityRequest)))

(defn field [raw]
  ;; TODO ensure camelCase
  ;; TODO ensure valid characters are used
  (->> (str/split (name (keyword raw)) #"-")
       (map utils/uppercase-first)
       (str/join)
       (utils/lowercase-first)
       (keyword)))

(deftest- field-key-test
  (is (= (field :initial-import) :initialImport))
  (is (= (field :helloWorld) :helloWorld))
  (is (= (field :Hello) :hello)))
