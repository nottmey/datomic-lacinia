(ns datomic-lacinia.schema
  (:require [datomic-lacinia.utils :as utils]
            [datomic-lacinia.types :as types]
            [datomic-lacinia.datomic :as datomic]
            [datomic-lacinia.graphql :as graphql]
            [datomic-lacinia.resolvers :as resolvers]
            [clojure.test :refer [deftest- is]]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [datomic-lacinia.testing :as testing]))

(defn gen-field-config [attribute default-entity-type]
  (let [attribute-ident       (:db/ident attribute)
        attribute-type        (:db/ident (:db/valueType attribute))
        attribute-cardinality (:db/ident (:db/cardinality attribute))
        type-config           {:type         (types/gql-type
                                               attribute-ident
                                               attribute-type
                                               attribute-cardinality
                                               default-entity-type)
                               :db/attribute attribute-ident
                               :db/type      attribute-type
                               :resolve      (resolvers/field-resolver attribute-ident attribute-type)}]
    (if-let [description (:db/doc attribute)]
      (assoc type-config :description description)
      type-config)))

(deftest- gen-field-config-test
  (let [db    (d/db (testing/local-temp-conn))
        field (gen-field-config
                (->> (datomic/attributes db)
                     (filter #(= (:db/ident %) :db/ident))
                     (first))
                :Entity)]
    (is (= (get field :type) :String))
    (is (= (get field :db/attribute) :db/ident))
    (is (= (get field :db/type) :db.type/keyword))
    (is (= (get field :description) (:db/doc datomic/default-ident-attribute)))
    (is (= ((get field :resolve) {:db db :eid 0} nil nil) ":db.part/db"))))

(defn gen-result-objects [attributes entity-type-key]
  (let [attributes-index  (->> (concat datomic/default-attributes attributes)
                               (mapcat
                                 (fn [v]
                                   (if (= (get-in v [:db/valueType :db/ident]) :db.type/ref)
                                     [v (-> v
                                            (update :db/ident #(keyword (namespace %) (str "_" (name %))))
                                            (assoc-in [:db/cardinality :db/ident] :db.cardinality/many)
                                            (assoc :db/doc (str "Holds all entities which referencing via " (v :db/ident))))]
                                     [v])))
                               (map #(vector (% :db/ident) %))
                               (into {}))
        path-to-attribute (->> (keys attributes-index)
                               (map #(let [back-ref? (str/starts-with? (name %) "_")]
                                       (concat
                                         (if back-ref? [:referencedBy] [])
                                         (if (namespace %) (str/split (namespace %) #"\.") [])
                                         (if back-ref? [(subs (name %) 1)] [(name %)])
                                         [%])))
                               (map #(map keyword %))
                               (map #(cons entity-type-key %)))]
    ;; TODO add collision detection and make sure the first attribute wins
    (loop [objects         {entity-type-key {:description "An entity of this application."}}
           remaining-paths path-to-attribute]
      (if-let [[object raw-field attribute-ident-or-nested-raw-field & tail] (first remaining-paths)]
        (if tail
          (let [nested-raw-field       attribute-ident-or-nested-raw-field
                field-name             (graphql/field-key raw-field)
                field-type-name        (graphql/result-type-key object field-name)
                field-type-description (str "Nested data of attribute " field-name " on type " object)
                field-description      (str "Nested data " field-name " as type " field-type-name)
                field-resolver         (fn [_ _ _] {})
                field-config           {:type        field-type-name
                                        :description field-description
                                        :resolve     field-resolver}]
            (recur
              (-> objects
                  (assoc-in [field-type-name :description] field-type-description)
                  (assoc-in [object :fields field-name] field-config))
              (conj (rest remaining-paths)
                    (concat [field-type-name nested-raw-field] tail))))
          (let [attribute-ident attribute-ident-or-nested-raw-field
                attribute       (get attributes-index attribute-ident)
                field-name      (graphql/field-key raw-field)
                field-config    (gen-field-config attribute entity-type-key)]
            (recur
              (assoc-in objects [object :fields field-name] field-config)
              (rest remaining-paths))))
        objects))))

(comment
  (gen-result-objects (datomic/attributes (datomic/current-db)) :Entity)

  ;; checking for backref
  (let [schema-with-refs [#:db{:ident       :artist/name,
                               :valueType   #:db{:ident :db.type/string},
                               :cardinality #:db{:ident :db.cardinality/one}}
                          #:db{:ident       :track/name,
                               :valueType   #:db{:ident :db.type/string},
                               :cardinality #:db{:ident :db.cardinality/one}}
                          #:db{:ident       :track/artists,
                               :valueType   #:db{:ident :db.type/ref},
                               :cardinality #:db{:ident :db.cardinality/many},
                               :doc         "Artists who contributed to the track"}
                          #:db{:ident       :track/influencers
                               :valueType   #:db{:ident :db.type/ref}
                               :cardinality #:db{:ident :db.cardinality/many}
                               :doc         "Artists who had influences on the style of this track"}]]
    (gen-result-objects schema-with-refs :Entity)))

(defn gen-input-objects [result-objects]
  (let [ref->input-ref           (fn [k] (if (get result-objects k) (graphql/input-type-key k) k))
        ref-type->input-ref-type (fn [t] (if (seq? t)
                                           (seq (update (vec t) 1 ref->input-ref))
                                           (ref->input-ref t)))
        field->input-field       (fn [f] (update f :type ref-type->input-ref-type))]
    (-> (utils/update-ks result-objects graphql/input-type-key)
        (utils/update-vs #(update % :fields utils/update-vs field->input-field)))))


; TODO add time basis to requests
; TODO add database query to tracing (or own tracing mode for it)
; TODO add 'what else is available field to entity, etc?'
; TODO use https://github.com/vlaaad/plusinia for optimization
; TODO try out https://github.com/Datomic/ion-starter
; TODO keep field intact when attribute renaming happens (old request don't break)
; TODO add security (query limiting, authorization, etc.)
; TODO add configuration options for schema features

(defn gen-schema [{:keys [resolve-db attributes entity-type-key] :or {entity-type-key :Entity}}]
  (let [result-objects (gen-result-objects attributes entity-type-key)]
    {:objects       result-objects
     :input-objects (gen-input-objects result-objects)
     :queries       {:get   {:type        entity-type-key
                             :description "Access any entity by its unique id, if it exists."
                             :args        {:id {:type :ID}}
                             :resolve     (resolvers/get-resolver resolve-db)}
                     :match {:type        (list 'list entity-type-key)
                             :description "Access any entity by matching fields."
                             :args        {:template {:type (graphql/input-type-key entity-type-key)}}
                             :resolve     (resolvers/match-resolver resolve-db result-objects entity-type-key)}}}))

