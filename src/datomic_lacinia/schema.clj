(ns datomic-lacinia.schema
  (:require [datomic-lacinia.utils :as utils]
            [datomic-lacinia.types :as types]
            [datomic-lacinia.datomic :as datomic]
            [datomic-lacinia.graphql :as graphql]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [clojure.string :as str]))

(defn gql-field-config [attribute default-entity-type]
  (let [attribute-ident (:db/ident attribute)
        attribute-type  (:db/ident (:db/valueType attribute))
        type-config     {:type         (types/gql-type attribute default-entity-type)
                         :db/attribute attribute-ident
                         :db/type      attribute-type
                         :resolve      (fn [{:keys [db eid]} _ _]
                                         (let [db-value      (datomic/value db eid attribute-ident)
                                               resolve-value #(if (map? %)
                                                                (resolve/with-context {} {:eid (:db/id %)})
                                                                (types/parse-db-value % attribute-type attribute-ident))]
                                           (if (sequential? db-value)
                                             (map resolve-value db-value)
                                             (resolve-value db-value))))}]
    (if-let [description (:db/doc attribute)]
      (assoc type-config :description description)
      type-config)))

(comment
  (second (vals (datomic/attributes (datomic/current-db))))
  (gql-field-config (second (vals (datomic/attributes (datomic/current-db)))) :Entity))

(defn gen-result-objects [attributes entity-type-key]
  (let [attributes-index  (->> attributes
                               (concat [{:db/ident       :db/id
                                         :db/valueType   {:db/ident :db.type/long}
                                         :db/cardinality {:db/ident :db.cardinality/one}
                                         :db/unique      {:db/ident :db.unique/identity},
                                         :db/doc         "Attribute used to uniquely identify an entity, managed by Datomic."}
                                        {:db/ident       :db/ident,
                                         :db/valueType   {:db/ident :db.type/keyword},
                                         :db/cardinality {:db/ident :db.cardinality/one},
                                         :db/unique      {:db/ident :db.unique/identity},
                                         :db/doc         "Attribute used to uniquely name an entity."}])
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
                                         (if back-ref? [:referencedBy] []) ;; TODO make this a configurable
                                         (if (namespace %) (str/split (namespace %) #"\.") [])
                                         (if back-ref? [(subs (name %) 1)] [(name %)])
                                         [%])))
                               (map #(map keyword %))
                               (map #(cons entity-type-key %)))]
    ;; TODO check results
    (loop [objects         {entity-type-key {:description "Any entity of this application."}}
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
                field-config    (gql-field-config attribute entity-type-key)]
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

(defn db-paths-with-values [input-object gql-objects default-entity-type]
  (->>
    (utils/paths input-object)
    (map
      (fn [[ks v]]
        (loop [gql-context   (get gql-objects default-entity-type)
               gql-path      ks
               db-path       []
               db-value-type nil]
          (if-let [current-field (first gql-path)]
            (let [current-attribute   (get-in gql-context [:fields current-field :db/attribute])
                  next-type           (get-in gql-context [:fields current-field :type]) ; potentially '(list <type>)
                  next-type-unwrapped (if (list? next-type) (second next-type) next-type)]
              (recur
                (get gql-objects next-type-unwrapped)
                (rest gql-path)
                (if current-attribute
                  (conj db-path current-attribute)
                  db-path)
                (get-in gql-context [:fields current-field :db/type])))
            [db-path
             (types/parse-gql-value v db-value-type (last db-path))]))))))

(comment
  (let [input-object {:db {:id "130" :cardinality {:db {:ident ":db.cardinality/many"}}}}
        db           (datomic/current-db)
        gql-objects  (gen-result-objects (datomic/attributes db) :Entity)]
    (db-paths-with-values input-object gql-objects :Entity))

  (let [input-object {:referencedBy {:track {:artists [{:track {:name "Moby Dick"}}]}}}
        db           (datomic/current-db)
        gql-objects  (gen-result-objects (datomic/attributes db) :Entity)]
    (db-paths-with-values input-object gql-objects :Entity)))


(defn gen-input-objects [result-objects]
  (let [ref->input-ref           (fn [k] (if (get result-objects k) (graphql/input-type-key k) k))
        ref-type->input-ref-type (fn [t] (if (seq? t)
                                           (seq (update (vec t) 1 ref->input-ref))
                                           (ref->input-ref t)))
        field->input-field       (fn [f] (update f :type ref-type->input-ref-type))]
    (-> (update-keys result-objects graphql/input-type-key)
        (update-vals #(update % :fields update-vals field->input-field)))))

(defn gen-schema [{:keys [resolve-db attributes entity-type-key] :or {entity-type-key :Entity}}]
  (let [result-objects (gen-result-objects attributes entity-type-key)
        get-resolver   (fn [_ {:keys [id]} _]
                         ;; TODO check id, if available etc.
                         (resolve/with-context {} {:eid (parse-long id) :db (resolve-db)}))
        match-resolver (fn [_ {:keys [template]} _]
                         (let [db       (resolve-db)
                               db-paths (db-paths-with-values template result-objects entity-type-key)
                               results  (datomic/matches db db-paths)]
                           (map #(resolve/with-context {} {:eid % :db db}) results)))]
    ;; TODO add time basis to requests
    ;; TODO add database query to tracing (or own tracing mode for it)
    ;; TODO add 'what else is available field to entity, etc?'
    ;; TODO use https://github.com/vlaaad/plusinia for optimization
    ;; TODO try out https://github.com/Datomic/ion-starter
    {:objects       result-objects
     :input-objects (gen-input-objects result-objects)
     :queries       {:get   {:type        entity-type-key
                             :description "Access any entity by its unique id, if it exists."
                             :args        {:id {:type :ID}}
                             :resolve     get-resolver}
                     :match {:type        (list 'list entity-type-key)
                             :description "Access any entity by matching fields."
                             :args        {:template {:type (graphql/input-type-key entity-type-key)}}
                             :resolve     match-resolver}}}))

