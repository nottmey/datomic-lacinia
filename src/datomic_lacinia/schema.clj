(ns datomic-lacinia.schema
  (:require [datomic-lacinia.utils :as utils]
            [datomic-lacinia.types :as types]
            [datomic-lacinia.datomic :as datomic]
            [datomic-lacinia.graphql :as graphql]
            [datomic-lacinia.resolvers :as resolvers]
            [clojure.test :refer [deftest- is]]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [datomic-lacinia.testing :as testing]
            [io.pedestal.log :as log]))

(defn gen-value-field-config [attribute default-entity-type]
  (let [attribute-ident       (:db/ident attribute)
        attribute-type        (:db/ident (:db/valueType attribute))
        attribute-cardinality (:db/ident (:db/cardinality attribute))
        type-config           {:type         (types/graphql-type
                                               attribute-ident
                                               attribute-type
                                               attribute-cardinality
                                               default-entity-type)
                               :db/attribute attribute-ident
                               :db/type      attribute-type
                               :resolve      (resolvers/value-field-resolver
                                               attribute-ident
                                               attribute-type)}]
    (if-let [description (:db/doc attribute)]
      (assoc type-config :description description)
      type-config)))

(deftest- gen-value-field-config-test
  (let [db    (d/db (testing/local-temp-conn))
        field (gen-value-field-config
                (->> (datomic/attributes db)
                     (filter #(= (:db/ident %) :db/ident))
                     (first))
                :Entity)]
    (is (= (get field :type) :String))
    (is (= (get field :db/attribute) :db/ident))
    (is (= (get field :db/type) :db.type/keyword))
    (is (= (get field :description) (:db/doc datomic/default-ident-attribute)))
    (is (= ((get field :resolve) {:db db :eid 0} nil nil) ":db.part/db"))))

(defn gen-context-field-config [object field]
  (let [field-type (graphql/response-type object field)]
    {:type        field-type
     :description (str "Nested " (str/replace (name field) "_" "") " data")
     :resolve     (resolvers/context-field-resolver)}))

(defn gen-back-ref-attribute [{:keys [db/ident]}]
  {:db/ident       (datomic/back-ref ident),
   :db/valueType   {:db/ident :db.type/ref},
   :db/cardinality {:db/ident :db.cardinality/many}
   :db/doc         (str "Attribute for entities which are referenced via " ident " by another entity")})

(defn gen-extended-attributes [attributes]
  (->> (concat datomic/default-attributes attributes)
       (mapcat (fn [a]
                 (if (= (get-in a [:db/valueType :db/ident]) :db.type/ref)
                   [a (gen-back-ref-attribute a)]
                   [a])))
       (map (fn [{:keys [db/ident] :as a}]
              (vector ident a)))
       (into {})))

(defn gen-attribute-paths [extended-attributes entity-type-key]
  (->> (keys extended-attributes)
       (map #(let [back-ref? (datomic/back-ref? %)]
               [(concat
                  [entity-type-key]
                  (when back-ref?
                    [(graphql/context-field :referencedBy)])
                  (when (namespace %)
                    (map graphql/context-field (str/split (namespace %) #"\.")))
                  [(graphql/value-field (if back-ref? (subs (name %) 1) (name %)))])
                %]))))

(deftest- gen-attribute-paths-test
  (let [attribute {:db/ident       :track/artists
                   :db/valueType   {:db/ident :db.type/ref}
                   :db/cardinality {:db/ident :db.cardinality/many}}
        extended  (gen-extended-attributes [attribute])
        paths     (gen-attribute-paths extended :Entity)]
    (is (= paths
           '([(:Entity :db_ :id) :db/id]
             [(:Entity :db_ :ident) :db/ident]
             [(:Entity :track_ :artists) :track/artists]
             [(:Entity :referencedBy_ :track_ :artists) :track/_artists])))))

(defn gen-response-objects [attributes entity-type-key]
  ; TODO add collision detection and make sure the first attribute wins
  (let [extended-attributes (gen-extended-attributes attributes)]
    (loop [response-objects {entity-type-key {:description "An entity of this application"}}
           [current-path & remaining-paths] (gen-attribute-paths extended-attributes entity-type-key)]
      (if-let [[[object field nested-field & more-fields] attribute-ident] current-path]
        (if nested-field
          (let [field-config    (gen-context-field-config object field)
                field-type      (:type field-config)
                field-type-desc (str "Nested data of field '" (str/replace (name field) "_" "") "' on type '" (name object) "'")]
            (recur
              (-> response-objects
                  (assoc-in [object :fields field] field-config)
                  (assoc-in [field-type :description] field-type-desc))
              (conj remaining-paths
                    [(concat [field-type nested-field] more-fields) attribute-ident])))
          (let [attribute    (get extended-attributes attribute-ident)
                field-config (gen-value-field-config attribute entity-type-key)]
            (recur
              (assoc-in response-objects [object :fields field] field-config)
              remaining-paths)))
        response-objects))))

(deftest- gen-response-objects-test
  (let [objects (gen-response-objects datomic/default-attributes :Entity)]
    (is (= (testing/clean objects [:resolve])
           {:DbContext {:description "Nested data of field 'db' on type 'Entity'"
                        :fields      {:id    {:db/attribute :db/id
                                              :db/type      :db.type/long
                                              :description  "Attribute used to uniquely identify an entity, managed by Datomic."
                                              :type         :ID}
                                      :ident {:db/attribute :db/ident
                                              :db/type      :db.type/keyword
                                              :description  "Attribute used to uniquely name an entity."
                                              :type         :String}}}
            :Entity    {:description "An entity of this application"
                        :fields      {:db_ {:description "Nested db data"
                                            :type        :DbContext}}}})))

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
                               :doc         "Artists who had influences on the style of this track"}]
        objects          (gen-response-objects schema-with-refs :Entity)]
    (is (= (testing/clean objects [:resolve])
           {:ArtistContext            {:description "Nested data of field 'artist' on type 'Entity'"
                                       :fields      {:name {:db/attribute :artist/name
                                                            :db/type      :db.type/string
                                                            :type         :String}}}
            :DbContext                {:description "Nested data of field 'db' on type 'Entity'"
                                       :fields      {:id    {:db/attribute :db/id
                                                             :db/type      :db.type/long
                                                             :description  "Attribute used to uniquely identify an entity, managed by Datomic."
                                                             :type         :ID}
                                                     :ident {:db/attribute :db/ident
                                                             :db/type      :db.type/keyword
                                                             :description  "Attribute used to uniquely name an entity."
                                                             :type         :String}}}
            :Entity                   {:description "An entity of this application"
                                       :fields      {:artist_       {:description "Nested artist data"
                                                                     :type        :ArtistContext}
                                                     :db_           {:description "Nested db data"
                                                                     :type        :DbContext}
                                                     :referencedBy_ {:description "Nested referencedBy data"
                                                                     :type        :ReferencedByContext}
                                                     :track_        {:description "Nested track data"
                                                                     :type        :TrackContext}}}
            :ReferencedByContext      {:description "Nested data of field 'referencedBy' on type 'Entity'"
                                       :fields      {:track_ {:description "Nested track data"
                                                              :type        :ReferencedByTrackContext}}}
            :ReferencedByTrackContext {:description "Nested data of field 'track' on type 'ReferencedByContext'"
                                       :fields      {:artists     {:db/attribute :track/_artists
                                                                   :db/type      :db.type/ref
                                                                   :description  "Attribute for entities which are referenced via :track/artists by another entity"
                                                                   :type         '(list :Entity)}
                                                     :influencers {:db/attribute :track/_influencers
                                                                   :db/type      :db.type/ref
                                                                   :description  "Attribute for entities which are referenced via :track/influencers by another entity"
                                                                   :type         '(list :Entity)}}}
            :TrackContext             {:description "Nested data of field 'track' on type 'Entity'"
                                       :fields      {:artists     {:db/attribute :track/artists
                                                                   :db/type      :db.type/ref
                                                                   :description  "Artists who contributed to the track"
                                                                   :type         '(list :Entity)}
                                                     :influencers {:db/attribute :track/influencers
                                                                   :db/type      :db.type/ref
                                                                   :description  "Artists who had influences on the style of this track"
                                                                   :type         '(list :Entity)}
                                                     :name        {:db/attribute :track/name
                                                                   :db/type      :db.type/string
                                                                   :type         :String}}}}))))

(defn gen-input-objects [response-objects]
  (let [ref->input-ref           (fn [k] (if (get response-objects k) (graphql/input-type k) k))
        ref-type->input-ref-type (fn [t] (if (seq? t)
                                           (seq (update (vec t) 1 ref->input-ref))
                                           (ref->input-ref t)))
        field->input-field       (fn [f] (update f :type ref-type->input-ref-type))]
    (-> (utils/update-ks response-objects graphql/input-type)
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
  (log/debug :msg "generating schema")
  (let [response-objects (gen-response-objects attributes entity-type-key)]
    {:objects       response-objects
     :input-objects (gen-input-objects response-objects)
     :queries       {:get   {:type        entity-type-key
                             :description "Access any entity by its unique id, if it exists."
                             :args        {:id {:type :ID}}
                             :resolve     (resolvers/get-resolver resolve-db)}
                     :match {:type        (list 'list entity-type-key)
                             :description "Access any entity by matching fields."
                             :args        {:template {:type (graphql/input-type entity-type-key)}}
                             :resolve     (resolvers/match-resolver resolve-db response-objects entity-type-key)}}}))

