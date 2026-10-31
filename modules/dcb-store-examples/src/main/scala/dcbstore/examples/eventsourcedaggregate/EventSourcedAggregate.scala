package dcbstore.examples.eventsourcedaggregate

import dcbstore.examples.{*, given}
import dcbstore.{AppendCondition, DcbStore, Event, Query, QueryItem, SequencedEvent, Tags}

import scala.concurrent.{ExecutionContext, Future}

// The example https://dcb.events/examples/event-sourced-aggregate/: a traditional event-sourced aggregate, a course
// students can subscribe to, on top of a DCB store. With DCB, the aggregate stays as it is, only the repository
// changes: the aggregate's "stream" is the query for the events tagged with the course, and the aggregate's version
// for optimistic locking is the position of the last event read, used as the append condition's after.
//
// Other than the other examples, this one has no decision models and projections, but uses the DcbStore API directly,
// and it has no given/when/then scenarios on its page.
//
// Other than the example's JavaScript, the aggregate is immutable: each change returns a new aggregate with the event
// recorded, and a violated business rule is a Left of Rejected rather than an exception.

type CourseId = String
type StudentId = String

def courseTags(courseId: CourseId): Tags = Tags("course", courseId)


////////////////////////////////////////
// Events
////////////////////////////////////////

sealed trait CourseEvent derives CanEqual:
  def courseId: CourseId

  def toEvent: Event[EventType, CourseEvent]

final case class CourseDefined(courseId: CourseId, title: String, capacity: Int) extends CourseEvent:
  override def toEvent: Event[EventType, CourseDefined] = event(this, courseTags(courseId))

final case class CourseCapacityChanged(courseId: CourseId, newCapacity: Int) extends CourseEvent:
  override def toEvent: Event[EventType, CourseCapacityChanged] = event(this, courseTags(courseId))

final case class StudentSubscribedToCourse(courseId: CourseId, studentId: StudentId)
  extends CourseEvent:
  override def toEvent: Event[EventType, StudentSubscribedToCourse] = event(this, courseTags(courseId))


////////////////////////////////////////
// Aggregate
////////////////////////////////////////

/**
 * The course aggregate.
 *
 * @param version        The position of the last event consumed when reconstituting this aggregate, for optimistic
 *                       locking. None for a new aggregate. Its type is the sequence position of the store the aggregate
 *                       was loaded from, so the aggregate itself does not depend on a store.
 * @param recordedEvents The events recording the changes since creation or reconstitution, oldest first.
 */
final case class CourseAggregate[Version] private(
                                                   id: CourseId,
                                                   capacity: Int,
                                                   numberOfSubscriptions: Int,
                                                   version: Option[Version],
                                                   recordedEvents: List[CourseEvent],
                                                 ):

  def changeCapacity(newCapacity: Int): Either[Rejected, CourseAggregate[Version]] =
    if newCapacity == capacity then Left(Rejected(s"Course \"$id\" already has a capacity of \"$newCapacity\""))
    else if newCapacity < numberOfSubscriptions then
      Left(Rejected(
        s"Course \"$id\" already has $numberOfSubscriptions active subscriptions, can't set the capacity below that"
      ))
    else Right(recordEvent(CourseCapacityChanged(id, newCapacity)))

  def subscribeStudent(studentId: StudentId): Either[Rejected, CourseAggregate[Version]] =
    if numberOfSubscriptions >= capacity then Left(Rejected(s"Course \"$id\" is already fully booked"))
    else Right(recordEvent(StudentSubscribedToCourse(id, studentId)))

  private def recordEvent(event: CourseEvent): CourseAggregate[Version] =
    applyEvent(event).copy(recordedEvents = recordedEvents :+ event)

  private def applyEvent(event: CourseEvent): CourseAggregate[Version] =
    event match
      case e: CourseDefined => copy(id = e.courseId, capacity = e.capacity)
      case e: CourseCapacityChanged => copy(capacity = e.newCapacity)
      case _: StudentSubscribedToCourse => copy(numberOfSubscriptions = numberOfSubscriptions + 1)


object CourseAggregate:

  /** A new course, with its CourseDefined event recorded. */
  def create[Version](id: CourseId, title: String, capacity: Int): CourseAggregate[Version] =
    val defined = CourseDefined(id, title, capacity)
    CourseAggregate(id, capacity, numberOfSubscriptions = 0, version = None, recordedEvents = List(defined))

  /**
   * An existing course from its previously appended events. The version is the position of the last of them, also if
   * it is no CourseEvent, as the repository's append condition covers all events of the course.
   *
   * @return None if there are no events, or they do not start with CourseDefined, i.e. there is no such course.
   */
  def reconstitute[Version](sequencedEvents: Iterable[SequencedEvent[EventType, DataSuper, Version]])
  : Option[CourseAggregate[Version]] =
    sequencedEvents.headOption.map(_.event.data).collect:
      case defined: CourseDefined =>
        val initial = CourseAggregate[Version](defined.courseId, 0, 0, version = None, recordedEvents = Nil)
        sequencedEvents.foldLeft(initial): (aggregate, sequencedEvent) =>
          val applied = sequencedEvent.event.data match
            case courseEvent: CourseEvent => aggregate.applyEvent(courseEvent)
            case _ => aggregate
          applied.copy(version = Some(sequencedEvent.sequencePosition))


////////////////////////////////////////
// Repository
////////////////////////////////////////

/**
 * Loads and saves course aggregates from and to a DCB store. The only DCB specific part of the example.
 *
 * Saving appends the recorded events with an append condition failing if any event of the course was appended after
 * the aggregate's version, or for a new aggregate if any event of the course exists at all. So a concurrent change of
 * the same course fails the save, while changes of other courses do not affect it. An aggregate must not be saved twice,
 * as its version is outdated after the first save: load it again instead.
 */
final class DcbCourseRepository(val store: DcbStore[EventType, DataSuper])(using ExecutionContext):

  type Course = CourseAggregate[store.SequencePosition]

  // All events tagged with the course, of any event type. The DCB equivalent of the course's stream.
  private def query(courseId: CourseId): Query[EventType] = Query(QueryItem(Set.empty[EventType], courseTags(courseId)))

  def load(courseId: CourseId): Future[Option[Course]] =
    store.read(query(courseId)).map(sequencedEvents => CourseAggregate.reconstitute(sequencedEvents.iterator.toList))

  def save(course: Course): Future[Unit] =
    store.append(course.recordedEvents.map(_.toEvent), Some(AppendCondition(query(course.id), course.version)))
