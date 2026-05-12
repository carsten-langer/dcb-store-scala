// A simple DCB store API following https://dcb.events.

package dcbstore

import scala.collection.immutable

final case class Query[EventType](queryItems: immutable.Set[QueryItem[EventType]]):

  // If queryItems is empty, then all events match. Else an event matches if at least one queryItem matches.
  def matches(event: Event[EventType, ?]): Boolean = queryItems.isEmpty || queryItems.exists(_.matches(event))

  def merge(other: Query[EventType]): Query[EventType] =
    // Empty queries match always, thus a merged query must also match always if one of its parts is empty.
    if this.queryItems.isEmpty
    then this
    else if other.queryItems.isEmpty
    then other
    else Query(queryItems ++ other.queryItems)


object Query:

  def apply[EventType](queryItem: QueryItem[EventType]): Query[EventType] = Query(Set(queryItem))

  def all[EventType]: Query[EventType] = Query(Set.empty)
