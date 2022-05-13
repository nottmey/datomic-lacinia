(ns datomic-lacinia.graphql
  (:require [datomic-lacinia.utils :as utils]
            [clojure.string :as str]
            [clojure.test :refer [deftest- is]]))

(defn response-type-key [& type-or-field-keys]
  ;; TODO ensure PascalCase
  ;; TODO ensure valid characters are used
  (->> type-or-field-keys
       (map name)
       (map utils/uppercase-first)
       (str/join)
       (keyword)))

(deftest- response-type-key-test
  (is (= (response-type-key :Entity :abstractRelease) :EntityAbstractRelease)))

(defn input-type-key [type-key]
  (keyword (str (name type-key) "Request")))

(deftest- input-type-key-test
  (is (= (input-type-key :Entity) :EntityRequest)))

(defn field-key [raw-field]
  ;; TODO ensure camelCase
  ;; TODO ensure valid characters are used
  (->> (str/split (name raw-field) #"-")
       (map utils/uppercase-first)
       (str/join)
       (utils/lowercase-first)
       (keyword)))

(deftest- field-key-test
  (is (= (field-key :initial-import) :initialImport))
  (is (= (field-key :helloWorld) :helloWorld))
  (is (= (field-key :Hello) :hello)))
