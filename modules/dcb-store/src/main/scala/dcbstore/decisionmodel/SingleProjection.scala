package dcbstore.decisionmodel

import dcbstore.{Event, Query, QueryItem, Tags}

import scala.reflect.Typeable

/**
 * A projection combining several projection functions (handlers) working on a single common state and a single set of
 * tags. This allows building a single [[QueryItem]] from all event types and the given tags. Implements the [[queryO]]
 * property for the resulting [[Query]] (or no query if [[handlers]] is empty) to fetch events of the correct types and
 * tags. Implements the [[updatedProjection()]] method to apply the handlers to a given event and the current state.
 *
 * @param state    The current state of this projection, common for all handlers.
 * @param handlers A map of event types to handlers, i.e. functions that transform the state.
 *                 See [[SingleProjection.handler()]] for creating such handlers.
 *
 *                 The handlers will use the same state but may have different data types. Actually, the type of
 *                 "Event Data" should remain opaque to the processing of events, like here in [[updatedProjection()]].
 *                 Thus, in parameter [[handlers]], the data type is [[Any]]. It is expected that the correct data type
 *                 is baked into each function, and that each function only creates a new state from the event if both
 *                 the event type value and the event data type match. Else it is expected that the function returns
 *                 the state unchanged.
 * @param tags     The tags for this projection.
 * @tparam State     The type of the projection's state.
 * @tparam EventType The type of the events' event type.
 */
final case class SingleProjection[State, EventType](
                                                     override val state: State,
                                                     handlers: Map[EventType, (state: State, event: (EventType, Any)) => State],
                                                     tags: Tags = Tags.empty,
                                                   )
  extends Projection[State, EventType]:

  // If no handlers are given, there is no point in fetching events, as no handler would update the state.
  // However, a QueryItem with empty eventTypes is allowed and means to fetch all events that match the tags.
  // Thus, if no handlers are given, we explicitly return no Query.
  override val queryO: Option[Query[EventType]] =
    val eventTypes = handlers.keySet
    Option.unless(eventTypes.isEmpty)(Query(QueryItem(eventTypes, tags)))

  override def updatedProjection(event: Event[EventType, Any]): Projection[State, EventType] =
    handlers.get(event.eventType) match
      // A handlerF exists and the tags match.
      case Some(handlerF) if tags.subsetOf(event.tags) =>
        copy(state = handlerF(state, (event.eventType, event.data)))
      // Ignore the event, as no handlerF found or tags mismatch.
      case _ => this


object SingleProjection:

  /**
   * Helper method to establish bidirectional type safety between the "Event Data" type and the "Event Type" value.
   * This method creates a single handler function for being part of a [[SingleProjection]].
   * It takes a handler function [[handlerF]] that transforms a state of type [[State]] and an event's "Event Data" of
   * type [[Data]] into a new state. It derives from this function signature the "Event Type" value of type
   * [[EventType]] for that "Event Data" type [[Data]] and a function that can be used to process events,
   * see [[SingleProjection]].
   *
   * Specifically, this handler method transforms the given function [[handlerF]] strongly typed for [[Data]] into a
   * function suitable for processing events of data type [[Any]]. Having [[Any]] as the data type in the resulting
   * function signature allows the [[SingleProjection]] to be agnostic to the actual type of any "Event Data". It
   * therefore also allows combining several handlers with different data types into a single projection, see
   * [[SingleProjection]].
   *
   * As the data type in the resulting function is [[Any]], the data type [[Data]] must have a type test [[Typeable]]
   * that can be used at runtime to check if the incoming data of type [[Any]] actually is of the correct type [[Data]].
   * If it is not, the handler is not responsible for processing the event and just returns the original state.
   *
   * In addition, the function verifies that it is called with the correct "Event Type" value of type [[EventType]]
   * which was derived from the original [[Data]] type. This strengthens the bidirectional type safety of the projection
   * between the "Event Data" type and the "Event Type" value.
   *
   * The type of [[State]] however does not need to be checked at runtime, as the compiler guarantees this already.
   *
   * @param handlerF        The function to apply to events of the correct event type value and data type.
   * @param dataToEventType A given conversion from the event's [[Data]] type to the event's[[EventType]] type.
   * @tparam State     The type of the projection's state.
   * @tparam EventType The type of the events' event type.
   * @tparam Data      The type of the events' data. It must have a Typeable instance so that the type [[Data]] can be
   *                   checked at runtime.
   * @return A tuple of the event type and the type-safe event projection function.
   */
  def handler[State, EventType, Data: Typeable](handlerF: (State, Data) => State)
                                               (using dataToEventType: DataToEventType[Data, EventType])
  : (eventType: EventType, handlerF: (state: State, event: (EventType, Any)) => State) =
    given CanEqual[EventType, EventType] = CanEqual.derived

    val eventType = dataToEventType.eventType
    (
      eventType = eventType,
      handlerF = {
        case (state, (`eventType`, data: Data)) => handlerF(state, data)
        case (state, _) => state
      },
    )


  /** A type class that converts an event's [[Data]] type to the event's [[EventType]] type. */
  trait DataToEventType[Data, EventType]:

    def eventType: EventType
