// A simple DCB store API following https://dcb.events.

package dcbstore

import scala.collection.immutable

final type Tags = immutable.Set[Tag]


object Tags:

  def apply(tag: Tag): Tags = Set(tag)

  def apply(tags: Tags*): Tags = tags.reduce(_ ++ _)

  def apply(key: String, value: Int): Tags = apply(Tag(key, value.toString))

  def apply(key: String, value: String): Tags = apply(Tag(key, value))

  def empty: Tags = Set.empty
