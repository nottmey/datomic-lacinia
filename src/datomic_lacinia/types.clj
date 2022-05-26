(ns datomic-lacinia.types
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest- is]]))


(defn parse-graphql-value [value db-attribute-type db-attribute-ident]
  (if (= db-attribute-ident :db/id)
    (Long/valueOf ^String value)
    (condp = db-attribute-type
      ;; the following types don't have a direct counterpart
      :db.type/symbol value                                 ;; TODO data handling
      :db.type/keyword (edn/read-string value)
      :db.type/instant value                                ;; TODO data handling
      :db.type/uuid value                                   ;; TODO data handling
      :db.type/fn value                                     ;; TODO data handling
      :db.type/uri value                                    ;; TODO data handling
      :db.type/tuple value                                  ;; TODO tuples
      ;; the other types have a direct counterpart, so we don't need to parse them
      value)))

(deftest- parse-graphql-value-test
  (is (= (parse-graphql-value ":db.type/long" :db.type/keyword :_) :db.type/long))
  (is (= (parse-graphql-value "130" :db.type/long :db/id) 130))
  (is (= (parse-graphql-value 130 :db.type/long :_) 130)))

(defn parse-db-value [value db-attribute-type db-attribute-ident]
  (if (= db-attribute-ident :db/id)
    (str value)
    (condp = db-attribute-type
      ;; the following types don't have a direct counterpart
      :db.type/symbol value                                 ;; TODO data handling
      :db.type/keyword (if value (str value) value)
      :db.type/instant value                                ;; TODO data handling
      :db.type/uuid value                                   ;; TODO data handling
      :db.type/fn value                                     ;; TODO data handling
      :db.type/uri value                                    ;; TODO data handling
      :db.type/tuple value                                  ;; TODO tuples
      ;; the other types have a direct counterpart, so we don't need to parse them
      value)))

(deftest- parse-db-value-test
  (is (= (parse-db-value 130 :db.type/long :db/id) "130"))
  (is (= (parse-db-value 130 :db.type/long :_) 130))
  (is (= (parse-db-value "hello" :db.type/string :_) "hello"))
  (is (= (parse-db-value :db.type/long :db.type/keyword :_) ":db.type/long")))

(defn graphql-type [attribute-ident attribute-type attribute-cardinality default-entity-type]
  (if (= attribute-ident :db/id)
    :ID
    (let [graphql-type (condp = attribute-type
                         :db.type/ref default-entity-type
                         :db.type/boolean :Boolean
                         :db.type/long :Int
                         :db.type/bigint :Int
                         :db.type/float :Float
                         :db.type/double :Float
                         :db.type/bigdec :Float
                         :db.type/string :String
                         :db.type/symbol :String            ;; TODO data handling
                         :db.type/keyword :String
                         :db.type/instant :String           ;; TODO data handling
                         :db.type/uuid :String              ;; TODO data handling
                         :db.type/fn :String                ;; TODO data handling
                         :db.type/uri :String               ;; TODO data handling
                         ;; TODO tuples
                         :db.type/tuple :String)]
      (condp = attribute-cardinality
        :db.cardinality/one graphql-type
        :db.cardinality/many (list 'list graphql-type)))))
