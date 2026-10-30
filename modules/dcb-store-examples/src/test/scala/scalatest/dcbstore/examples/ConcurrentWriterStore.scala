package scalatest.dcbstore.examples

import dcbstore.examples.*
import dcbstore.inmemdcbstore.InMemDcbStore
import dcbstore.{AppendCondition, DcbStore, Events, Query, SequencedEvent}

import scala.concurrent.{ExecutionContext, Future}

// A store simulating a concurrent writer: right before the first append, it appends the given concurrent events to the
// underlying store. To a command handler, these events appear between its read and its append, i.e. as a decision made
// concurrently to its own.
final class ConcurrentWriterStore(
                                   val underlying: InMemDcbStore[EventType, DataSuper],
                                   concurrentEvents: Events[EventType, DataSuper],
                                 )(using ExecutionContext)
  extends DcbStore[EventType, DataSuper]:

  override type SequencePosition = underlying.SequencePosition

  override type AppendConditionFailed = underlying.AppendConditionFailed

  private var concurrentEventsAppended = false

  override def append[Data <: DataSuper](
                                          events: Events[EventType, Data],
                                          appendConditionO: Option[AppendCondition[EventType, SequencePosition]],
                                        ): Future[Unit] =
    val concurrentAppend =
      if concurrentEventsAppended then Future.unit
      else
        concurrentEventsAppended = true
        underlying.append(concurrentEvents)
    concurrentAppend.flatMap(_ => underlying.append(events, appendConditionO))

  override def read(query: Query[EventType])
  : Future[IterableOnce[SequencedEvent[EventType, DataSuper, SequencePosition]]] =
    underlying.read(query)
