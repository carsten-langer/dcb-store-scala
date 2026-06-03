package dcbstore.decisionmodel

import dcbstore.{Event, Query}

/**
 * A projection combining several other projections into a composed projection.
 * Each projection builds a single [[QueryItem]], which are all combined into a [[Query]]. Implements the [[queryO]]
 * property for the resulting [[Query]] (or no query if [[projections]] is empty). Implements the
 * [[updatedProjection()]] method to apply the projections to a given event and the current combined state.
 *
 * @param projections A map of projection keys to projections. The projections may have different state types. Thus,
 *                    [[State]] is effectively a common super type of all the states' types.
 * @tparam K         The type of the projection key in [[projections]].
 * @tparam State     A super type of the different projection states' types.
 * @tparam EventType The type of the events' event type.
 * @return A projection that combines the given projections.
 */
final case class ComposedProjection[K, State, EventType](projections: Map[K, Projection[State, EventType]])
  extends Projection[Map[K, State], EventType]:

  override val state: Map[K, State] = projections.view.mapValues(_.state).toMap

  // If no projections are given, there is no point in fetching events, as no projection would update the state.
  // However, a Query with empty queryItems is allowed and means to fetch all events.
  // Thus, if no projections are given, we explicitly return no Query.
  override val queryO: Option[Query[EventType]] =
    Option.unless(projections.isEmpty)(projections.values).flatMap: ps =>
      val definedQueries = ps.flatMap(_.queryO)
      val maybeMergedQuery = definedQueries.reduceOption(_.merge(_))
      maybeMergedQuery

  override def updatedProjection(event: Event[EventType, Any]): Projection[Map[K, State], EventType] =
    queryO match
      case Some(query) if query.matches(event) =>
        // at least one projection should match the event.
        val newProjections = projections.view.mapValues(_.updatedProjection(event)).toMap
        copy(projections = newProjections)
      case _ => this
