// A simple DCB store API following https://dcb.events.

package dcbstore

import scala.collection.immutable

final case class Query[EventType](queryItems: immutable.Set[QueryItem[EventType]]):

  // If queryItems is empty, then all events match. Else an event matches if at least one queryItem matches.
  def matches(event: Event[EventType, ?]): Boolean = queryItems.isEmpty || queryItems.exists(_.matches(event))


object Query:

  def all[EventType]: Query[EventType] = Query(Set.empty)
