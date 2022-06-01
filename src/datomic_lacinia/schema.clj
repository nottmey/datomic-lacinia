(ns datomic-lacinia.schema
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest- is]]
            [datomic-lacinia.datomic :as datomic]
            [datomic-lacinia.graphql :as graphql]
            [datomic-lacinia.resolvers :as resolvers]
            [datomic-lacinia.testing :as testing]
            [datomic-lacinia.types :as types]
            [datomic-lacinia.utils :as utils]
            [datomic.client.api :as d]
            [io.pedestal.log :as log]))

(defn extend-aliases [attribute-aliases]
  (merge attribute-aliases
         (-> attribute-aliases
             (utils/update-ks datomic/back-ref)
             (utils/update-vs datomic/back-ref))))

(defn gen-value-field-config [field attribute entity-type]
  (let [attribute-ident       (:db/ident attribute)
        attribute-type        (:db/ident (:db/valueType attribute))
        attribute-cardinality (:db/ident (:db/cardinality attribute))
        type-config           {:type              (types/graphql-type
                                                    (= attribute-ident :db/id)
                                                    attribute-type
                                                    attribute-cardinality
                                                    entity-type)
                               :datomic/ident     attribute-ident
                               :datomic/valueType attribute-type
                               :resolve           (resolvers/value-field-resolver
                                                    field
                                                    attribute-ident
                                                    attribute-type)}]
    (if-let [description (:db/doc attribute)]
      (assoc type-config :description description)
      type-config)))

