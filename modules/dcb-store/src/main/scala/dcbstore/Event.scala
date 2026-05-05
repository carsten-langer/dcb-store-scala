// A simple DCB store API following https://dcb.events.

package dcbstore

final case class Event[EventType, +Data](eventType: EventType, data: Data, tags: Tags)
