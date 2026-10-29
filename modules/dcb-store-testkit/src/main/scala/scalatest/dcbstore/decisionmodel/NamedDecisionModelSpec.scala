package scalatest.dcbstore.decisionmodel

import dcbstore.*
import dcbstore.decisionmodel.*
import dcbstore.decisionmodel.SingleProjection.{DataToEventType, handler}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.{ClassTag, classTag}

// Architectural decision for this test spec, to demonstrate the logic:
// DCB "Event Data" shall be represented by AnyRef, e.g. case classes. This settles type DataSuper = AnyRef.
// Per event the corresponding DCB "Event Type" value shall be automatically derived from the "Event Data"'s class name,
// thus their type will be String. This settles type EventType = String
// Explicitly set here these type parameters, rather than spelling out as String and AnyRef below.
// A DcbStore usable to run this spec must support Strings as EventType, and AnyRef as DataSuper.
// If you have other architectural wishes, adopt accordingly.
type DataSuper = AnyRef
type EventType = String

// The "Event type" value of every "Event Data" type is its simple class name, e.g. "CourseDefined".
// This one generic given replaces a DataToEventType per data type. It is limited to DataSuper, so that e.g. an Int does
// not silently get an event type.
// If you rather fix the "Event Type" value per "Event Data" type, so that a renaming of the class does not change it,
// define it explicitly per event data type, e.g.
//   given DataToEventType[CourseDefined, EventType]:
//     override def eventType: EventType = "CourseDefined"
given simpleClassNameAsEventType: [Data <: DataSuper : ClassTag] => DataToEventType[Data, EventType]:
  override def eventType: EventType = classTag[Data].runtimeClass.getSimpleName

final case class CourseDefined(courseId: String, capacity: Int)

final case class CourseCapacityChanged(courseId: String, newCapacity: Int)

def courseTags(courseId: String): Tags = Tags("course", courseId)

def courseExists(courseId: String) = SingleProjection(
  internalState = false,
  handlers = Map(
    handler(
      handlerF = (_: Boolean, _: CourseDefined) => true,
      tags = courseTags(courseId),
    ),
  ),
)

def courseCapacity(courseId: String) = SingleProjection(
  internalState = 0,
  handlers = Map(
    handler(
      handlerF = (_: Int, e: CourseDefined) => e.capacity,
      tags = courseTags(courseId),
    ),
    handler(
      handlerF = (_: Int, e: CourseCapacityChanged) => e.newCapacity,
      tags = courseTags(courseId),
    ),
  ),
)

// Specifies building decision and read models from a named tuple of projections, see NamedDecisionModel.
// Each implementation of DcbStore runs it via an empty subclass, passing a factory for a fresh, empty store under test.
// A factory rather than a store, as ScalaTest uses one suite instance for all its tests, other than Cucumber, which
// creates a new glue instance per scenario.
abstract class NamedDecisionModelSpec(newDcbStore: () => DcbStore[EventType, DataSuper])
  extends AnyFlatSpec
    with Matchers
    with ScalaFutures
    with IntegrationPatience:

  // helper function
  private def event[Data](data: Data, tags: Tags)(using dataToEventType: DataToEventType[Data, EventType])
  : Event[EventType, Data] =
    Event(dataToEventType.eventType, data, tags)

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
