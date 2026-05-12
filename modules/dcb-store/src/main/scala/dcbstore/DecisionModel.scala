package dcbstore

import scala.concurrent.{ExecutionContext, Future}

object DecisionModel:

  /**
   * Projects the events of a given [[DcbStore]] into the combined state of given [[Projection]]s.
   *
   * @param dcbStore    The [[DcbStore]] to read events from. As the store's _Data_ type is opaque to the projections, a
   *                    type wildcard is used here.
   * @param projections A map of projection keys to projections. The projections may have different state types. Thus,
   *                    [[State]] is effectively a common super type of all the states' types.
   * @tparam K         The type of the projection key in [[projections]].
   * @tparam State     A super type of the different projection states' types.
   * @tparam EventType The type of the events' event type.
   * @return A [[Future]] that resolves to the projections' combined state, and an optional [[AppendCondition]] that can
   *         be used for appending events to the store. The [[AppendCondition]] is only set if the projections involved
   *         a query.
   */
  def buildDecisionModel[K, State, EventType](
                                               dcbStore: DcbStore[EventType, ?],
                                               projections: Map[K, Projection[State, EventType]],
                                             )(using ExecutionContext)
  : Future[(
    finalState: Map[K, State],
    appendConditionO: Option[AppendCondition[EventType, dcbStore.SequencePosition]],
  )] =
    val composedProjection = ComposedProjection(projections)
    val composedInitialState = composedProjection.initialState
    val resultO = composedProjection.queryO.map: query =>
      for
        sequencedEvents <- dcbStore.read(query)
      yield
        var state: Map[K, State] = composedInitialState
        var lastSequencePositionO: Option[dcbStore.SequencePosition] = None
        sequencedEvents.iterator.foreach:
          case SequencedEvent(event, sequencePosition) =>
            state = composedProjection.apply(state, event)
            lastSequencePositionO = Some(sequencePosition)
        (state, Some(AppendCondition(query, lastSequencePositionO)))
    resultO.getOrElse(Future.successful((composedInitialState, None)))

  /**
   * Similar to [[buildDecisionModel()]] for multiple projections, but for a single projection.
   */
  def buildDecisionModel[State, EventType](
                                            dcbStore: DcbStore[EventType, ?],
                                            projection: Projection[State, EventType],
                                          )(using ExecutionContext)
  : Future[(finalState: State, appendConditionO: Option[AppendCondition[EventType, dcbStore.SequencePosition]])] =
    val key = "TheKey"
    buildDecisionModel(dcbStore, Map(key -> projection)).map:
      (finalStateMap, appendConditionO) => (finalStateMap(key), appendConditionO)

  /**
   * Similar to [[buildDecisionModel()]] for multiple projections, but returns only the final state of the projections.
   * Useful for ad-hoc synchronous read-only projections.
   */
  def buildReadModel[K, State, EventType](
                                           dcbStore: DcbStore[EventType, ?],
                                           projections: Map[K, Projection[State, EventType]],
                                         )(using ExecutionContext): Future[Map[K, State]] =
    buildDecisionModel(dcbStore, projections).map(_.finalState)

  /**
   * Similar to [[buildDecisionModel()]] for a single projection, but returns only the final state of the projection.
   * Useful for ad-hoc synchronous read-only projections.
   */
  def buildReadModel[K, State, EventType](
                                           dcbStore: DcbStore[EventType, ?],
                                           projection: Projection[State, EventType],
                                         )(using ExecutionContext): Future[State] =
    buildDecisionModel(dcbStore, projection).map(_.finalState)


extension [EventType](dcbStore: DcbStore[EventType, ?])
  def buildDecisionModel[K, State](projections: Map[K, Projection[State, EventType]])
                                  (using ExecutionContext)
  : Future[(
    finalState: Map[K, State],
    appendConditionO: Option[AppendCondition[EventType, dcbStore.SequencePosition]],
  )] =
    DecisionModel.buildDecisionModel(dcbStore, projections)

extension [EventType](dcbStore: DcbStore[EventType, ?])
  def buildDecisionModel[State](projection: Projection[State, EventType])
                               (using ExecutionContext)
  : Future[(finalState: State, appendConditionO: Option[AppendCondition[EventType, dcbStore.SequencePosition]])] =
    DecisionModel.buildDecisionModel(dcbStore, projection)

extension [EventType](dcbStore: DcbStore[EventType, ?])
  def buildReadModel[K, State](projections: Map[K, Projection[State, EventType]])
                              (using ExecutionContext): Future[Map[K, State]] =
    DecisionModel.buildReadModel(dcbStore, projections)

extension [State, EventType](dcbStore: DcbStore[EventType, ?])
  def buildReadModel(projection: Projection[State, EventType])
                    (using ExecutionContext): Future[State] =
    DecisionModel.buildReadModel(dcbStore, projection)
