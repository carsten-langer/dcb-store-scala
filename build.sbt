// This is the single build.sbt file for the whole project.
// It follows the sbt best practice to have a single build.sbt file and define multiple modules in it, see
// https://www.scala-sbt.org/1.x/docs/Multi-Project.html#Appendix%3A+Subproject+build+definition+files

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / organization := "de.carsten-langer"

ThisBuild / version := "0.1.0-SNAPSHOT"

// Written into the published POM / ivy.xml. See the LICENSE and NOTICE files.
ThisBuild / licenses := Seq(License.Apache2)

// All versions: https://www.scala-lang.org/download/all.html
// Scala 3.7 is the hard minimum for this code base, i.e. 3.3 LTS is NOT reachable without an API
// redesign:
// - named tuples (stable in 3.7) appear in the PUBLIC signatures of
//   dcbstore.decisionmodel.DecisionModel, dcbstore.decisionmodel.NamedDecisionModel and
//   dcbstore.decisionmodel.SingleProjection,
//   including a function parameter type,
// - "-Wall" needs 3.6+,
// - Tags.scala / Events.scala use "final type" aliases (not verified against 3.7).
// Kept currently on 3.8.4 to match known consumers (e.g. dcb-store-sw-eventstore (different repo)) exactly.
// Note the TASTy forward-compatibility rule: a library built with 3.8.4 can only be read by
// Scala 3.8+. Lowering this later is non-breaking for consumers; raising it is breaking.
ThisBuild / scalaVersion := "3.8.4"


////////////////////////////////////////
// Settings shared by all published modules
////////////////////////////////////////
lazy val commonSettings = Def.settings(
  // Scaladoc is invoked with the same scalacOptions as compile, i.e. including -Wall and -Werror.
  // These sources use [[...]] links heavily, so a single dangling link would fail publishLocal.
  // Until the Scaladoc is warning-clean, do not publish a doc jar.
  // Alternative: Compile / doc / scalacOptions -= "-Werror".
  Compile / packageDoc / publishArtifact := false,
  // Ship the Apache 2.0 LICENSE and the NOTICE in every published jar, as the license asks for.
  Compile / packageBin / mappings ++= licenseMappings.value,
  Compile / packageSrc / mappings ++= licenseMappings.value,
)
lazy val licenseMappings = Def.setting {
  val root = (LocalRootProject / baseDirectory).value
  Seq(root / "LICENSE" -> "META-INF/LICENSE", root / "NOTICE" -> "META-INF/NOTICE")
}


////////////////////////////////////////
// A generic DCB store API (Dynamic Context Boundaries)
// See https://dcb.events/
////////////////////////////////////////
lazy val dcbStore = (project in file("modules/dcb-store"))
  .settings(commonSettings)
  .settings(
    name := "dcb-store",
    // Deliberately no libraryDependencies at all, not even test-scoped ones. The shared Cucumber
    // conformance spec lives in dcbStoreTestkit, so this module has no tests of its own and no
    // third-party compile dependencies whatsoever.
  )


////////////////////////////////////////
// The shared DcbStore conformance spec (a TCK)
////////////////////////////////////////
// Holds DcbStoreStepDefinitions and DcbStore.feature as MAIN sources/resources rather than test
// ones, so that any implementation of DcbStore - in this repo or in another repo - can run the
// same specification against itself. See the Readme for how to use it.
lazy val dcbStoreTestkit = (project in file("modules/dcb-store-testkit"))
  // dcbStoreTestkit % Compile needs dcbStore % Compile: DcbStoreStepDefinitions takes a DcbStore.
  .dependsOn(dcbStore)
  .settings(commonSettings)
  .settings(
    name := "dcb-store-testkit",
    libraryDependencies ++= Seq(
      // Note the Compile (default) scope rather than Test: these two are part of the public API of the
      // dcbStoreTestkit module, as DcbStoreStepDefinitions extends ScalaDsl/EN (cucumber-scala)
      // and ScalaFutures/IntegrationPatience/Matchers/TryValues (scalatest).
      // https://github.com/cucumber/cucumber-jvm-scala/releases
      "io.cucumber" %% "cucumber-scala" % "9.2.1",
      // https://www.scalatest.org/release_notes
      "org.scalatest" %% "scalatest" % "3.2.20",
    ),
  )


