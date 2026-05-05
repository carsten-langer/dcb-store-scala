// A simple DCB store API following https://dcb.events.

package dcbstore

final case class SequencedEvent[EventType, +Data, SequencePosition](
                                                                     event: Event[EventType, Data],
                                                                     sequencePosition: SequencePosition,
                                                                   )
