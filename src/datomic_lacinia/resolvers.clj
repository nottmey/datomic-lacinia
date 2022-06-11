(ns datomic-lacinia.resolvers
  (:require [clojure.test :refer [deftest- is]]
            [clojure.walk :as w]
            [com.walmartlabs.lacinia.executor :as executor]
            [datomic-lacinia.datomic :as datomic]
            [datomic-lacinia.types :as types]
            [datomic-lacinia.utils :as utils]
            [io.pedestal.log :as log]))

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

; TODO generate resolvers for every other identity attribute (see https://docs.datomic.com/on-prem/schema/identity.html)

(defn pull-pattern [selection-tree selection-field->attr]
  (w/postwalk
    (fn [v]
      (let [r (if (and (sequential? v) (not (map-entry? v)))
                (->> v
                     (map :selections)
                     (filter some?)
                     (mapcat
                       #(mapcat
                          (fn [[selection-field subselection]]
                            (if-let [attr (selection-field->attr selection-field)]
                              [(if (empty? subselection) attr {attr subselection})]
                              subselection))
                          %))
                     distinct)
                v)]
        #_(println "val" v)
        #_(when (not (identical? v r))
            (println "res" r))
        r))
    [{:selections selection-tree}]))

(comment
  (let [selection-tree        {:Entity/db_ [{:selections {:DbContext/_rest [nil]}}]}
        selection-field->attr {:ReferencedByContext/artist_     nil,
                               :ReferencedByContext/_rest       nil,
                               :ReferencedByArtistContext/type  :artist/_type,
                               :ReferencedByArtistContext/_rest nil
                               :ArtistContext/name              :artist/name,
                               :ArtistContext/type              :artist/type,
                               :ArtistContext/_rest             nil
                               :DbContext/id                    :db/id,
                               :DbContext/ident                 :db/ident,
                               :DbContext/_rest                 nil,
                               :Entity/db_                      nil,
                               :Entity/referencedBy_            nil,
                               :Entity/artist_                  nil
                               :Entity/_rest                    nil}]
    (is (= (pull-pattern selection-tree selection-field->attr)
           [:db/id :db/ident :artist/name {:artist/type [:db/ident]}]))))

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
        selection-field->attr {:ReferencedByContext/artist_    nil,
                               :ArtistContext/name             :artist/name,
                               :DbContext/id                   :db/id,
                               :ArtistContext/type             :artist/type,
                               :DbContext/ident                :db/ident,
                               :ReferencedByArtistContext/type :artist/_type,
                               :Entity/db_                     nil,
                               :Entity/referencedBy_           nil,
                               :Entity/artist_                 nil}]
    (is (= (pull-pattern selection-tree selection-field->attr)
           [:db/id :db/ident :artist/name {:artist/type [:db/ident]}]))))

(defn get-resolver [resolve-db selection-field->attr]
  (fn [context {:keys [id] :as args} _]
    (log/debug :msg "get request"
               :args args)
    (let [eid     (Long/valueOf ^String id)
          db      (resolve-db)
          pattern (pull-pattern
                    (executor/selections-tree context)
                    selection-field->attr)
          result  (datomic/entity db eid pattern)]
      result)))

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
    (log/debug :msg "match request"
               :args args)
    (let [db       (resolve-db)
          db-paths (db-paths-with-values template response-objects entity-type)
          pattern  (pull-pattern
                     (executor/selections-tree context)
                     selection-field->attr)
          results  (datomic/matches db db-paths pattern)]
      results)))
