package scalatest.dcbstore.examples

import dcbstore.examples.*
import dcbstore.inmemdcbstore.InMemDcbStore
import dcbstore.{AppendCondition, DcbStore, Events, Query, SequencedEvent}
import scalatest.dcbstore.examples.ConcurrentWriterStore.Moment

import scala.concurrent.{ExecutionContext, Future}

// A store simulating a concurrent writer: once, at the given moment, it appends the given concurrent events to the
// underlying store. By default right before the first append: to a command handler, these events then appear between
// its read and its append, i.e. as a decision made concurrently to its own.
final class ConcurrentWriterStore(
                                   val underlying: InMemDcbStore[EventType, DataSuper],
                                   concurrentEvents: Events[EventType, DataSuper],
                                   moment: Moment = Moment.BeforeFirstAppend,
                                 )(using ExecutionContext)
  extends DcbStore[EventType, DataSuper]:

  override type SequencePosition = underlying.SequencePosition

  override type AppendConditionFailed = underlying.AppendConditionFailed

  private var concurrentEventsAppended = false

  private var readCount = 0

  override def append[Data <: DataSuper](
                                          events: Events[EventType, Data],
                                          appendConditionO: Option[AppendCondition[EventType, SequencePosition]],
                                        ): Future[Unit] =
    appendConcurrentEventsIf(moment == Moment.BeforeFirstAppend)
      .flatMap(_ => underlying.append(events, appendConditionO))

  override def read(query: Query[EventType])
  : Future[IterableOnce[SequencedEvent[EventType, DataSuper, SequencePosition]]] =
    readCount += 1
    appendConcurrentEventsIf(moment == Moment.BeforeRead(readCount))
      .flatMap(_ => underlying.read(query))

  private def appendConcurrentEventsIf(isMoment: Boolean): Future[Unit] =
    if concurrentEventsAppended || !isMoment then Future.unit
    else
      concurrentEventsAppended = true
      underlying.append(concurrentEvents)


object ConcurrentWriterStore:

  enum Moment derives CanEqual:
    case BeforeFirstAppend
    // E.g. BeforeRead(2) between the first and the second read of a command handler reading in two steps.
    case BeforeRead(readNumber: Int)
