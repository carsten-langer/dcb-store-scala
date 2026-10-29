package dcbstore.decisionmodel

import dcbstore.{AppendCondition, DcbStore}

import scala.NamedTuple.NamedTuple
import scala.concurrent.{ExecutionContext, Future}

// All extensions on DcbStore live in this one file: they overload each other, and Scala 3 only allows overloads of
// top-level definitions within the same file ("overloaded methods must all be defined in the same group of toplevel
// definitions").

// Convenience extensions to call the buildDecisionModel() etc. methods directly on a DcbStore, using DecisionModel.
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

// Convenience extensions to call the buildDecisionModel() etc. methods directly on a DcbStore, using NamedDecisionModel.
extension [EventType](dcbStore: DcbStore[EventType, ?])
  def buildDecisionModel[Ns <: Tuple, Ps <: Tuple, States <: Tuple](projections: NamedTuple[Ns, Ps])
                                                                   (using
                                                                    ExecutionContext,
                                                                    TupleProjection.Builder[Ps, EventType, States],
                                                                   )
  : Future[(
    finalState: NamedTuple[Ns, States],
    appendConditionO: Option[AppendCondition[EventType, dcbStore.SequencePosition]],
  )] =
    NamedDecisionModel.buildDecisionModel(dcbStore, projections)

extension [EventType](dcbStore: DcbStore[EventType, ?])
  def buildReadModel[Ns <: Tuple, Ps <: Tuple, States <: Tuple](projections: NamedTuple[Ns, Ps])
                                                               (using
                                                                ExecutionContext,
                                                                TupleProjection.Builder[Ps, EventType, States],
                                                               )
  : Future[NamedTuple[Ns, States]] =
    NamedDecisionModel.buildReadModel(dcbStore, projections)
