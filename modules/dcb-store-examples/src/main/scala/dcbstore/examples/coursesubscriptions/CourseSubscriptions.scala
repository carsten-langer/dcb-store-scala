package dcbstore.examples.coursesubscriptions

import dcbstore.decisionmodel.*
import dcbstore.decisionmodel.SingleProjection.handler
import dcbstore.examples.{*, given}
import dcbstore.{AppendCondition, DcbStore, Event, Events, Tags}

import scala.concurrent.{ExecutionContext, Future}

// The example https://dcb.events/examples/course-subscriptions/
// Courses have a capacity, students subscribe to courses. A subscription must respect constraints of both the course
// (it exists and is not full) and the student (not yet subscribed to it and at most 5 subscriptions). With DCB, the
// StudentSubscribedToCourse event simply carries the tags of both, so one decision covers both entities.

type CourseId = String
type StudentId = String

def courseTags(courseId: CourseId): Tags = Tags("course", courseId)

def studentTags(studentId: StudentId): Tags = Tags("student", studentId)


////////////////////////////////////////
// Events
////////////////////////////////////////
// Each event knows its tags, i.e. which of its fields are tags in terms of the example.

final case class CourseDefined(courseId: CourseId, capacity: Int) derives CanEqual:
  def toEvent: Event[EventType, CourseDefined] = event(this, courseTags(courseId))

final case class CourseCapacityChanged(courseId: CourseId, newCapacity: Int) derives CanEqual:
  def toEvent: Event[EventType, CourseCapacityChanged] = event(this, courseTags(courseId))

final case class StudentSubscribedToCourse(studentId: StudentId, courseId: CourseId) derives CanEqual:
  def toEvent: Event[EventType, StudentSubscribedToCourse] = event(this, Tags(studentTags(studentId), courseTags(courseId)))


////////////////////////////////////////
// Projections
////////////////////////////////////////

def courseExists(courseId: CourseId) = SingleProjection(
  internalState = false,
  handlers = Map(
    handler(handlerF = (_: Boolean, _: CourseDefined) => true, tags = courseTags(courseId)),
  ),
)

def courseCapacity(courseId: CourseId) = SingleProjection(
  internalState = 0,
  handlers = Map(
    handler(handlerF = (_: Int, e: CourseDefined) => e.capacity, tags = courseTags(courseId)),
    handler(handlerF = (_: Int, e: CourseCapacityChanged) => e.newCapacity, tags = courseTags(courseId)),
  ),
)

def courseSubscriptionCount(courseId: CourseId) = SingleProjection(
  internalState = 0,
  handlers = Map(
    handler(handlerF = (count: Int, _: StudentSubscribedToCourse) => count + 1, tags = courseTags(courseId)),
  ),
)

def studentSubscriptionCount(studentId: StudentId) = SingleProjection(
  internalState = 0,
  handlers = Map(
    handler(handlerF = (count: Int, _: StudentSubscribedToCourse) => count + 1, tags = studentTags(studentId)),
  ),
)

def studentAlreadySubscribed(studentId: StudentId, courseId: CourseId) = SingleProjection(
  internalState = false,
  handlers = Map(
    handler(
      handlerF = (_: Boolean, _: StudentSubscribedToCourse) => true,
      tags = Tags(studentTags(studentId), courseTags(courseId)),
    ),
  ),
)


////////////////////////////////////////
// Commands
////////////////////////////////////////

final case class DefineCourse(courseId: CourseId, capacity: Int)

final case class ChangeCourseCapacity(courseId: CourseId, newCapacity: Int)

final case class SubscribeStudentToCourse(studentId: StudentId, courseId: CourseId)

/** A command violating a business rule. Other than a failed append condition, this is a regular outcome. */
final case class Rejected(reason: String) derives CanEqual

val MaxSubscriptionsPerStudent = 5

/**
 * Handles the commands: builds a decision model from a named tuple of projections, checks the business rules on its
 * typed state, and appends the resulting events with the decision model's append condition. The append condition
 * covers exactly the events the decision was based on, i.e. the consistency boundary, so the append fails if a
 * conflicting event was appended in the meantime.
 *
 * @return A [[Future]] of [[Rejected]] if a business rule is violated, or of [[Unit]] if the events were appended. A
 *         failed [[Future]] if the append condition failed, i.e. the command can be retried.
 */
final class CourseSubscriptionsCommandHandler(store: DcbStore[EventType, DataSuper])(using ExecutionContext):

  def handle(command: DefineCourse): Future[Either[Rejected, Unit]] =
    import command.*
    store.buildDecisionModel((exists = courseExists(courseId))).flatMap: (state, appendConditionO) =>
      val decision =
        if state.exists then Left(Rejected("Course already exists"))
        else Right(List(CourseDefined(courseId, capacity).toEvent))
      appendIfAccepted(decision, appendConditionO)

  def handle(command: ChangeCourseCapacity): Future[Either[Rejected, Unit]] =
    import command.*
    val projections = (exists = courseExists(courseId), capacity = courseCapacity(courseId))
    store.buildDecisionModel(projections).flatMap: (state, appendConditionO) =>
      val decision =
        if !state.exists then Left(Rejected("Course does not exist"))
        else if state.capacity == newCapacity then Left(Rejected("Capacity is unchanged"))
        else Right(List(CourseCapacityChanged(courseId, newCapacity).toEvent))
      appendIfAccepted(decision, appendConditionO)

  def handle(command: SubscribeStudentToCourse): Future[Either[Rejected, Unit]] =
    import command.*
    val projections = (
      courseExists = courseExists(courseId),
      courseCapacity = courseCapacity(courseId),
      courseSubscriptionCount = courseSubscriptionCount(courseId),
      studentAlreadySubscribed = studentAlreadySubscribed(studentId, courseId),
      studentSubscriptionCount = studentSubscriptionCount(studentId),
    )
    store.buildDecisionModel(projections).flatMap: (state, appendConditionO) =>
      val decision =
        if !state.courseExists then Left(Rejected("Course does not exist"))
        else if state.courseSubscriptionCount >= state.courseCapacity then Left(Rejected("Course is full"))
        else if state.studentAlreadySubscribed then Left(Rejected("Student is already subscribed"))
        else if state.studentSubscriptionCount >= MaxSubscriptionsPerStudent
        then Left(Rejected("Student is subscribed to too many courses"))
        else Right(List(StudentSubscribedToCourse(studentId, courseId).toEvent))
      appendIfAccepted(decision, appendConditionO)

  private def appendIfAccepted[Data <: DataSuper](
                                                   decision: Either[Rejected, Events[EventType, Data]],
                                                   appendConditionO: Option[AppendCondition[EventType, store.SequencePosition]],
                                                 ): Future[Either[Rejected, Unit]] =
    decision match
      case Left(rejected) => Future.successful(Left(rejected))
      case Right(events) => store.append(events, appendConditionO).map(Right(_))
