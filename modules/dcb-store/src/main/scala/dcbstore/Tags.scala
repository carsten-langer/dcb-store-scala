// A simple DCB store API following https://dcb.events.

package dcbstore

import scala.collection.immutable

final type Tags = immutable.Set[Tag]


object Tags:

  def apply(tag: Tag): Tags = Set(tag)

  def empty: Tags = Set.empty
