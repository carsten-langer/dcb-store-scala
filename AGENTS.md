# AGENTS.md

This file provides guidance to AI coding agents (Claude Code, Codex, Copilot, Cursor, ...) when working
with code in this repository. `CLAUDE.md` imports it, so keep this file as the single source.

## What this is

A standalone Scala 3 library implementing a **DCB** (Dynamic Consistency Boundaries) event-store API,
see <https://dcb.events/>, plus a shared conformance spec, one in-memory implementation, and an
(unpublished) examples module following <https://dcb.events/examples/>.
Extracted from a (non-public) Play application with history preserved; that (non-public) Play application is now a
consumer.

See `README.md` for the consumer-facing view (artifacts, API surface, how to implement your own
store against the testkit).

## Commands

```
sbt compile
sbt test                # all suites: the conformance suites against dcb-store-inmem (15 scenarios, 75 steps
                        # + 2 ScalaTest tests) and the examples
sbt testInMem           # alias for the three conformance suites, by name
sbt testExamples        # alias for the examples' tests
sbt publishLocal
sbt dependencyBrowseGraph
```

Single suite: `sbt "dcbStoreInMem / Test / testOnly cucumber.dcbstore.inmemdcbstore.InMemDcbStoreCucumberSpec"`
or `sbt "dcbStoreInMem / Test / testOnly scalatest.dcbstore.decisionmodel.inmemdcbstore.InMemNamedDecisionModelSpec"`
(likewise `InMemComposedDecisionModelSpec`).

`dcbStore` and `dcbStoreTestkit` have **no tests of their own** — only `dcbStoreInMem` runs suites,
so `sbt "dcbStore / test"` correctly reports "no tests". Use `sbt "dcbStore / Test / compile"` if you
want to check those modules compile under test config.

No Docker, no database, no external services: everything is in-memory.

Test reports land at `<repo-root>/target/dcbstore-*-test-report.html` — the build root, not
`modules/*/target/`, because the tests are unforked and the Cucumber plugin paths are relative.

**There is no `sbt` on PATH on the original development machine**; sbt is driven from IntelliJ. If
you need it from a shell, either install a launcher or invoke IntelliJ's bundled `sbt-launch.jar`
with `-Dsbt.ivy.home` passed explicitly (`java -jar sbt-launch.jar` does not read `SBT_OPTS`).
If IntelliJ's sbt shell is open, a second sbt fails with `ServerAlreadyBootingException` (it cannot
take the server pipe lock); add `-Dsbt.server.forcestart=true -Dsbt.server.autostart=false`. Pass
commands as plain arguments (e.g. `... sbt-launch.jar test < /dev/null`): `-batch` is a script-launcher
flag that the raw jar rejects as "Not a valid command".

## Module architecture

```
dcb-store            the DcbStore API + projections / decision models; ZERO third-party compile deps
dcb-store-testkit    the shared conformance spec, as MAIN sources: DcbStoreStepDefinitions
                     + resources/cucumber/dcbstore/DcbStore.feature, and the ScalaTest
                     NamedDecisionModelSpec + ComposedDecisionModelSpec.  dependsOn(dcbStore)
dcb-store-inmem      an in-memory DcbStore.  dependsOn(dcbStore, dcbStoreTestkit % Test)
dcb-store-examples   the dcb.events examples, NOT published (publish / skip := true).
                     dependsOn(dcbStore, dcbStoreInMem % Test)
root (.)             pure aggregator, publish / skip := true
```

The sliceworkz-eventstore adapter is deliberately **not** here — it lives in the separate
`dcb-store-sw-eventstore` repo, because it links an LGPL-3.0 library that transitively brings
Jackson and Micrometer. Keeping that out is the whole point of the split, and it is what lets
`dcb-store` be licensed under Apache 2.0 with no copyleft interaction. Do not add third-party compile
dependencies to `dcb-store`; that invariant is checkable with
`sbt "show dcbStore / Compile / libraryDependencies"` (expect only `scala3-library`).

## Testing architecture

The conformance spec is a **published artifact**, not test-scoped code. That is the one structural
thing to understand here:

