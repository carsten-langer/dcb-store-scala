// A simple DCB store API following https://dcb.events.

package dcbstore

import scala.collection.immutable

final case class QueryItem[EventType](eventTypes: immutable.Set[EventType], tags: Tags):

  // If eventTypes is empty, then an event with any eventType matches (subject to matching of tags).
  // If eventTypes is non-empty, then an event matches if it matches one of the eventTypes (subject to matching of tags). 
  def matches(event: Event[EventType, ?]): Boolean =
    val eventTypeMatches = eventTypes.isEmpty || eventTypes.contains(event.eventType)
    val tagsMatch = tags.subsetOf(event.tags)
    eventTypeMatches && tagsMatch
