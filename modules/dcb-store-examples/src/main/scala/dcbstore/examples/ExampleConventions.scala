package dcbstore.examples

import dcbstore.decisionmodel.SingleProjection.DataToEventType
import dcbstore.{Event, Tags}

import scala.reflect.{ClassTag, classTag}

// Conventions shared by all examples, which live in sub-packages, e.g. dcbstore.examples.coursesubscriptions.
// As a sub-package does not see the members of this package automatically, each example imports them via
//   import dcbstore.examples.{*, given}

// Architectural decision for the examples:
// DCB "Event Data" is represented by AnyRef, i.e. one case class per domain event. This settles type DataSuper = AnyRef.
// Per event the corresponding DCB "Event Type" value is automatically derived from the "Event Data"'s class name,
// thus its type is String. This settles type EventType = String.
// The examples' main code works against any DcbStore[EventType, DataSuper].
type DataSuper = AnyRef
type EventType = String

// The "Event Type" value of every "Event Data" type is its simple class name, e.g. "CourseDefined".
// This one generic given replaces a DataToEventType per data type. It is limited to DataSuper, so that e.g. an Int does
// not silently get an event type.
given simpleClassNameAsEventType: [Data <: DataSuper : ClassTag] => DataToEventType[Data, EventType]:
  override def eventType: EventType = classTag[Data].runtimeClass.getSimpleName

// Creates an event whose "Event Type" value is derived from the "Event Data" type.
def event[Data <: DataSuper](data: Data, tags: Tags)(using dataToEventType: DataToEventType[Data, EventType])
: Event[EventType, Data] =
  Event(dataToEventType.eventType, data, tags)
