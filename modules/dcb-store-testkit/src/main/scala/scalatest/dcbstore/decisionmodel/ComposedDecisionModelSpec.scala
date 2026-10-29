package scalatest.dcbstore.decisionmodel

import dcbstore.*
import dcbstore.decisionmodel.*
import dcbstore.decisionmodel.SingleProjection.handler
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Random

// Specifies building decision and read models from an Iterable of projections, see ComposedProjection and
// DecisionModel. The final state is an Iterable of the common super type of all states.
// This is ideal if all states have the same type, see first test below.
// If the common super type instead is a union type, the individual states have to be recovered by pattern matching.
// See the second test below, where the union type is Boolean | Int. For this scenario, ComposedProjection is not ideal.
// For comparison, NamedDecisionModelSpec composes the same projections as a named tuple, where each state keeps its own
// type and is accessed by name.
// Each implementation of DcbStore runs it via an empty subclass, passing a factory for a fresh, empty store under test.
// A factory rather than a store, as ScalaTest uses one suite instance for all its tests, other than Cucumber, which
// creates a new glue instance per scenario.
abstract class ComposedDecisionModelSpec(newDcbStore: () => DcbStore[EventType, DataSuper])
  extends AnyFlatSpec
    with Matchers
    with ScalaFutures
    with IntegrationPatience:

  "A composed decision model" should "support an upfront unknown number of individual decision models" in :
    // Given a DCB store with 1 created course
    val store = newDcbStore()
    store.append(List(event(CourseDefined(courseId = "c0", capacity = 0), courseTags("c0")))).futureValue

    // Given a number of new courses and ids to be created.
    val noNewCourses = Random.between(10, 100)
    val newCourseIds = Range.inclusive(1, noNewCourses).map(i => s"c$i").toList

    // 1) Check if any of the to-be-added courses already exist.
    // Design test such that no to-be-added course already exists.
    val newCourseIds1 = newCourseIds

    // Build list of projections.
    val projections1: List[Projection[Boolean, EventType]] = newCourseIds1.map(courseExists)
    // Use list to build the decision model via composed projection.
    val modelF1 = store.buildDecisionModel(projections1)
    val model1 = modelF1.futureValue
    // As all individual projections share the same type, here Boolean, the final state is just an iterable of that type
    val finalState1: Iterable[Boolean] = model1.finalState
    val _ = finalState1.exists(_ == true) shouldBe false

    // 2) Check if any of the to-be-added courses already exist, and if yet, which one.
    // Design test such that one of the to-be-added course already exists.
    val newCourseIds2 = newCourseIds.appended("c0")

    def namedCourseExists(courseId: String) = SingleProjection(
      internalState = (courseId, false),
      handlers = Map(
        handler(
          handlerF = (_: (String, Boolean), _: CourseDefined) => (courseId, true),
          tags = courseTags(courseId),
        ),
      ),
    )

    val projections2: List[Projection[(String, Boolean), EventType]] = newCourseIds2.map(namedCourseExists)
    val modelF2 = store.buildDecisionModel(projections2)
    val model2 = modelF2.futureValue
    // As all individual projections share the same type, here (String, Boolean), the final state is just an iterable of that type
    val finalState2: Iterable[(String, Boolean)] = model2.finalState
    val alreadyExistingCourses = finalState2.collect:
      case (courseId, true) => courseId
    alreadyExistingCourses should contain theSameElementsAs List("c0")


  // If combining several projections with different types, here Boolean and Int, a composed decision model can still be
  // used, but requires a bit more effort. For an upfront known number of projections to combine, like in this case,
  // the NamedDecisionModel may be a better choice, compare NamedDecisionModelSpec.
  it should "yield the states as an Iterable of a union type and one append condition" in :
    // Given a DCB store with 2 events
    val store = newDcbStore()
    store.append(List(event(CourseDefined(courseId = "c1", capacity = 10), courseTags("c1")))).futureValue
    store.append(List(event(CourseCapacityChanged(courseId = "c1", newCapacity = 15), courseTags("c1")))).futureValue

    // Check that course c1 exists and has capacity of 15
    // The projections have the state types Boolean and Int. As Projection is covariant in its state type, both are
    // Projections of the union type Boolean | Int. The explicit type annotation states this union deliberately: without
    // it, the compiler still infers Boolean | Int, but warns that an inferred union type may be a programming error.
    val projections: List[Projection[Boolean | Int, EventType]] = List(courseExists("c1"), courseCapacity("c1"))
    val modelF = store.buildDecisionModel(projections)
    val model = modelF.futureValue
    val finalState: Iterable[Boolean | Int] = model.finalState
    // The states are in the order of the projections. Pattern matching on both the position and the type recovers the
    // individual states with their own types. A type pattern can only tell states apart by their runtime class: had both
    // projections the state type Int, only the position would distinguish them, and a generic state type like
    // List[Int] could not be checked at all due to erasure. Then give each projection a distinct nominal state type,
    // e.g. a case class wrapping the value.
    val (exists, capacity) = finalState.toList match
      case List(exists: Boolean, capacity: Int) => (exists, capacity)
      case other => fail(s"Unexpected final state: ${other.mkString("(", ", ", ")")}")
    val _ = exists shouldBe true
    val _ = capacity shouldBe 15

    // Alternative pattern matching approach, not taking position into account. Requires each state differs in type.
    val existsO = finalState.collectFirst:
      case exists: Boolean => exists
    val capacityO = finalState.collectFirst:
      case capacity: Int => capacity
    val _ = existsO shouldBe Some(true)
    val _ = capacityO shouldBe Some(15)

    // Verify the append condition's query.
    val _ = model.appendConditionO.map(_.failIfEventsMatch.queryItems.size) shouldBe Some(2)

    // Check the append condition can be fed straight back.
    val _ = store.append(
      List(event(CourseCapacityChanged(courseId = "c1", newCapacity = 20), courseTags("c1"))),
      model.appendConditionO,
    ).futureValue shouldBe()

    // Reusing the now stale condition must fail. store.AppendConditionFailed is an abstract type here, so only its
    // upper bound RuntimeException can be checked at runtime.
    val _ = store.append(
      List(event(CourseCapacityChanged(courseId = "c1", newCapacity = 25), courseTags("c1"))),
      model.appendConditionO,
    ).failed.futureValue shouldBe a[RuntimeException]

    // Read-model variant. With a single projection, the state type is simply Int, and no pattern matching is needed.
    val readModelF: Future[Iterable[Int]] = store.buildReadModel(projections = List(courseCapacity("c1")))
    readModelF.futureValue.toList shouldBe List(20)