(deftest- gen-value-field-config-test
  (let [db    (d/db (testing/local-temp-conn))
        field (gen-value-field-config
                :ident
                (->> (datomic/attributes db)
                     (filter #(= (:db/ident %) :db/ident))
                     (first))
                :Entity)]
    (is (= (get field :type) :String))
    (is (= (get field :datomic/ident) :db/ident))
    (is (= (get field :datomic/valueType) :db.type/keyword))
    (is (= (get field :description) (:db/doc datomic/default-ident-attribute)))
    (is (= ((get field :resolve) {:db db :eid 0} nil nil) ":db.part/db"))))

(defn gen-context-field-config [object field]
  {:type        (graphql/response-type object field)
   :description (str "Nested " (str/replace (name field) "_" "") " data")
   :resolve     (resolvers/context-field-resolver field)})

(defn gen-back-ref-attribute [{:keys [db/ident]}]
  {::back-ref?     true
   :db/ident       (datomic/back-ref ident),
   :db/valueType   {:db/ident :db.type/ref},
   :db/cardinality {:db/ident :db.cardinality/many}
   :db/doc         (str "Attribute for entities which are referenced via " ident " by another entity")})

(defn gen-path [entity-type back-ref? ident]
  (concat
    (when entity-type
      [entity-type])
    (when back-ref?
      [(graphql/context-field :referencedBy)])
    (when (namespace ident)
      (map graphql/context-field (str/split (namespace ident) #"\.")))
    [(graphql/value-field (if back-ref? (subs (name ident) 1) (name ident)))]))

(defn gen-extended-attributes [attributes attribute-aliases]
  (->> (concat datomic/default-attributes attributes)
       (reduce
         (fn [distinct-paths {:keys [db/ident] :as a}]
           (let [path (gen-path nil false (get attribute-aliases ident ident))]
             (if-let [path-present (get distinct-paths path)]
               (do
                 (log/warn :msg "attribute overshadowed!"
                           :hint "use an alias to make the overshadowed attribute visible"
                           :overshadowed ident
                           :already-present (:db/ident path-present)
                           :fields-path (vec path))
                 distinct-paths)
               (assoc distinct-paths path a))))
         {})
       (vals)
       (mapcat
         (fn [a]
           (if (= (get-in a [:db/valueType :db/ident]) :db.type/ref)
             [a (gen-back-ref-attribute a)]
             [a])))))

(defn gen-attribute-paths [extended-attributes attribute-aliases entity-type]
  (->> extended-attributes
       (map (fn [{:keys [db/ident ::back-ref?]}]
              (let [aliased-ident (get attribute-aliases ident ident)]
                [(gen-path entity-type back-ref? aliased-ident)
                 ident])))))

(deftest- gen-attribute-paths-test
  (let [attribute {:db/ident       :track/artists
                   :db/valueType   {:db/ident :db.type/ref}
                   :db/cardinality {:db/ident :db.cardinality/many}}
        extended  (gen-extended-attributes [attribute] {})
        paths     (gen-attribute-paths extended {} :Entity)]
    (is (= paths
           '([(:Entity :db_ :id) :db/id]
             [(:Entity :db_ :ident) :db/ident]
             [(:Entity :track_ :artists) :track/artists]
             [(:Entity :referencedBy_ :track_ :artists) :track/_artists]))))

  (let [attribute {:db/ident       :0wie%rd.3n-amesp_ace2/arti%sts
                   :db/valueType   {:db/ident :db.type/ref}
                   :db/cardinality {:db/ident :db.cardinality/many}}
        extended  (gen-extended-attributes [attribute] {})
        paths     (gen-attribute-paths extended {} :Entity)]
    (is (= paths
           '([(:Entity :db_ :id) :db/id]
             [(:Entity :db_ :ident) :db/ident]
             [(:Entity :wierd_ :nAmespAce2_ :artists) :0wie%rd.3n-amesp_ace2/arti%sts]
             [(:Entity :referencedBy_ :wierd_ :nAmespAce2_ :artists) :0wie%rd.3n-amesp_ace2/_arti%sts]))))

  (let [attributes             [{:db/ident       :track/%artists
                                 :db/valueType   {:db/ident :db.type/ref}
                                 :db/cardinality {:db/ident :db.cardinality/many}}
                                {:db/ident       :track/artists
                                 :db/valueType   {:db/ident :db.type/ref}
                                 :db/cardinality {:db/ident :db.cardinality/many}}]
        extended-without-alias (gen-extended-attributes attributes {})
        paths-without-alias    (gen-attribute-paths extended-without-alias {} :Entity)
        attribute-aliases      {:track/artists :track/redefined-artists}
        extended-aliases       (extend-aliases attribute-aliases)
        extended-with-alias    (gen-extended-attributes attributes extended-aliases)
        paths-with-alias       (gen-attribute-paths extended-with-alias extended-aliases :Entity)]
    (is (= paths-without-alias
           '([(:Entity :db_ :id) :db/id]
             [(:Entity :db_ :ident) :db/ident]
             [(:Entity :track_ :artists) :track/%artists]
             [(:Entity :referencedBy_ :track_ :artists) :track/_%artists])))
    (is (= paths-with-alias
           '([(:Entity :db_ :id) :db/id]
             [(:Entity :db_ :ident) :db/ident]
             [(:Entity :track_ :artists) :track/%artists]
             [(:Entity :referencedBy_ :track_ :artists) :track/_%artists]
             [(:Entity :track_ :redefinedArtists) :track/artists]
             [(:Entity :referencedBy_ :track_ :redefinedArtists) :track/_artists])))))

(defn gen-response-objects [attributes attribute-aliases entity-type]
  (let [extended-aliases        (extend-aliases attribute-aliases)
        extended-attributes     (gen-extended-attributes attributes extended-aliases)
        extended-attributes-map (->> extended-attributes
                                     (map (fn [{:keys [db/ident] :as a}]
                                            (vector ident a)))
                                     (into {}))]
    (loop [response-objects {entity-type {:description "An entity of this application"}}
           [current-path & remaining-paths] (gen-attribute-paths extended-attributes extended-aliases entity-type)]
      (if-let [[[object field nested-field & more-fields] real-attribute-ident] current-path]
        (if nested-field
          (let [field-config    (gen-context-field-config object field)
                field-type      (:type field-config)
                field-type-desc (str "Nested data of field '" (str/replace (name field) "_" "") "' on type '" (name object) "'")]
            (recur
              (-> response-objects
                  (assoc-in [object :fields field] field-config)
                  (assoc-in [field-type :description] field-type-desc))
              (conj remaining-paths
                    [(concat [field-type nested-field] more-fields) real-attribute-ident])))
          (let [attribute    (get extended-attributes-map real-attribute-ident)
                field-config (gen-value-field-config field attribute entity-type)]
            (recur
              (assoc-in response-objects [object :fields field] field-config)
              remaining-paths)))
        response-objects))))

