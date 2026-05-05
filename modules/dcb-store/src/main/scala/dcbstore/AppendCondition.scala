package dcbstore

final case class AppendCondition[EventType, SequencePosition](
                                                               failIfEventsMatch: Query[EventType],
                                                               afterO: Option[SequencePosition]
                                                             )
