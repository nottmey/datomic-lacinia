# Datomic & Lacinia

A pragmatic [Lacinia](https://github.com/walmartlabs/lacinia) schema generator for your [Datomic](https://www.datomic.com/) schema, which tries to fill the complete gap in between, while adhering to the principles of both worlds.

Essentially this gives you a neat GraphQL API for your Datomic instance, when combined with [Lacinia-Pedestal](https://github.com/walmartlabs/lacinia-pedestal).

[![state](https://img.shields.io/badge/state-draft_(some_todos_left,_not_optimized,_structure_might_change)-red.svg)](https://github.com/nottmey/datomic-lacinia/issues)
[![feedback](https://img.shields.io/badge/feedback-welcome!_(anything_helps)-informational.svg)](https://github.com/nottmey/datomic-lacinia/issues)
[![Tests](https://github.com/nottmey/datomic-lacinia/actions/workflows/tests.yml/badge.svg?branch=main)](https://github.com/nottmey/datomic-lacinia/actions/workflows/tests.yml)

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

## Design Principles

1. Make it run out of the box, on any schema, even with "chaotic" history and rare feature usage. 
2. Make it easy to adhere to the GraphQL specification, so any client-side programmer is keen to use it.
3. Decisions you do while setting up this library, should not cause you headaches in the future. 
4. Unsupported edge-cases of a specific context are reported at configuration time.

## Considerations

### Namespaced Keys

GraphQL does not know about namespaced keys, and it only allows field names in `[_A-Za-z][_0-9A-Za-z]*` form, because it's generators target different language platforms.

Adding the namespace to the key (e.g. mapping `:medium.format/name` to e.g. `medium_format__name`) is not convenient in usage and might cause collisions (you will need to find a bidirectional mapping). Also, `camelCase` is the default naming convention for GraphQL fields, which only gives you one way to differentiate a word break.

Ultimately, in GraphQL the default is to provide context via nesting, not so much via prefixing. That's why I chose to map e.g. `:medium.format/name` to `medium_ { format_ { name } }` instead. 

#### Note on the `_` postfix 

Using nesting alone does not solve the collision problem. For example attributes `:medium/format` and `:medium.format/name` would cause a collision without it, but can happily co-exist with it: `{ medium_ { format format_ { name }}}`

That's why the mapping needs to differentiate namespace nesting and actual fields. I choose post-fixing, because it interferes less with typing the queries, and it doesn't have an overlap with other semantics (`__` prefix is used for introspection, `_` prefix means "private" in some contexts). I choose to apply it to the attribute namespace rather than the attribute name, because namespaces are common to overlap, so it minimizes character overhead. (You have equal or more as many attributes as attribute namespaces.)

As a benefit, you can clearly see when you leave the entity context and may find scalar values. That's why it's a good default, even though it's implemented to prevent an edge case. (Also, a valid decision in the past – e.g. naming a field – should not cause you harm, when doing another valid decision in the future – e.g. naming another field.)

### Special Characters

In GraphQL, field names must adhere to `[_A-Za-z][_0-9A-Za-z]*`, case is relevant, and `camelCase` is expected. Clojure however allows a much larger variety of characters, including "the elephant in the room" `-`. 

So, either the mapping discards every non-valid attribute, or it tries the best effort approach and introduces the possibility of naming collisions. Because this library should allow you to run a valid GraphQL API out of the box, I choose the best effort approach and to deal with the naming collisions in a predictable manner.

Most likely, you are already doing `camelCase`, `snake_case`, or `lisp-case`. Therefore, any `_` and `-` inside a word is dropped and the following character is ensured to be uppercase. Afterwards every special character, including `_`, is dropped. If your schema was doing this to differentiate attributes, you probably wanted to hide or rename such attributes on API level anyway. If your schema is doing `stUdlY_cAps`, it becomes `stUdlYCAps` and renaming on API level is advised!

This will eventually cause collisions. For example, both `:any/hello_world%` and `:any/__helloWorld` will result in field `any { helloWorld }`. The important parts are that this collision will be reported at configuration time and that adding attributes does not overwrite previous ones, such that your API remains stable over time.

I solved this by sorting the input attributes by the transaction id of the `:db/ident` in increasing order (so, old to new). Attributes with the lower transaction id take precedence over the ones with higher transaction id. So, the newer version of a colliding attributes doesn't appear in the API, until a name overwrite is configured on API level.

### IDs

In GraphQL IDs are assumed to be strings, in Datomic they are integers (`long`). So, I implemented parsing accordingly.

### Entity Types

Datomic doesn't know about entity types. There are just entities and relations. This does not conflict with GraphQL. GraphQL just would happily allow us to have more types.

So, I just started with the general case and will consider generating type based versions of the queries, if configured. Maybe reusing meta information from your schema is a way to go. Forcing you to transact special meta attributes to your schema, so this generator can do more magic, does not feel like the right solution though. (Happy to hear your feedback on this!) 

## Examples

Extracted from [test/datomic_lacinia/mbrainz_example_test.clj](test/datomic_lacinia/mbrainz_example_test.clj). Subset of the plain old [Datomic mbrainz example](https://github.com/Datomic/mbrainz-sample).

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
        db_ {
            # all attributes of namespace "db"
            id
        }
        artist_ {
            # all attributes of namespace "artist"
            name
            type {
                # :artist/type is a ref, so we are in type "Entity" again
                db_ {
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
      "db_": {
        "id": "92358976733259"
      },
      "artist_": {
        "name": "Led Zeppelin",
        "type": {
          "db_": {
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
        artist_: {
            name: "John Lennon"
        }
    }) {
        db_ {
            id
        }
        artist_ {
            name
            type {
                db_ {
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
        "db_": {
          "id": "92358976733261"
        },
        "artist_": {
          "name": "John Lennon",
          "type": {
            "db_": {
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

Licensed under the Eclipse Public License, Version 2.0
