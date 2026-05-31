package dcbstore.decisionmodel

import dcbstore.{Event, Query, QueryItem, Tags}

import scala.reflect.Typeable

/**
 * A projection base.
 */
trait ProjectionBase[State, EventType]:

  def initialState: State

  def queryO: Option[Query[EventType]]

  def apply(state: State, event: Event[EventType, Any]): State


/**
 * Creates a projection from several handlers all working on a common state, combined with a common initial state and
 * tags. It allows building a single [[QueryItem]] from all event types and the given tags. Provides a [[queryO]]
 * property for the resulting [[Query]] (or no query if [[handlers]] is emtpy) to fetch events of the correct types and
 * tags. Provides an [[apply()]] method to apply the handlers to a given state and event.
 *
 * @param initialState The common initial state of this projection.
 * @param handlers     A map of event types to handlers, i.e. partial functions that transform the state.
 *                     See [[Projection.handler()]] for creating such handlers.
 *                     The handlers will use the same state but may have different data types. Thus, in parameter
 *                     [[handlers]], the data type is [[Any]]. However, it is expected that the correct data type is
 *                     baked into each partial function, and that it matches the corresponding event type. This will
 *                     be tested via "isDefinedAt()" at runtime before calling the handler.
 * @param tags         The tags for this projection.
 * @tparam State     The type of the projection's state.
 * @tparam EventType The type of the events' event type.
 */
final case class Projection[State, EventType](
                                               override val initialState: State,
                                               handlers: Map[EventType, PartialFunction[(State, EventType, Any), State]],
                                               tags: Tags = Tags.empty,
                                             )
  extends ProjectionBase[State, EventType]:

  // If no handlers are given, there is no point in fetching events, as no handler would update the state.
  // However, a QueryItem with empty eventTypes is allowed and means to fetch all events that match the tags.
  // Thus, if no handlers are given, we explicitly return no Query.
  override val queryO: Option[Query[EventType]] =
    val eventTypes = handlers.keySet
    Option.unless(eventTypes.isEmpty)(Query(QueryItem(eventTypes, tags)))

  override def apply(state: State, event: Event[EventType, Any]): State =
    handlers.get(event.eventType) match
      // A handlerPF exists, the tags match, and the handlerPF is defined for the data type.
      case Some(handlerPF) if tags.subsetOf(event.tags) && handlerPF.isDefinedAt((state, event.eventType, event.data)) =>
        handlerPF(state, event.eventType, event.data)
      // Ignore the event, as no handlerPF found, tags mismatch, or data type mismatches.
      // Another projection should handle it.
      case _ => state


/**
 * Composes several projections into a single projection. Typcially used from [[DecisionModel.buildDecisionModel()]].
 * Each projection builds a single [[QueryItem]], which are all combined into a [[Query]]. Provides a [[queryO]]
 * property for the resulting [[Query]] (or no query if [[projections]] is emtpy). Provides an [[apply()]] method to
 * apply the projections to a given state and event.
 *
 * @param projections A map of projection keys to projections. The projections may have different state types. Thus,
 *                    [[State]] is effectively a common super type of all the states' types.
 * @tparam K         The type of the projection key in [[projections]].
 * @tparam State     A super type of the different projection states' types.
 * @tparam EventType The type of the events' event type.
 * @return A single projection that combines the given projections.
 */
final case class ComposedProjection[K, State, EventType](projections: Map[K, Projection[State, EventType]])
  extends ProjectionBase[Map[K, State], EventType]:

  override val initialState: Map[K, State] = projections.view.mapValues(_.initialState).toMap

  // If no projections are given, there is no point in fetching events, as no projection would update the state.
  // However, a Query with empty queryItems is allowed and means to fetch all events.
  // Thus, if no projections are given, we explicitly return no Query.
  override val queryO: Option[Query[EventType]] =
    if projections.isEmpty
    then
      None
    else
      val maybeQueries = projections.values.map(_.queryO)
      val definedQueries = maybeQueries.flatten
      val maybeMergedQuery = definedQueries.reduceOption(_.merge(_))
      maybeMergedQuery

  override def apply(state: Map[K, State], event: Event[EventType, Any]): Map[K, State] =
    if queryO.exists(_.matches(event))
    then
      projections.map: (key, projection) =>
        // For correct usage of this apply() method, the state map must be consistent to the original projections map.
        // If the following Map.apply() fails this indicates a usage error of this ComposedProjection.apply() method.
        // We rather throw an error than to hide this problem.
        val oldState = state(key)
        val newState = projection(oldState, event)
        (key, newState)
    else
      state


object Projection:

  /**
   * Helper method to establish bidirectional type safety between the "Event Data" type and the "Event Type" value.
   * This method creates a single handler for being part of a [[Projection]].
   * It takes a handler function [[handlerF]] that transforms a state of type [[State]] and an event's "Event Data" of
   * type [[Data]] into a new state. It derives from it the "Event Type" value of type [[EventType]] for that
   * "Event Data" type [[Data]] and a partial function that can be used process events, see [[Projection]].
   * Specifically, this handler method transforms the given non-partial function [[handlerF]] strongly typed for
   * [[Data]] into a partial function handlerPF suitable for processing events of data type [[Any]].
   * Having [[Any]] as the data type in the resulting partial function signature allows combining several handlers with
   * different data types into a single projection, see [[Projection]].
   * As the data type in the resulting partial function is [[Any]], the data type [[Data]] must have a type test
   * [[Typeable.unapply()]] that can be used at runtime to check if the given data type is the correct type.
   * This way the resulting partial function is only defined for data of the correct type [[Data]], even if it can be
   * called with data of type [[Any]]. It is now the caller's responsibility to check before calling if the partial
   * function is defined for a given data type. As such, the type safety for the data type is guaranteed.
   * In addition, the partial function verifies that it is called with the correct "Event Type" value of type
   * [[EventType]] which was derived from the original [[Data]] type. This strengthens the bidirectional type safety of
   * the projection between the "Event Data" type and the "Event Type" value.
   * The type of [[State]] however is not checked by the resulting partial function handlerPF, as the value is directly
   * forwarded to the non-partial function [[handlerF]]. It is the caller's responsibility to only call the partial
   * function handlerPF with a state of the correct type.
   *
   * @param handlerF        The function this handler shall apply to events of the correct type.
   * @param dataToEventType A given conversion from the event's [[Data]] type to the event's[[EventType]] type.
   * @tparam State     The type of the projection's state.
   * @tparam EventType The type of the events' event type.
   * @tparam Data      The type of the events' data. It must have a Typeable instance so that the type [[Data]] can be
   *                   checked at runtime.
   * @return A tuple of the event type and the type-safe partial event function.
   */
  def handler[State, EventType, Data: Typeable](handlerF: (State, Data) => State)
                                               (using dataToEventType: DataToEventType[Data, EventType])
  : (eventType: EventType, handlerPF: PartialFunction[(State, EventType, Any), State]) =
    given CanEqual[EventType, EventType] = CanEqual.derived

    val eventType = dataToEventType.eventType
    (
      eventType = eventType,
      handlerPF = {
        case (state: State, `eventType`, data: Data) => handlerF(state, data)
      },
    )


  /** A type class that converts an event's [[Data]] type to the event's [[EventType]] type. */
  trait DataToEventType[Data, EventType]:

    def eventType: EventType
