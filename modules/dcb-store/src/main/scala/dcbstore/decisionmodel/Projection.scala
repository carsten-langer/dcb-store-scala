package dcbstore.decisionmodel

import dcbstore.{Event, Query}

/** A projection with the current state and projection function baked in. */
trait Projection[+State, EventType]:

  /** The current state of this projection. */
  def state: State

  /**
   * The [[Query]] that can be used to fetch events of the correct type(s) and tag(s) corresponding to this projection's
   * baked in projection function. No query is returned if the projection does not need one (e.g. because it does not
   * have a projection function).
   */
  def queryO: Option[Query[EventType]]

  def updatedProjection(event: Event[EventType, Any]): Projection[State, EventType]
