(ns datomic-lacinia.graphql
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest- is]]
            [datomic-lacinia.utils :as utils]))

(defn response-type [object field]
  (let [postfix     "Context"
        object-name (name object)
        field-name  (utils/uppercase-first (str/replace (name field) "_" ""))]
    (if (str/ends-with? object-name postfix)
      (-> object-name
          (subs 0 (- (count object-name) (count postfix)))
          (str field-name postfix)
          (keyword))
      (keyword (str field-name postfix)))))

(deftest- response-type-test
  (is (= (response-type :Entity :abstractRelease_) :AbstractReleaseContext))
  (is (= (response-type :AbstractReleaseContext :type_) :AbstractReleaseTypeContext)))

(defn input-type [response-type]
  (keyword (str (name response-type) "Request")))

(deftest- input-type-test
  (is (= (input-type :Entity) :EntityRequest)))

(defn value-field [raw]
  (let [cleaned-name (-> (name (keyword raw))
                         (str/replace #"[^A-Za-z0-9-_]" "")
                         (str/replace #"^[0-9]+" ""))]
    (->> (str/split cleaned-name #"[-_]+")
         (map utils/uppercase-first)
         (str/join)
         (utils/lowercase-first)
         (keyword))))

(deftest- value-field-test
  (is (= (value-field :initial%-import) :initialImport))
  (is (= (value-field :initial__import_) :initialImport))
  (is (= (value-field :$helloWorld____) :helloWorld))
  (is (= (value-field :0Hell=0) :hell0)))

(defn context-field [raw]
  (keyword (str (name (value-field raw)) "_")))

(deftest- context-field-test
  (is (= (context-field :artist) :artist_))
  (is (= (context-field :long-example) :longExample_)))

(defn object-field-comb [object field]
  {:pre [(nil? (namespace object))
         (nil? (namespace field))]}
  (keyword (name object) (name field)))

(deftest- object-field-comb-test
  (is (= (object-field-comb :Entity :db_) :Entity/db_)))