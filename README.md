# Datomic & Lacinia

A pragmatic [Lacinia](https://github.com/walmartlabs/lacinia) schema generator for your [Datomic](https://www.datomic.com/) schema, which tries to fill the complete gap in between, while adhering to the principles of both worlds.

Essentially this gives you a neat GraphQL API for your Datomic instance, when combined with [Lacinia-Pedestal](https://github.com/walmartlabs/lacinia-pedestal).

[![state](https://img.shields.io/badge/state-draft_(some_todos_left,_not_optimized)-red.svg)](https://github.com/nottmey/datomic-lacinia/issues)
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

## License

Copyright © 2022 Malte Nottmeyer

Distributed under the Eclipse Public License 2.0.
