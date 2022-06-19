(ns datomic-lacinia.resolvers
  (:require [clojure.test :refer [deftest- is]]
            [clojure.walk :as w]
            [com.walmartlabs.lacinia.executor :as executor]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [datomic-lacinia.datomic :as datomic]
            [datomic-lacinia.graphql :as graphql]
            [datomic-lacinia.types :as types]
            [datomic-lacinia.utils :as utils]
            [io.pedestal.log :as log]
            [medley.core :as m]))

(defn value-field-resolver [field attribute-ident attribute-type]
  (fn [{:keys [com.walmartlabs.lacinia/container-type-name]} args value]
    (log/trace :msg "resolve value"
               :type container-type-name
               :field field
               :attribute [attribute-ident attribute-type]
               :args args
               :value value)
    (let [attr-value    (get value attribute-ident)
          resolve-value #(if (map? %) % (types/parse-db-value % attribute-type attribute-ident))]
      (if (sequential? attr-value)
        (map resolve-value attr-value)
        (resolve-value attr-value)))))

(defn context-field-resolver [field]
  (fn [{:keys [com.walmartlabs.lacinia/container-type-name]} args value]
    (log/trace :msg "resolve context"
               :type container-type-name
               :field field
               :args args
               :value value)
    value))

(defn filled-fields-field-resolver [field]
  (fn [{:keys [com.walmartlabs.lacinia/container-type-name selection-field->attr]} args value]
    (log/trace :msg "resolve filled fields"
               :type container-type-name
               :field field
               :args args
               :value value)
    (let [obj-field-comb (graphql/object-field-comb container-type-name field)
          attr->field    (selection-field->attr obj-field-comb)
          result         (->> (keys value)
                              (map #(get attr->field %))
                              (filter some?)
                              (map name)
                              distinct)]
      result)))

; TODO generate resolvers for every other identity attribute (see https://docs.datomic.com/on-prem/schema/identity.html)

(defn pull-pattern [selections selection-field->attr]
  (w/postwalk
    (fn [v]
      (let [r (if (and (sequential? v) (not (map-entry? v)))
                (->> v
                     (map :selections)
                     (filter some?)
                     (mapcat
                       #(mapcat
                          (fn [[selection-field subselection]]
                            (let [a-or-as (selection-field->attr selection-field)]
                              (cond
                                (nil? a-or-as) subselection
                                (and (keyword? a-or-as) (empty? subselection)) [a-or-as]
                                (and (keyword? a-or-as) (seq subselection)) [{a-or-as subselection}]
                                (and (map? a-or-as) (empty? subselection)) (vec (keys a-or-as))
                                :else (throw (AssertionError. "subselection not possible for scalar fields")))))
                          %))
                     ; sort keywords last (maps first), so that the unspecific pull is dropped by distinct-by
                     (sort-by keyword?)
                     (m/distinct-by #(if (map? %) (ffirst %) %)))
                v)]
        #_(println "val" v)
        #_(when (not (identical? v r))
            (println "res" r))
        r))
    [{:selections selections}]))

(deftest- pull-pattern-test
  (let [selection-tree        {:Entity/db_
                               [{:selections {:DbContext/id    [nil],
                                              :DbContext/ident [nil]}}],
                               :Entity/artist_
                               [{:selections {:ArtistContext/name [nil],
                                              :ArtistContext/type
                                              [{:selections {:Entity/db_
                                                             [{:alias :type, :selections {:DbContext/ident [nil]}}]}}
                                               {:alias      :another
                                                :selections {:Entity/db_
                                                             [{:alias :type, :selections {:DbContext/ident
                                                                                          [{:alias :name}
                                                                                           {:alias :other}]}}]}}]}}]}
        selection-field->attr {:DbContext/id                   :db/id,
                               :DbContext/ident                :db/ident,
                               :ArtistContext/name             :artist/name,
                               :ArtistContext/type             :artist/type,
                               :ReferencedByArtistContext/type :artist/_type}]
    (is (= (set (pull-pattern selection-tree selection-field->attr))
           (set [:db/id :db/ident :artist/name {:artist/type [:db/ident]}]))))

  (let [selection-tree        {:Entity/db_ [{:selections {:DbContext/id      [nil]
                                                          :DbContext/_fields [nil]}}]}
        selection-field->attr {:DbContext/id      :db/id,
                               :DbContext/ident   :db/ident,
                               :DbContext/_fields {:db/id    :id
                                                   :db/ident :ident}}]
    (is (= (set (pull-pattern selection-tree selection-field->attr))
           #{:db/id :db/ident})))

  (let [selection-tree        {:Entity/_fields [nil]
                               :Entity/db_     [{:selections {:DbContext/id      [nil]
                                                              :DbContext/_fields [nil]}}]}
        selection-field->attr {:ReferencedByArtistContext/type :artist/_type,
                               :ArtistContext/name             :artist/name,
                               :ArtistContext/type             :artist/type,
                               :DbContext/id                   :db/id,
                               :DbContext/ident                :db/ident,
                               :DbContext/_fields              {:db/id    :id
                                                                :db/ident :ident},
                               :Entity/_fields                 {:db/id        :db_
                                                                :db/ident     :db_
                                                                :artist/name  :artist_
                                                                :artist/type  :artist_
                                                                :artist/_type :referencedBy_}}]
    (is (= (set (pull-pattern selection-tree selection-field->attr))
           #{:db/id :db/ident :artist/name :artist/type :artist/_type}))))

(defn get-resolver [resolve-db selection-field->attr]
  (fn [context {:keys [id] :as args} _]
    (let [selections (executor/selections-tree context)]
      (log/debug :msg "get request"
                 :args args
                 :selections selections)
      (let [eid     (Long/valueOf ^String id)
            db      (resolve-db)
            pattern (pull-pattern selections selection-field->attr)
            result  (datomic/entity db eid pattern)]
        (resolve/with-context result {:selection-field->attr selection-field->attr})))))

(defn- db-paths-with-values [input-object response-objects entity-type]
  (->>
    (utils/paths input-object)
    (map
      (fn [[ks v]]
        (loop [graphql-context (get response-objects entity-type)
               graphql-path    ks
               db-path         []
               db-value-type   nil]
          (if-let [current-field (first graphql-path)]
            (let [current-attribute   (get-in graphql-context [:fields current-field :datomic/ident])
                  next-type           (get-in graphql-context [:fields current-field :type]) ; potentially '(list <type>)
                  next-type-unwrapped (if (list? next-type) (second next-type) next-type)]
              (recur
                (get response-objects next-type-unwrapped)
                (rest graphql-path)
                (if current-attribute
                  (conj db-path current-attribute)
                  db-path)
                (get-in graphql-context [:fields current-field :datomic/valueType])))
            [db-path
             (types/parse-graphql-value v db-value-type (last db-path))]))))))

(deftest- db-paths-with-values-test
  (let [artist-name      "<some artist>"
        song-name        "<some song artist was part of>"
        input-object     {:artist       {:name artist-name}
                          :referencedBy {:track {:artists [{:track {:name song-name}}]}}}
        response-objects {:Entity                  {:fields {:artist       {:type :EntityArtist},
                                                             :track        {:type :EntityTrack},
                                                             :referencedBy {:type :EntityReferencedBy}}},
                          :EntityArtist            {:fields {:name {:type              :String,
                                                                    :datomic/ident     :artist/name,
                                                                    :datomic/valueType :db.type/string}}},
                          :EntityTrack             {:fields {:name    {:type              :String,
                                                                       :datomic/ident     :track/name,
                                                                       :datomic/valueType :db.type/string},
                                                             :artists {:type              '(list :Entity),
                                                                       :datomic/ident     :track/artists,
                                                                       :datomic/valueType :db.type/ref}}},
                          :EntityReferencedBy      {:fields {:track {:type :EntityReferencedByTrack}}},
                          :EntityReferencedByTrack {:fields {:artists {:type              '(list :Entity),
                                                                       :datomic/ident     :track/_artists,
                                                                       :datomic/valueType :db.type/ref}}}}]
    (is (= (db-paths-with-values input-object response-objects :Entity)
           (list [[:artist/name] artist-name] [[:track/_artists :track/name] song-name])))))

(defn match-resolver [resolve-db
                      response-objects
                      selection-field->attr
                      entity-type]
  (fn [context {:keys [template] :as args} _]
    (let [selections (executor/selections-tree context)]
      (log/debug :msg "match request"
                 :args args
                 :selections selections)
      (let [db       (resolve-db)
            db-paths (db-paths-with-values template response-objects entity-type)
            pattern  (pull-pattern selections selection-field->attr)
            results  (datomic/matches db db-paths pattern)]
        (resolve/with-context results {:selection-field->attr selection-field->attr})))))
