package dcbstore.decisionmodel

import dcbstore.{Event, Query}

import scala.annotation.implicitNotFound

/**
 * A projection combining a tuple of projections into one projection whose state is the tuple of their states, each
 * keeping its own type. In contrast to [[ComposedProjection]], whose state is an `Iterable` of a common super type.
 *
 * It is built recursively like a tuple: [[TupleProjection.Empty]] for `EmptyTuple`, and [[TupleProjection.Cons]] for
 * a head projection followed by a tuple projection of the remaining projections. Use [[TupleProjection.Builder]] to
 * create it from a tuple of projections.
 *
 * @tparam States    The tuple of the projections' state types.
 * @tparam EventType The type of the events' event type.
 */
sealed trait TupleProjection[States <: Tuple, EventType]
  extends Projection[States, EventType]:

  override def updatedProjection(event: Event[EventType, Any]): TupleProjection[States, EventType]


object TupleProjection:

  /** The tuple projection of no projections. It needs no query, and its state is the empty tuple. */
  final case class Empty[EventType]()
    extends TupleProjection[EmptyTuple, EventType]:

    override def state: EmptyTuple = EmptyTuple

    override val queryO: Option[Query[EventType]] = None

    override def updatedProjection(event: Event[EventType, Any]): Empty[EventType] = this

  /** The tuple projection of a head projection followed by the tuple projection of the remaining projections. */
  final case class Cons[State, TailStates <: Tuple, EventType](
                                                                head: Projection[State, EventType],
                                                                tail: TupleProjection[TailStates, EventType],
                                                              )
    extends TupleProjection[State *: TailStates, EventType]:

    // Is def rather than val to not call the projections' states too early, see ComposedProjection.
    override def state: State *: TailStates = head.state *: tail.state

    // As in ComposedProjection: projections without a query do not contribute to the merged query.
    override val queryO: Option[Query[EventType]] =
      (head.queryO ++ tail.queryO).reduceOption(_.merge(_))

    override def updatedProjection(event: Event[EventType, Any]): Cons[State, TailStates, EventType] =
      Cons(head.updatedProjection(event), tail.updatedProjection(event))


  /**
   * Builds a [[TupleProjection]] from a tuple of projections. The instances are derived by the compiler along the
   * structure of the tuple, which is also how the tuple of states [[States]] is computed.
   *
   * @tparam Projections The tuple of projections, e.g. `(SingleProjection[...], SingleProjection[...])`.
   * @tparam EventType   The type of the events' event type, which all projections must share.
   * @tparam States      The tuple of the projections' state types, computed by the compiler.
   */
  @implicitNotFound(
    "Cannot compose ${Projections} into a TupleProjection with event type ${EventType}: " +
      "every element must be a Projection[?, ${EventType}]"
  )
  trait Builder[-Projections <: Tuple, EventType, States <: Tuple]:

    def apply(projections: Projections): TupleProjection[States, EventType]


  object Builder:
    given empty: [EventType] => Builder[EmptyTuple, EventType, EmptyTuple] =
      _ => Empty()

    given cons: [State, Projections <: Tuple, EventType, TailStates <: Tuple]
      => (tail: Builder[Projections, EventType, TailStates])
      => Builder[Projection[State, EventType] *: Projections, EventType, State *: TailStates] =
      case head *: projections => Cons(head, tail(projections))
