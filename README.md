# dcb-store-scala

A small Scala 3 API for **DCB** (Dynamic Consistency Boundaries) event stores, following
<https://dcb.events/>, incl. conformance specification and an example in-memory implementation.

A DCB store keeps events in a single global sequence. Events carry an *event type* and a set of *tags*, and are queried
by both. An *append condition* implements optimistic concurrency: an append
fails if any event matching a given query was written after a given sequence position. That is
enough to build decision models which are consistent per decision without needing aggregates to be the unit of
consistency.

## Goals

* A simple, type-flexible, yet type-safe API to represent the ideas of **DCB** in Scala 3.
* A conformance specification of the API in Gherkin language.
* A projection / decision-model layer on top of the API.
* A simple in-memory implementation of the API, incl. test execution of the conformance specification.
* Local publishing of the libraries is considered good-enough for now.

## Non-Goals

* A fully fledged event store. Instead, using this API adaptors could be written against existing third party DCB event
  stores. One such adaptor exists already as
  [dcb-store-sw-eventstore](https://github.com/carsten-langer/dcb-store-sw-eventstore), which adapts this API towards
  [Sliceworkz Eventstore](https://sliceworkz.github.io/eventstore/), a third-party open source event store implementation in Java.
* Read models, subscriptions or similar. This is outside the core idea of **DCB**, which focuses on decision models.
  If needed, the mechanisms of the chosen underlying DCB event store could be used.
* Publishing the libraries to Maven or similar is currently not planned.

## Artifacts

All artifacts are published under group `de.carsten-langer` (the domain seems useful, but is not officially owned by me):

| Artifact            | Contents                                                                    | Third-party compile deps  |
|---------------------|-----------------------------------------------------------------------------|---------------------------|
| `dcb-store`         | the `DcbStore` API plus the projection / decision-model layer               | **none**                  |
| `dcb-store-testkit` | the shared conformance spec: `DcbStore.feature` + `DcbStoreStepDefinitions` | cucumber-scala, ScalaTest |
| `dcb-store-inmem`   | a simple in-memory `DcbStore`                                               | none                      |

## Usage

There is currently no Maven-hosted artifact, so to use the following `libraryDependencies` in _your_ app
you first need to run `sbt publishLocal` in this repository, see [Building](#building) below.
The version in all snippets below must match `ThisBuild / version` in this repository's `build.sbt`.

```scala
libraryDependencies += "de.carsten-langer" %% "dcb-store" % "0.1.0-SNAPSHOT"
libraryDependencies += "de.carsten-langer" %% "dcb-store-inmem" % "0.1.0-SNAPSHOT"
```

## API surface

- `dcbstore`: `DcbStore` (with its abstract `SequencePosition` and `AppendConditionFailed` types),
  `Event`, `Events`, `SequencedEvent`, `Query`, `QueryItem`, `AppendCondition`, `Tag`, `Tags`
- `dcbstore.decisionmodel`: `Projection`, `SingleProjection` (plus `SingleProjection.handler` and
  the `DataToEventType` type class), `ComposedProjection`, `TupleProjection`, `DecisionModel`, `NamedDecisionModel`,
  and six `extension` methods on `DcbStore` — `buildDecisionModel` and `buildReadModel`, each for
  one projection, an iterable of them, or a named tuple of them

The design intent is that write/decision models are as small as possible — typically one yes/no question
each — and are then composed into a single query against the store. See the Scaladoc on
`DecisionModel`, `NamedDecisionModel`, `ComposedProjection` and `TupleProjection`.

Composing projections as a **named tuple** keeps each projection's state type, so the decision can
be written against named, typed fields:

```scala
store.buildDecisionModel((courseExists = courseExists(id), capacity = courseCapacity(id)))
  .flatMap: (finalState, appendConditionO) =>
    // finalState: (courseExists: Boolean, capacity: Int)
    if finalState.courseExists && finalState.capacity > 0
    then store.append(events, appendConditionO)
    else Future.failed(CourseNotAvailable(id))
```

The store is read once with the merged query of all projections, and the single append condition
covers all of them. Composing an `Iterable` of projections does the same, but returns the states as
an `Iterable` of their common super type.

## Requirements for consumers

- **Scala 3.8 or newer.** The artifacts are built with 3.8.4, and Scala 3 TASTy is only readable by
  the same or a newer compiler. The code itself would compile on 3.7 (named tuples in public
  signatures put the floor there — 3.3 LTS is *not* reachable without an API redesign), so the
  requirement can be lowered later without breaking anyone; raising it would be breaking.
- **`-language:strictEquality` is expected.** The sources are written for it, and the public API
  hands back types that you will usually want `CanEqual` instances for.
- Java 17+ (developed and tested against Temurin 21 and Temurin 27).

## Implementing your own DcbStore, and verifying it

`dcb-store-testkit` is the executable specification. `DcbStore.feature` is over a dozen scenarios covering
reading, appending, tag and event-type filtering, and append conditions with and without an `after`
position. `DcbStoreStepDefinitions` is an abstract class taking the store under test as a
constructor argument:

```scala
// src/test/scala/…
class MyDcbStoreStepDefinitions extends DcbStoreStepDefinitions(MyDcbStore())

@Suite
@IncludeEngines(Array("cucumber"))
@SelectClasspathResource("cucumber/dcbstore/DcbStore.feature")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "not @ignore")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "my.glue.package")
class MyDcbStoreCucumberSpec
```

with

```scala
libraryDependencies += "de.carsten-langer" %% "dcb-store-testkit" % "0.1.0-SNAPSHOT" % Test
```

plus `cucumber-junit-platform-engine`, `junit-platform-suite` and `jupiter-interface` to run it.

Depending on the report plugins you configure (e.g. `html:`), Cucumber (JVM) 8 may fail with
_"Cucumber needs a JSON library to write reports"_. It then needs a **JSON library on the test classpath**:
Jackson 3 (`tools.jackson.core:jackson-databind`) or Jackson 2 (`jackson-databind` + `jackson-datatype-jdk8`).
The testkit deliberately does not export one, so add one yourself unless your build already brings it in
transitively:

```scala
libraryDependencies += "tools.jackson.core" % "jackson-databind" % "3.2.4" % Test
```

Note the `@SelectClasspathResource`, **not** `@SelectPackages`: the feature file arrives inside the
testkit jar, and Cucumber's package scanning for `.feature` resources inside jars is unreliable —
it fails by silently running zero scenarios rather than by erroring.

Because `DcbStoreStepDefinitions` needs the store's dependent `SequencePosition` type to be stable
at construction time, it is an abstract *class* rather than a trait. The event-type and data
types are fixed to `String`, only because this is what the feature file uses.

## Building

```
sbt compile
sbt test           # runs the conformance suite against dcb-store-inmem
sbt testInMem      # the same, by name
sbt publishLocal
```

Everything is in-memory: no Docker, no database, no external services.

`-Werror` is on, so any warning fails the build — there is no separate lint step. Test reports are
written to `target/dcbstore-*-test-report.html` (the build root, since the tests are not forked and
the Cucumber plugin paths are relative).

Publishing is **local only** for now — there is no remote repository, no signing and no release
workflow. Artifacts land in the Ivy local repository; the `[info] published … to …` lines in the
`publishLocal` output tell you exactly where. Doc jars are deliberately not published, because
Scaladoc inherits `-Werror` and the sources contain unresolved `[[links]]`.

## History

This source code is extracted from a (currently non-public) Play app supporting the (public)
[HealthCareDay](https://github.com/carsten-langer/HealthCareDay), with the history of the extracted files preserved.

## License

Copyright 2026 Carsten Langer.

Licensed under the [Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE) for third-party
attributions.
