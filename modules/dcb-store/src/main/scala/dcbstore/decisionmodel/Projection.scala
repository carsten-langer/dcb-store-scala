package dcbstore.decisionmodel

import dcbstore.{Event, Query}

/**
 * A projection, having an initial state and an [[apply()]] method to apply the projection a given state and event.
 * Provides a [[queryO]] property for the [[Query]] (if any) that can be used to fetch events of the correct type.
 */
trait Projection[State, EventType]:

  def initialState: State

  def queryO: Option[Query[EventType]]

  def apply(state: State, event: Event[EventType, Any]): State
