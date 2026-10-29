package scalatest.dcbstore.decisionmodel

import dcbstore.*
import dcbstore.decisionmodel.*
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext.Implicits.global

// Specifies building decision and read models from a named tuple of projections, see NamedDecisionModel.
// Each state keeps its own type and is accessed by name. For comparison, ComposedDecisionModelSpec composes the same
// projections as an Iterable via ComposedProjection, where the states have to be recovered by pattern matching.
// Each implementation of DcbStore runs it via an empty subclass, passing a factory for a fresh, empty store under test.
// A factory rather than a store, as ScalaTest uses one suite instance for all its tests, other than Cucumber, which
// creates a new glue instance per scenario.
abstract class NamedDecisionModelSpec(newDcbStore: () => DcbStore[EventType, DataSuper])
  extends AnyFlatSpec
    with Matchers
    with ScalaFutures
    with IntegrationPatience:

  "A named decision model" should "yield typed states and one append condition" in :
    // Given a DCB store with 2 events
    val store = newDcbStore()
    store.append(List(event(CourseDefined(courseId = "c1", capacity = 10), courseTags("c1")))).futureValue
    store.append(List(event(CourseCapacityChanged(courseId = "c1", newCapacity = 15), courseTags("c1")))).futureValue

    // Check that course c1 exists and has capacity of 15
    val projections = (exists = courseExists("c1"), capacity = courseCapacity("c1"))
    val modelF = store.buildDecisionModel(projections)
    val model = modelF.futureValue
    val exists: Boolean = model.finalState.exists
    val capacity: Int = model.finalState.capacity
    val _ = exists shouldBe true
    val _ = capacity shouldBe 15

    // Verify the append condition's query.
    val _ = model.appendConditionO.map(_.failIfEventsMatch.queryItems.size) shouldBe Some(2)

    // Check the append condition can be fed straight back.
    val _ = store.append(
      List(event(CourseCapacityChanged(courseId = "c1", newCapacity = 20), courseTags("c1"))),
      model.appendConditionO,
    ).futureValue shouldBe ()

    // Reusing the now stale condition must fail. store.AppendConditionFailed is an abstract type here, so only its
    // upper bound RuntimeException can be checked at runtime.
    val _ = store.append(
      List(event(CourseCapacityChanged(courseId = "c1", newCapacity = 25), courseTags("c1"))),
      model.appendConditionO,
    ).failed.futureValue shouldBe a[RuntimeException]

    // Read-model variant.
    val readModelF = store.buildReadModel(projections = (capacity = courseCapacity("c1")))
    readModelF.futureValue.capacity shouldBe 20
