package scalatest.dcbstore.decisionmodel

import dcbstore.*
import dcbstore.decisionmodel.*
import dcbstore.decisionmodel.SingleProjection.{DataToEventType, handler}

import scala.reflect.{ClassTag, classTag}

// The course domain shared by NamedDecisionModelSpec and ComposedDecisionModelSpec: the same events and the same two
// single projections, composed in two different ways.

// Architectural decision for these test specs, to demonstrate the logic:
// DCB "Event Data" shall be represented by AnyRef, e.g. case classes. This settles type DataSuper = AnyRef.
// Per event the corresponding DCB "Event Type" value shall be automatically derived from the "Event Data"'s class name,
// thus their type will be String. This settles type EventType = String
// Explicitly set here these type parameters, rather than spelling out as String and AnyRef below.
// A DcbStore usable to run these specs must support Strings as EventType, and AnyRef as DataSuper.
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
given simpleClassNameAsEventType: [Data <: DataSuper: ClassTag] => DataToEventType[Data, EventType]:
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

// Creates an event whose "Event Type" value is derived from the "Event Data" type.
def event[Data](data: Data, tags: Tags)(using dataToEventType: DataToEventType[Data, EventType])
: Event[EventType, Data] =
  Event(dataToEventType.eventType, data, tags)