////////////////////////////////////////
// A simple in-memory implementation of the generic DCB store API
////////////////////////////////////////
// This is a complicated setup.
// The issue is that key values like "JupiterKeys.junitPlatformVersion.value" cannot be used in normal Scala
// val assignments like "val someVal = ...", but only in sbt key assignments like "someKey := ...".
// To mitigate, a whole sequence of settings is defined here which can then be added to the projects which need it,
// similar to what is described in the docs for Task keys, see
// https://www.scala-sbt.org/1.x/docs/Appending-Values.html
// Note that cucumber-scala is not listed here: it arrives transitively from dcbStoreTestkit.
lazy val cucumberSbtIntegrationSettingsForTest = Def.settings(
  libraryDependencies ++= Seq(
    // https://github.com/cucumber/cucumber-jvm/tree/main/cucumber-junit-platform-engine
    "io.cucumber" % "cucumber-junit-platform-engine" % "8.0.4" % Test,
    // Cucumber (JVM) 8 needs a JSON library on the classpath to write its reports, and accepts either
    // Jackson 2 (databind + datatype-jdk8) or Jackson 3. Test-scoped only, so dcb-store stays
    // free of third-party compile dependencies.
    // https://github.com/FasterXML/jackson-databind/tree/3.x
    "tools.jackson.core" % "jackson-databind" % "3.2.4" % Test,
    // https://github.com/junit-team/junit-framework/tree/main/junit-platform-suite
    "org.junit.platform" % "junit-platform-suite" % JupiterKeys.junitPlatformVersion.value % Test,
    // https://github.com/sbt/sbt-jupiter-interface
    "com.github.sbt.junit" % "jupiter-interface" % JupiterKeys.jupiterVersion.value % Test,
  ),
)
lazy val dcbStoreInMem = (project in file("modules/dcb-store-inmem"))
  // https://www.scala-sbt.org/1.x/docs/Multi-Project.html#Per-configuration+classpath+dependencies
  // dcbStoreInMem % Compile needs dcbStore % Compile, as it implements DcbStore.
  // dcbStoreInMem % Test needs dcbStoreTestkit, as it implements DcbStoreStepDefinitions and runs
  // DcbStore.feature. cucumber-scala and scalatest arrive transitively from the testkit.
  .dependsOn(dcbStore, dcbStoreTestkit % Test)
  .settings(commonSettings)
  .settings(
    name := "dcb-store-inmem",
    cucumberSbtIntegrationSettingsForTest,
  )


////////////////////////////////////////
// The root project: pure aggregator, publishes nothing
////////////////////////////////////////
lazy val root = (project in file("."))
  // https://www.scala-sbt.org/1.x/docs/Multi-Project.html#Aggregation
  .aggregate(dcbStore, dcbStoreTestkit, dcbStoreInMem)
  .settings(
    name := "dcb-store-scala",
    // In sbt 1.x the publish task reads "publish / skip" for both publish and publishLocal, so
    // this single setting keeps the empty aggregator out of both.
    publish / skip := true,
  )


////////////////////////////////////////
// Scala options
////////////////////////////////////////
ThisBuild / scalacOptions ++= Seq(
  "-deprecation", // Emit warning and location for usages of deprecated APIs.
  "-explain", // Explain errors in more detail.
  "-explain-cyclic", // Explain cyclic reference errors in more detail.
  "-feature", // Emit warning and location for usages of features that should be imported explicitly.
  // Load-bearing: SingleProjection.scala has "given CanEqual[EventType, EventType] = CanEqual.derived".
  "-language:strictEquality", // Enable strict equality (disable canEqualAny).
  "-new-syntax", // Require then and do in control expressions.
  "-source:future",
  "-unchecked", // Enable additional warnings where generated code depends on assumptions.
  "-Wall", // Enable all warnings.
  // All -Wconf options are stacked to the left. Later options get precedence over earlier ones, if in conflict.
  "-Wconf:any:verbose", // Helpful for debugging. As it is most general, it is the first option.
  "-Werror", // Fail the compilation if there are any warnings. There is no separate lint step.
)


////////////////////////////////////////
// Alias
////////////////////////////////////////
addCommandAlias(
  name = "testInMem",
  value = "dcbStoreInMem / Test / testOnly" +
    " cucumber.dcbstore.inmemdcbstore.InMemDcbStoreCucumberSpec" +
    " scalatest.dcbstore.decisionmodel.inmemdcbstore.InMemNamedDecisionModelSpec" +
    " scalatest.dcbstore.decisionmodel.inmemdcbstore.InMemComposedDecisionModelSpec",
)