- `DcbStore.feature` (15 scenarios) is the executable specification of the API. It lives in
  `modules/dcb-store-testkit/src/main/resources/`, i.e. it ships inside the testkit jar.
- `DcbStoreStepDefinitions` is an abstract **class** — not a trait — taking the store under test as a
  constructor argument. It has to be a class because several of its members depend on the store's
  dependent type `dcbStore.SequencePosition`, which must be stable at construction time. As a
  consequence, `EventType` and `DataSuper` are fixed to `String` as top-level aliases in the same
  file (both values come from the feature file as text).
- cucumber-scala and ScalaTest are therefore **Compile**-scoped deps of the testkit, since
  `DcbStoreStepDefinitions` extends `ScalaDsl`/`EN` and `ScalaFutures`/`IntegrationPatience`/`Matchers`/`TryValues`. Do not
  "fix" them to `% Test`; downstream consumers need them to compile their own subclass.
- Each implementation supplies a concrete subclass plus an empty runner class annotated
  `@Suite @IncludeEngines("cucumber") @SelectClasspathResource("cucumber/dcbstore/DcbStore.feature")`
  with `GLUE_PROPERTY_NAME` pointing at its own package.
- Cucumber (JVM) 8 needs a **JSON library on the test classpath** for its serialising report plugins
  (this repo's runner configures `html:`), otherwise the run fails with
  "Cucumber needs a JSON library to write reports". It accepts Jackson 3
  (`tools.jackson.core:jackson-databind`) or Jackson 2 (`jackson-databind` + `jackson-datatype-jdk8`).
  This repo adds Jackson 3 `% Test` in `cucumberSbtIntegrationSettingsForTest`; the testkit
  deliberately does not export one, so each implementation must supply its own (a downstream
  repo may already get Jackson 2 transitively, as `dcb-store-sw-eventstore` likely does).
- `Given("an empty DCB store")` is deliberately a no-op: freshness relies on Cucumber constructing a
  new glue instance per scenario, so it is the *subclass* constructor that creates a fresh store.
- Scenarios tagged `@ignore` are filtered out by the runners.
- `scalatest.dcbstore.decisionmodel.NamedDecisionModelSpec` and `ComposedDecisionModelSpec` are plain
  ScalaTest specs covering the decision-model layer against a real store. They compose the same two
  projections from `CourseFixtures.scala`, once as a named tuple (typed states by name) and once as an
  `Iterable` via `ComposedProjection` (a union-typed state, recovered by pattern matching), so they
  read as a side-by-side comparison; keep them parallel when changing either. Each is an abstract class
  taking a **factory** `() => DcbStore[EventType, DataSuper]` rather than a store, because ScalaTest
  shares one suite instance across all its tests, unlike Cucumber's per-scenario glue. Its
  `EventType`/`DataSuper` aliases are `String`/`AnyRef` (case classes as data), not the Cucumber
  spec's `String`/`String`. Each implementation runs it via an empty subclass in its test sources;
  sbt discovers that like any ScalaTest suite, no runner annotations needed.

**Examples** (`modules/dcb-store-examples`, see its `README.md` for the list and status): one
package per example, `dcbstore.examples.<example>` in main and `scalatest.dcbstore.examples.<example>`
in test. Main code is written against `DcbStore[EventType, DataSuper]` only and must not use
`InMemDcbStore`, which is why the in-memory store is only a Test dependency. Shared conventions
(`EventType = String`, `DataSuper = AnyRef`, the class-name-derived event type, the `event` helper,
`Rejected`, `appendIfAccepted`) are top-level in `ExampleConventions.scala`; sub-packages need
`import dcbstore.examples.{*, given}`. Specs extend `ExampleSpec` (test sources), which provides `scenario`
and the `CanEqual` for events; `ConcurrentWriterStore` simulates a concurrent decision.
Under `strictEquality`, event case classes compared in tests need `derives CanEqual`, and comparing
`Tags` needs a `CanEqual[Tag, Tag]`, as `dcb-store` does not provide one; likewise comparing `Event`s, where
the read ones have data type `DataSuper` and the expected ones their case class (see `ExampleSpec`).
ScalaTest's `shouldBe` is subject to `strictEquality` too. When implementing an
example, set its status in the module README.

**Use `@SelectClasspathResource`, never `@SelectPackages`.** Package selection makes JUnit scan the
classpath for `cucumber/dcbstore/*.feature`, which is unreliable when the feature is inside a jar —
and it fails by silently running **zero scenarios** while still reporting success. Since the
downstream `dcb-store-sw-eventstore` repo consumes the feature from the jar, both repos use the
resource form so the behaviour is identical. If more feature files are ever added, add more
annotations rather than reverting to package selection.

To add an implementation: new step-definitions subclass, new `*CucumberSpec` runner, new
`NamedDecisionModelSpec` and `ComposedDecisionModelSpec` subclasses,
`cucumberSbtIntegrationSettingsForTest` on the module, a `lazy val` plus root `.aggregate`, and an
entry in the `testInMem` alias.

## Constraints worth knowing before editing

- **Scala 3.8.4, and the TASTy rule.** Consumers must be on Scala 3.8+. The hard floor for this code
  is **3.7**, not 3.3 LTS: named tuples appear in the *public* signatures of `DecisionModel.scala`, `package.scala`,
  `NamedDecisionModel.scala` and `SingleProjection.scala` (including a function parameter type), `-Wall` needs 3.6+, and
  `Tags.scala`/`Events.scala` use `final type` aliases that were never checked against 3.7. Lowering
  the version later is non-breaking for consumers; raising it is breaking. Keep this build in lockstep
  with the consuming repos.
- **Java 17+** (Cucumber 8 and Jackson 3 require it). Developed on Temurin 27 and tested with Temurin 21.
- **`-Werror` is the lint step.** Any warning fails the build; there is no separate linter.
- **Scaladoc inherits `-Werror`**, and the sources contain unresolved `[[links]]`, so
  `Compile / packageDoc / publishArtifact := false` in `commonSettings`. Removing that will break
  `publishLocal`. To publish docs, first do `Compile / doc / scalacOptions -= "-Werror"` and fix the
  links.
- **`-source:future`, not `-rewrite -source:future-migration`.** The originating app build used
  `-rewrite` only to fix up Play-generated code, and it mutates source files in place. Do not
  reintroduce it here.
- **`-language:strictEquality` is on and load-bearing** — e.g. `SingleProjection.scala` needs an
  explicit `given CanEqual[EventType, EventType] = CanEqual.derived`.
- **`NamedDecisionModel` is cast-free by construction.** `TupleProjection.Builder` instances are
  derived along the tuple structure (`EmptyTuple` / `head *: tail`), which also computes the tuple of
  state types. Keep it that way rather than going through `Iterable`/`productIterator`, which loses
  the types and needs `asInstanceOf` to get them back. `Tuple`'s inline methods (e.g. `toList`) on an
  abstract tuple type crashed the 3.8.4 inliner; plain `*:` pattern matching works.
- **All `extension`s on `DcbStore` live in `decisionmodel/package.scala`**, because Scala 3 only
  allows overloads of top-level definitions within one file.
- **Named tuples interact badly with runtime type checks.** `SingleProjection.handler` requires a
  `Typeable[Data]` for exactly this reason: the projection stores handlers with data type `Any` and
  has to test the real type at runtime. Consumers composing projections generally need each
  projection to produce a distinct nominal type so that pattern matching on a composed state works
  despite erasure.

## Publishing

Licensed under Apache 2.0: `LICENSE` holds the verbatim license text, `NOTICE` the copyright line
and third-party attributions, and `ThisBuild / licenses` puts it into the published POM. `commonSettings` copies both files into
`META-INF/` of every published binary and sources jar. Keep
`NOTICE` up to date when adding or removing dependencies.

Local only for now: no Sonatype, no PGP, no CI. `sbt publishLocal` writes to
the Ivy local repository — trust the `[info] published … to …` log lines for the exact path rather
than assuming `~/.ivy2`, since `sbt.ivy.home` may be overridden (it is on the original machine).

If a downstream repo does not see a change, suspect stale `-SNAPSHOT` resolution before anything
else: bump `version` to `0.1.0-local.N` and the consumer's version val to match, rather than
fighting the caches.
