# Datomic & Lacinia

A pragmatic [Lacinia](https://github.com/walmartlabs/lacinia) schema generator for your [Datomic](https://www.datomic.com/) schema, which tries to fill the complete gap in between, while adhering to the principles of both worlds.

Essentially this gives you a neat GraphQL API for your Datomic instance, when combined with [Lacinia-Pedestal](https://github.com/walmartlabs/lacinia-pedestal).

[![state](https://img.shields.io/badge/state-draft_(some_todos_left,_not_optimized,_structure_might_change)-red.svg)](https://github.com/nottmey/datomic-lacinia/issues)
[![feedback](https://img.shields.io/badge/feedback-welcome!_(anything_helps)-informational.svg)](https://github.com/nottmey/datomic-lacinia/issues)

## Use-case

Generating a stable and complete API contract directly from your database, with minimal effort. Particularly useful for (non-clojure) API consumers which can generate a client from this contract (e.g. Flutter). This also minimizes effort for them (or you, if you build both).

Enabling you to automatically grow your API with your database schema, without breaking old clients. You could even regenerate your schema in-process, every time your database schema grows.

Possible use-case in the future: Allowing you to move your schema or database while keeping the API stable. (Possibly through configuration of mappings from old to new attributes. Or just reuse the generated schema with your own resolvers.)

> This is very helpful, when you want to support clients which are permanently installed. These often have an unreliable update mechanism (e.g. Android & iOS Apps). Your only choice is to keep your API stable and never break your old clients. This Datomic-GraphQL-Stack could help you to never worry about breakage and could enable you to just move forward.

## Usage

```clojure
(ns usage
  (:require [datomic.client.api :as d]
            [datomic-lacinia.schema :as schema]
            [datomic-lacinia.datomic :as datomic]))

; given a database connection
(def conn ,,,)

; load your database attributes (or query them yourself)
(def attributes
  (datomic/attributes (d/db conn)))

; maybe do some additional filtering on your attributes

; generate a schema with build-in resolvers
(def lacinia-schema
  (schema/gen-schema {:attributes attributes
                      :resolve-db #(d/db conn)}))

; overwrite resolvers if you want

; compile your schema with lacinia and pass it e.g. to lacinia-pedestal:
;  (io.pedestal.http/start
;    (io.pedestal.http/create-server
;      (com.walmartlabs.lacinia.pedestal2/default-service
;        (com.walmartlabs.lacinia.schema/compile lacinia-schema) nil)))
```

## Considerations

### Namespaced Keys

GraphQL is not used to namespaced keys. Including the namespace in the key (e.g. mapping `:medium.format/name` to e.g. `medium_format__name`) is not convenient in usage and might cause collisions (you will need to find a bidirectional mapping). Also, `camelCase` is the default naming convention for GraphQL fields, which only gives you one way to differentiate a word break. 

Ultimately, in GraphQL the default is to provide context via nesting, not so much via prefixing. That's why I chose to map e.g. `:medium.format/name` to `medium { format { name } }` instead.

### IDs

In GraphQL IDs are assumed to be strings, in Datomic they are integers (`long`). So, I implemented parsing accordingly.

### Entity Types

Datomic doesn't know about entity types. There are just entities and relations. This does not conflict with GraphQL. GraphQL just would happily allow us to have more types.

So, I just started with the general case and will consider generating type based versions of the queries, if configured. Maybe reusing meta information from your schema is a way to go. Forcing you to transact special meta attributes to your schema, so this generator can do more magic, does not feel like the right solution though. (Happy to hear your feedback on this!) 

## Examples

Extracted from [test/datomic_lacinia/schema_test.clj](test/datomic_lacinia/schema_test.clj). Subset of the plain old [Datomic mbrainz example](https://github.com/Datomic/mbrainz-sample).

```clojure
; suppose you transact attributes like these
(def tx-attributes
  [{:db/ident       :artist/name,
    :db/valueType   :db.type/string,
    :db/cardinality :db.cardinality/one,
    :db/doc         "The artist's name"}
   {:db/ident       :artist/type,
    :db/valueType   :db.type/ref,
    :db/cardinality :db.cardinality/one,
    :db/doc         "Enum, one of :artist.type/person, :artist.type/other, :artist.type/group."}])

; which allows you to transact data like this
(def tx-data
  [{:db/id       "1"
    :artist/name "Led Zeppelin"
    :artist/type {:db/ident :artist.type/group}}
   {:db/id       "2"
    :artist/name "John Lennon"
    :artist/type {:db/ident :artist.type/person}}
   {:db/id       "3"
    :artist/name "The Beatles"
    :artist/type {:db/ident :artist.type/group}}])
```

When you then generate, compile and provide your Datomic-GraphQL-API (see [Usage](#usage)), you can do queries (with introspection!) like these:

```graphql
query($id: ID!) { # with database id for temp id "1"
  get(id: $id) {
    # generic type "Entity"
    db {
      # all attributes of namespace "db"
      id
    }
    artist {
      # all attributes of namespace "artist"
      name
      type {
        # :artist/type is a ref, so we are in type "Entity" again
        db {
          # keywords are just values
          ident
        }
      }
    }
  }
}
```
```json
{
  "data": {
    "get": {
      "db": {
        "id": "92358976733259"
      },
      "artist": {
        "name": "Led Zeppelin",
        "type": {
          "db": {
            "ident": ":artist.type/group"
          }
        }
      }
    }
  }
}
```

---

```graphql
query {
  # leveraging the power of datalog and full indexing :)
  match(template: {
    artist: {
      name: "John Lennon"
    }
  }) {
    db {
      id
    }
    artist {
      name
      type {
        db {
          ident
        }
      }
    }
  }
}
```
```json
{
  "data": {
    "match": [
      {
        "db": {
          "id": "92358976733261"
        },
        "artist": {
          "name": "John Lennon",
          "type": {
            "db": {
              "ident": ":artist.type/person"
            }
          }
        }
      }
    ]
  }
}

```

## License

Copyright © 2022 Malte Nottmeyer

Distributed under the Eclipse Public License 2.0.
