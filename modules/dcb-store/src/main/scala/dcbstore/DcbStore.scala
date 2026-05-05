// A simple DCB store API following https://dcb.events.

package dcbstore

import scala.concurrent.Future

/**
 * A simple DCB (Dynamic Context Boundary) store API following https://dcb.events.
 *
 * @tparam EventType The Scala type of DCB's "Event Data", see [[Event.eventType]]. Having this type parameter allows
 *                   for some flexibility in the implementation of the store.
 *
 *                   In DCB, the "Event Type" represents the type of the domain event used to filter [[Events]] in the
 *                   [[Query]]. It will be one constant value per domain event type, and it serves for fast filtering
 *                   of events in the underlying storage layer, e.g. in a database.
 *
 *                   In contrast to this, the DCB "Event Data" is opaque to the store, see [[Event.data]]. There is a
 *                   mapping from each "Event Type" value, representing a domain event type, to the type of its
 *                   "Event Data", representing the corresponding domain data. This way, when a query filters on events
 *                   of a certain "Event Type", the resulting events are guaranteed to have the corresponding type of
 *                   "Event Data". In DCB, the reverse mapping does not need to be defined.
 *
 *                   However, in a strongly typed language like Scala, each domain event type is typically modeled with
 *                   its own language type; in Scala e.g. via a case class or a named enum value. When constructing an
 *                   [[Event]], the domain event type, e.g. case class, is directly used as [[Event.data]]. Therefore,
 *                   the language type of the "Event Data" in an individual event corresponds to its "Event Type" value,
 *                   see [[Event.eventType]] and [[Event.data]]. Using this approach, even a bidirectional mapping
 *                   exists between an "Event Type" value and its "Event Data" type.
 *
 *                   In practical terms, a class implementing this [[DcbStore]] must choose a Scala type [[EventType]]
 *                   for the possible values of "Event Type" such that it can serialize them to and deserialize them
 *                   from a stored event. A typical implementation may use the type [[String]]. But it may choose a more
 *                   specific type, like [[Int]] or a String with a limited length for performance reasons and to avoid
 *                   the risk of unbound Strings. Using the type [[Class]] as [[EventType]], so that the Scala class
 *                   of an "Event Data" value [[Event.data]] may be used, could be challenging, as during
 *                   deserialization of an event the Class would need to be reconstructed.
 *
 *                   The Scala type [[EventType]] which an implementation of [[DcbStore]] chooses affects users of this
 *                   implementation. Users have to take care of the mapping of their domain event types with their
 *                   "Event Data" values in [[Event.data]] and a corresponding "Event Type" value in
 *                   [[Event.eventType]], which effectively must be of the type [[EventType]] chosen for this
 *                   implementation of [[DcbStore]].
 *
 *                   There is therefore a coupling between an implementation of this [[DcbStore]] and the Scala type of
 *                   the "Event Type" values a user can use in [[Event.eventType]]. However, this coupling is not as
 *                   fixed as it would be if upfront it was decided to always use e.g. use a [[String]] for the
 *                   "Event Type" values. With this type parameter [[EventType]], there remains some flexibility.
 *
 *                   It can also be seen the other way round. A class using an [[DcbStore]] may want to use a certain
 *                   way to map from its domain "Event Data" to corresponding "Event Type" values. For example, it may
 *                   want to use the class name of the domain event as the "Event Type", thus [[EventType]] should be a
 *                   String. Then such class needs an implementation of [[DcbStore]] which uses the type [[String]] for
 *                   the [[EventType]].
 *
 * @tparam DataSuper The Scala super-type of all the types used in the individual event's "Event Data" [[Event.data]]
 *                   values. The "Event Data" itself is opaque to the event store and thus [[DataSuper]] could thus be
 *                   [[Any]]. However, an implementing class may choose to specify this type more in detail, e.g. to
 *                   ensure proper serialization/deserialization, thereby limiting how to use this event store.
 */
trait DcbStore[EventType, DataSuper]:

  /** The type of the sequence position of an event. Opaque to the DcbStore API and depends on the implementation. */
  type SequencePosition

  /** The type of the RuntimeException inside the failed Future when an append-condition check fails. */
  type AppendConditionFailed <: RuntimeException

  def append[Data <: DataSuper](
                                 events: Events[EventType, Data],
                                 appendConditionO: Option[AppendCondition[EventType, SequencePosition]] = None,
                               ): Future[Unit]

  def read(query: Query[EventType]): Future[IterableOnce[SequencedEvent[EventType, DataSuper, SequencePosition]]]
