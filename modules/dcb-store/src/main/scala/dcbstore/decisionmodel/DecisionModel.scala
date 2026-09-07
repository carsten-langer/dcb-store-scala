package dcbstore.decisionmodel

import dcbstore.{AppendCondition, DcbStore}

import scala.concurrent.{ExecutionContext, Future}

/**
 * Convenience methods and classes to build a decision model.
 * It works with the convention that the value of the "Event Type" is derived from the type of the "Event Data" value.
 * This is more specific than a general [[DcbStore]] requires.
 */
object DecisionModel:

  /**
   * Projects the events of a given [[DcbStore]] into the state of a given [[Projection]].
   *
   * @param dcbStore   The [[DcbStore]] to read events from. As the store's _Data_ type is opaque to the projections, a
   *                   type wildcard is used here.
   * @param projection The projection.
   * @tparam State     A type of the projection state.
   * @tparam EventType The type of the events' event type.
   * @return A [[Future]] that resolves to the projection's state, and an optional [[AppendCondition]] that can
   *         be used for appending events to the store. The [[AppendCondition]] is only set if the projection involved
   *         a query.
   */
  def buildDecisionModel[State, EventType](
                                            dcbStore: DcbStore[EventType, ?],
                                            projection: Projection[State, EventType],
                                          )(using ExecutionContext)
  : Future[(finalState: State, appendConditionO: Option[AppendCondition[EventType, dcbStore.SequencePosition]])] =
    // Must be a def rather than a val to not break the as-reference semantic in fold(ifEmpty: => B).
    def resultForEmptyQuery = Future.successful((finalState = projection.state, appendConditionO = None))

    projection.queryO.fold(resultForEmptyQuery): query =>
      for
        sequencedEvents <- dcbStore.read(query)
        acc0 = (projection = projection, lastSequencePositionO = Option.empty[dcbStore.SequencePosition])
        (finalProjection, lastSequencePositionO) = sequencedEvents.iterator.foldLeft(acc0): (acc, sequencedEvent) =>
          val oldProjection = acc.projection
          val newProjection = oldProjection.updatedProjection(sequencedEvent.event)
          val newLastSequencePositionO = Some(sequencedEvent.sequencePosition)
          (newProjection, newLastSequencePositionO)
      yield
        (finalState = finalProjection.state, appendConditionO = Some(AppendCondition(query, lastSequencePositionO)))

  /**
   * Projects the events of a given [[DcbStore]] into the combined state of multiple given [[Projection]]s.
   * Convenience method to first build a composed projection and second use it with [[buildDecisionModel()]].
   * Also reminds that the finalState of a composed projection is an iterable of combined state.
   *
   * @param dcbStore    The [[DcbStore]] to read events from. As the store's _Data_ type is opaque to the projections, a
   *                    type wildcard is used here.
   * @param projections An iterable of projections. The projections may have different state types. Thus, [[State]] is
   *                    effectively a common super type of all the states' types.
   * @tparam State     A super type of the different projection states' types.
   * @tparam EventType The type of the events' event type.
   * @return A [[Future]] that resolves to the projections' combined state, and an optional [[AppendCondition]] that can
   *         be used for appending events to the store. The [[AppendCondition]] is only set if the projections involved
   *         a query.
   */
  def buildDecisionModel[State, EventType](
                                            dcbStore: DcbStore[EventType, ?],
                                            projections: Iterable[Projection[State, EventType]],
                                          )(using ExecutionContext)
  : Future[(
    finalState: Iterable[State],
    appendConditionO: Option[AppendCondition[EventType, dcbStore.SequencePosition]],
  )] =
    val composedProjection = ComposedProjection(projections)
    // The IDE may indicate this call to be recursive, this is a wrong indication.
    buildDecisionModel(dcbStore, composedProjection)

  /**
   * Convenience method similar to [[buildDecisionModel()]] for a projection, but returns only the final state of the
   * projection. Useful for ad-hoc synchronous read-only projections.
   */
  def buildReadModel[State, EventType](
                                        dcbStore: DcbStore[EventType, ?],
                                        projection: Projection[State, EventType],
                                      )(using ExecutionContext): Future[State] =
    buildDecisionModel(dcbStore, projection).map(_.finalState)

  /**
   * Convenience method similar to [[buildDecisionModel()]] for multiple projections, but returns only the final state
   * of the projections. Useful for ad-hoc synchronous read-only projections.
   */
  def buildReadModel[State, EventType](
                                        dcbStore: DcbStore[EventType, ?],
                                        projections: Iterable[Projection[State, EventType]],
                                      )(using ExecutionContext): Future[Iterable[State]] =
    buildDecisionModel(dcbStore, projections).map(_.finalState)


// Convenience extensions to call the buildDecisionModel() etc. methods directly on a DcbStore.
extension [EventType](dcbStore: DcbStore[EventType, ?])
  def buildDecisionModel[State](projection: Projection[State, EventType])
                               (using ExecutionContext)
  : Future[(finalState: State, appendConditionO: Option[AppendCondition[EventType, dcbStore.SequencePosition]])] =
    DecisionModel.buildDecisionModel(dcbStore, projection)

extension [EventType](dcbStore: DcbStore[EventType, ?])
  def buildDecisionModel[State](projections: Iterable[Projection[State, EventType]])
                               (using ExecutionContext)
  : Future[(
    finalState: Iterable[State],
    appendConditionO: Option[AppendCondition[EventType, dcbStore.SequencePosition]],
  )] =
    DecisionModel.buildDecisionModel(dcbStore, projections)

extension [State, EventType](dcbStore: DcbStore[EventType, ?])
  def buildReadModel(projection: Projection[State, EventType])
                    (using ExecutionContext): Future[State] =
    DecisionModel.buildReadModel(dcbStore, projection)

extension [EventType](dcbStore: DcbStore[EventType, ?])
  def buildReadModel[State](projections: Iterable[Projection[State, EventType]])
                           (using ExecutionContext): Future[Iterable[State]] =
    DecisionModel.buildReadModel(dcbStore, projections)
