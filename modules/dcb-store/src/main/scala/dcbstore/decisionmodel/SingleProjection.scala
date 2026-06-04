package dcbstore.decisionmodel

import dcbstore.{Event, Query, QueryItem, Tags}

import scala.reflect.Typeable

/**
 * A projection combining several handlers, i.e. [[QueryItem]]s with their projection functions, working on a single
 * common state. Implements the [[queryO]] property for the resulting [[Query]] (or no query if [[handlers]] is empty)
 * to fetch events of the correct types and tags as encoded in the [[QueryItem]]s. Implements the
 * [[updatedProjection()]] method to apply the handlers to a given event and the current state.
 *
 * @param state    The current state of this projection, common for all handlers.
 * @param handlers A map of [[QueryItem]]s to handler functions that transform the state.
 *                 See [[SingleProjection.handler()]] for creating such handlers.
 *
 *                 The handlers will use the same state but may have different data types. Actually, the type of
 *                 "Event Data" should remain opaque to the processing of events, like here in [[updatedProjection()]].
 *                 Thus, in parameter [[handlers]], the data type is [[Any]]. It is expected that the correct data type
 *                 is baked into each function, and that each function only creates a new state from the event if both
 *                 the event type value and the event data type match. Else it is expected that the function returns
 *                 the state unchanged.
 * @tparam State     The type of the projection's state.
 * @tparam EventType The type of the events' event type.
 */
final case class SingleProjection[State, EventType](
                                                     override val state: State,
                                                     handlers: Map[QueryItem[EventType], (state: State, event: (EventType, Any)) => State],
                                                   )
  extends Projection[State, EventType]:

  // If no handlers are given, there is no point in fetching events, as no handler would update the state.
  // However, a Query with empty QueryItems is allowed and means to fetch all events.
  // Thus, if no handlers are given, we explicitly return no Query.
  override val queryO: Option[Query[EventType]] =
    val queryItems = handlers.keySet
    Option.unless(queryItems.isEmpty)(Query(queryItems))

  override def updatedProjection(event: Event[EventType, Any]): Projection[State, EventType] =
    handlers.foldLeft(this):
      case (projection, (queryItem, handlerF)) if queryItem.matches(event) =>
        projection.copy(state = handlerF(state, (event.eventType, event.data)))
      case (projection, _) => projection


object SingleProjection:

  /**
   * Helper method to establish bidirectional type safety between the "Event Data" type and the "Event Type" value, and
   * to create a [[QueryItem]] from that combination to be used in a [[SingleProjection]].
   *
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
   * @param tags            The tags for the relevant events for the derived "Event Type".
   * @param dataToEventType A given conversion from the event's [[Data]] type to the event's[[EventType]] type.
   * @tparam State     The type of the projection's state.
   * @tparam EventType The type of the events' event type.
   * @tparam Data      The type of the events' data. It must have a Typeable instance so that the type [[Data]] can be
   *                   checked at runtime.
   * @return A tuple of the derived [[QueryItem]] and the type-safe event projection function.
   */
  def handler[State, EventType, Data: Typeable](handlerF: (State, Data) => State, tags: Tags = Tags.empty)
                                               (using dataToEventType: DataToEventType[Data, EventType])
  : (queryItem: QueryItem[EventType], handlerF: (state: State, event: (EventType, Any)) => State) =
    given CanEqual[EventType, EventType] = CanEqual.derived

    val eventType = dataToEventType.eventType
    (
      queryItem = QueryItem(eventType, tags),
      handlerF = {
        case (state, (`eventType`, data: Data)) => handlerF(state, data)
        case (state, _) => state
      },
    )


  /** A type class that converts an event's [[Data]] type to the event's [[EventType]] type. */
  trait DataToEventType[Data, EventType]:

    def eventType: EventType
