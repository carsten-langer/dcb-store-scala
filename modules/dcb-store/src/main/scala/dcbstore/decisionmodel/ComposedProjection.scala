package dcbstore.decisionmodel

import dcbstore.{Event, Query}

/**
 * Composes several projections into a projection.
 * Each projection builds a single [[QueryItem]], which are all combined into a [[Query]]. Implements the [[queryO]]
 * property for the resulting [[Query]] (or no query if [[projections]] is empty). Implements the [[apply()]] method to
 * apply the projections to a given combined state and event.
 *
 * @param projections A map of projection keys to projections. The projections may have different state types. Thus,
 *                    [[State]] is effectively a common super type of all the states' types.
 * @tparam K         The type of the projection key in [[projections]].
 * @tparam State     A super type of the different projection states' types.
 * @tparam EventType The type of the events' event type.
 * @return A single projection that combines the given projections.
 */
final case class ComposedProjection[K, State, EventType](projections: Map[K, Projection[State, EventType]])
  extends Projection[Map[K, State], EventType]:

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