(deftest- gen-response-objects-test
  (let [objects (gen-response-objects [] {} :Entity)]
    (is (= (testing/clean objects [:resolve])
           {:DbContext {:description "Nested data of field 'db' on type 'Entity'"
                        :fields      {:id    {:datomic/ident     :db/id
                                              :datomic/valueType :db.type/long
                                              :description       "Attribute used to uniquely identify an entity, managed by Datomic."
                                              :type              :ID}
                                      :ident {:datomic/ident     :db/ident
                                              :datomic/valueType :db.type/keyword
                                              :description       "Attribute used to uniquely name an entity."
                                              :type              :String}}}
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
        objects          (gen-response-objects schema-with-refs {} :Entity)]
    (is (= (testing/clean objects [:resolve])
           {:ArtistContext            {:description "Nested data of field 'artist' on type 'Entity'"
                                       :fields      {:name {:datomic/ident     :artist/name
                                                            :datomic/valueType :db.type/string
                                                            :type              :String}}}
            :DbContext                {:description "Nested data of field 'db' on type 'Entity'"
                                       :fields      {:id    {:datomic/ident     :db/id
                                                             :datomic/valueType :db.type/long
                                                             :description       "Attribute used to uniquely identify an entity, managed by Datomic."
                                                             :type              :ID}
                                                     :ident {:datomic/ident     :db/ident
                                                             :datomic/valueType :db.type/keyword
                                                             :description       "Attribute used to uniquely name an entity."
                                                             :type              :String}}}
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
                                       :fields      {:artists     {:datomic/ident     :track/_artists
                                                                   :datomic/valueType :db.type/ref
                                                                   :description       "Attribute for entities which are referenced via :track/artists by another entity"
                                                                   :type              '(list :Entity)}
                                                     :influencers {:datomic/ident     :track/_influencers
                                                                   :datomic/valueType :db.type/ref
                                                                   :description       "Attribute for entities which are referenced via :track/influencers by another entity"
                                                                   :type              '(list :Entity)}}}
            :TrackContext             {:description "Nested data of field 'track' on type 'Entity'"
                                       :fields      {:artists     {:datomic/ident     :track/artists
                                                                   :datomic/valueType :db.type/ref
                                                                   :description       "Artists who contributed to the track"
                                                                   :type              '(list :Entity)}
                                                     :influencers {:datomic/ident     :track/influencers
                                                                   :datomic/valueType :db.type/ref
                                                                   :description       "Artists who had influences on the style of this track"
                                                                   :type              '(list :Entity)}
                                                     :name        {:datomic/ident     :track/name
                                                                   :datomic/valueType :db.type/string
                                                                   :type              :String}}}}))))

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

(defn gen-schema [{:keys [datomic/resolve-db
                          datomic/attributes
                          datomic/attribute-aliases
                          lacinia/entity-type]
                   :or   {attributes        []
                          attribute-aliases {}
                          entity-type       :Entity}
                   :as   params}]
  (when (nil? resolve-db)
    (throw (IllegalArgumentException. (str "missing " :datomic/resolve-db))))
  (log/debug :msg "generating schema" :params (dissoc params :datomic/resolve-db :datomic/attributes))
  (let [response-objects (gen-response-objects attributes attribute-aliases entity-type)]
    {:objects       response-objects
     :input-objects (gen-input-objects response-objects)
     :queries       {:get   {:type        entity-type
                             :description "Access any entity by its unique id, if it exists."
                             :args        {:id {:type :ID}}
                             :resolve     (resolvers/get-resolver resolve-db)}
                     :match {:type        (list 'list entity-type)
                             :description "Access any entity by matching fields."
                             :args        {:template {:type (graphql/input-type entity-type)}}
                             :resolve     (resolvers/match-resolver resolve-db response-objects entity-type)}}}))

