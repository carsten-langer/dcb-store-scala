package dcbstore.inmemdcbstore

import dcbstore.*

import scala.concurrent.Future

class InMemDcbStore[EventType, DataSuper]
  extends DcbStore[EventType, DataSuper]:

  override opaque type SequencePosition = Long

  override type AppendConditionFailed = InMemAppendConditionFailed

  private val FirstSequencePosition: SequencePosition = 0L

  private var sequencedEvents: Vector[SequencedEvent[EventType, DataSuper, SequencePosition]] = Vector.empty // Vector.append has eC characteristics
  private var nextSequencePosition: SequencePosition = FirstSequencePosition

  override def append[Data <: DataSuper](
                                          events: Events[EventType, Data],
                                          appendConditionO: Option[AppendCondition[EventType, SequencePosition]],
                                        ): Future[Unit] =
    synchronized:
      val appendConditionFails =
        appendConditionO.exists: condition =>
          val sequencedEventsAfterO = condition.afterO.map: upToAfterIncl =>
            val (_, eventsAfter) = sequencedEvents.span: sequencedEvent =>
              sequencedEvent.sequencePosition <= upToAfterIncl
            eventsAfter
          val sequencedEventsAfter = sequencedEventsAfterO.getOrElse(sequencedEvents)
          sequencedEventsAfter.exists: sequencedEvent =>
            condition.failIfEventsMatch.matches(sequencedEvent.event)

      if appendConditionFails then
        Future.failed(InMemAppendConditionFailed())
      else
        val newSequencedEvents =
          events.map: event =>
            val sequencePosition = nextSequencePosition
            nextSequencePosition = nextSequencePosition + 1L
            SequencedEvent(event, sequencePosition)
        sequencedEvents = sequencedEvents.appendedAll(newSequencedEvents)
        Future.successful(())

  override def read(query: Query[EventType])
  : Future[IterableOnce[SequencedEvent[EventType, DataSuper, SequencePosition]]] =
    synchronized:
      val matchingEvents = sequencedEvents.filter(sequencedEvent => query.matches(sequencedEvent.event))
      Future.successful(matchingEvents)
