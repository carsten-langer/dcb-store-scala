// A simple DCB store API following https://dcb.events.

package dcbstore

import scala.collection.immutable

final type Events[EventType, Data] = immutable.Seq[Event[EventType, Data]]
