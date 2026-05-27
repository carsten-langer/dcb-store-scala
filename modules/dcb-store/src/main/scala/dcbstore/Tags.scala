// A simple DCB store API following https://dcb.events.

package dcbstore

import scala.collection.immutable

final type Tags = immutable.Set[Tag]


object Tags:

  def apply(tag: Tag): Tags = Set(tag)

  def apply(key: String, value: Int): Tags = apply(Tag(key, value.toString))

  def empty: Tags = Set.empty
