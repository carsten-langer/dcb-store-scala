package dcbstore.decisionmodel

import dcbstore.{AppendCondition, DcbStore}

import scala.NamedTuple.NamedTuple
import scala.concurrent.{ExecutionContext, Future}

/**
 * Typed composition of projections via named tuples.
 *
 * [[DecisionModel.buildDecisionModel()]] for an iterable of projections returns an `Iterable[State]`, i.e. the
 * individual projections' state types are lost and must be recovered by pattern matching. Here, the projections are
 * given as a named tuple instead, and the final state is a named tuple with the same names, each field typed with the
 * state type of its projection:
 *
 * {{{
 *   val model = store.buildDecisionModel((courseExists = courseExists(id), capacity = courseCapacity(id)))
 *   // model: Future[(finalState: (courseExists: Boolean, capacity: Int), appendConditionO: Option[...])]
 *   model.map: (finalState, appendConditionO) =>
 *     if finalState.courseExists && finalState.capacity > 0 then ... // decide
 * }}}
 *
 * All projections are combined into one [[TupleProjection]], i.e. the store is read once with the merged query, and
 * the single resulting [[AppendCondition]] covers all of them. This is the same behaviour as for an iterable of
 * projections; only the shape of the final state differs.
 *
 * The projections may have different state types, but must all share the store's [[EventType]]. This is checked at
 * compile time.
 *
 * A single projection still needs the parentheses of a named tuple, e.g. `store.buildReadModel((capacity = p))`.
 * An empty named tuple yields an empty final state and no [[AppendCondition]].
 */
object NamedDecisionModel:

  /**
   * Projects the events of a given [[DcbStore]] into the states of a named tuple of [[Projection]]s, keeping each
   * projection's state type.
   *
   * @param dcbStore          The [[DcbStore]] to read events from. As the store's _Data_ type is opaque to the
   *                          projections, a type wildcard is used here.
   * @param projections       A named tuple of projections, e.g. `(courseExists = p1, capacity = p2)`. The projections
   *                          may have different state types.
   * @param toTupleProjection Combines the projections into one [[TupleProjection]]. Derived by the compiler, which
   *                          thereby also checks that all projections are for [[EventType]].
   * @tparam EventType The type of the events' event type.
   * @tparam Ns        The names of the named tuple, inferred.
   * @tparam Ps        The types of the projections, inferred.
   * @tparam States    The types of the projections' states, computed by the compiler.
   * @return A [[Future]] that resolves to the final states as a named tuple with the same names as [[projections]], and
   *         an optional [[AppendCondition]] that can be used for appending events to the store. The [[AppendCondition]]
   *         is only set if at least one projection involved a query.
   */
  def buildDecisionModel[EventType, Ns <: Tuple, Ps <: Tuple, States <: Tuple](
                                                                                dcbStore: DcbStore[EventType, ?],
                                                                                projections: NamedTuple[Ns, Ps],
                                                                              )(using
                                                                                ExecutionContext,
                                                                              )(using
                                                                                toTupleProjection: TupleProjection.Builder[Ps, EventType, States],
                                                                              )
  : Future[(
    finalState: NamedTuple[Ns, States],
    appendConditionO: Option[AppendCondition[EventType, dcbStore.SequencePosition]],
  )] =
    // The final state is a plain tuple of the states. It is returned as is, as a tuple conforms to a named tuple with
    // the same value types.
    DecisionModel.buildDecisionModel(dcbStore, toTupleProjection(projections.toTuple))

  /**
   * Convenience method similar to [[buildDecisionModel()]] for a named tuple of projections, but returns only the final
   * states. Useful for ad-hoc synchronous read-only projections.
   */
  def buildReadModel[EventType, Ns <: Tuple, Ps <: Tuple, States <: Tuple](
                                                                            dcbStore: DcbStore[EventType, ?],
                                                                            projections: NamedTuple[Ns, Ps],
                                                                          )(using
                                                                            ExecutionContext,
                                                                          )(using
                                                                            TupleProjection.Builder[Ps, EventType, States],
                                                                          ): Future[NamedTuple[Ns, States]] =
    buildDecisionModel(dcbStore, projections).map(_.finalState)
