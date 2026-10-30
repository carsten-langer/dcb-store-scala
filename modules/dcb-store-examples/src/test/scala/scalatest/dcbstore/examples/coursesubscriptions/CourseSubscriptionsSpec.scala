package scalatest.dcbstore.examples.coursesubscriptions

import dcbstore.examples.*
import dcbstore.examples.coursesubscriptions.*
import dcbstore.inmemdcbstore.{InMemAppendConditionFailed, InMemDcbStore}
import dcbstore.{DcbStore, Event, Query}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scalatest.dcbstore.examples.ConcurrentWriterStore

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

// The scenarios of https://dcb.events/examples/course-subscriptions/, plus the append condition at work.
class CourseSubscriptionsSpec
  extends AnyFlatSpec
    with Matchers
    with ScalaFutures
    with IntegrationPatience:

  // Event does not derive CanEqual in dcb-store, so under strictEquality comparing events needs it given here. The
  // read events have the data type DataSuper, the expected ones their specific data type.
  private given [Data1 <: DataSuper, Data2 <: DataSuper] => CanEqual[Event[EventType, Data1], Event[EventType, Data2]] =
    CanEqual.derived

  // Given the events in a fresh store, when the command is handled, then its result and the newly appended events.
  private def scenario(givenEvents: Event[EventType, DataSuper]*)
                      (when: CourseSubscriptionsCommandHandler => Future[Either[Rejected, Unit]])
  : (result: Either[Rejected, Unit], newEvents: List[Event[EventType, DataSuper]]) =
    val store = InMemDcbStore[EventType, DataSuper]()
    store.append(givenEvents.toList).futureValue
    val result = when(CourseSubscriptionsCommandHandler(store)).futureValue
    (result = result, newEvents = allEvents(store).drop(givenEvents.size))

  private def allEvents(store: DcbStore[EventType, DataSuper]): List[Event[EventType, DataSuper]] =
    store.read(Query.all).futureValue.iterator.map(_.event).toList

  "DefineCourse" should "be rejected for an existing id" in :
    val (result, newEvents) = scenario(CourseDefined("c1", 10).toEvent)(_.handle(DefineCourse("c1", 15)))
    val _ = result shouldBe Left(Rejected("Course already exists"))
    newEvents shouldBe empty

  it should "define a course with a new id" in :
    val (result, newEvents) = scenario()(_.handle(DefineCourse("c1", 15)))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(CourseDefined("c1", 15).toEvent)

  "ChangeCourseCapacity" should "be rejected for a non-existing course" in :
    val (result, newEvents) = scenario()(_.handle(ChangeCourseCapacity("c0", 15)))
    val _ = result shouldBe Left(Rejected("Course does not exist"))
    newEvents shouldBe empty

  it should "be rejected for an unchanged capacity" in :
    val (result, newEvents) = scenario(CourseDefined("c1", 15).toEvent)(_.handle(ChangeCourseCapacity("c1", 15)))
    val _ = result shouldBe Left(Rejected("Capacity is unchanged"))
    newEvents shouldBe empty

  it should "change the capacity of a course to a new value" in :
    val (result, newEvents) = scenario(CourseDefined("c1", 12).toEvent)(_.handle(ChangeCourseCapacity("c1", 15)))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(CourseCapacityChanged("c1", 15).toEvent)

  "SubscribeStudentToCourse" should "be rejected for a non-existing course" in :
    val (result, newEvents) = scenario()(_.handle(SubscribeStudentToCourse("s1", "c0")))
    val _ = result shouldBe Left(Rejected("Course does not exist"))
    newEvents shouldBe empty

  it should "be rejected for a fully booked course" in :
    val (result, newEvents) = scenario(
      CourseDefined("c1", 3).toEvent,
      StudentSubscribedToCourse("s1", "c1").toEvent,
      StudentSubscribedToCourse("s2", "c1").toEvent,
      StudentSubscribedToCourse("s3", "c1").toEvent,
    )(_.handle(SubscribeStudentToCourse("s4", "c1")))
    val _ = result shouldBe Left(Rejected("Course is full"))
    newEvents shouldBe empty

  it should "be rejected for the same course twice" in :
    val (result, newEvents) = scenario(
      CourseDefined("c1", 10).toEvent,
      StudentSubscribedToCourse("s1", "c1").toEvent,
    )(_.handle(SubscribeStudentToCourse("s1", "c1")))
    val _ = result shouldBe Left(Rejected("Student is already subscribed"))
    newEvents shouldBe empty

  it should "be rejected for more than 5 courses" in :
    val (result, newEvents) = scenario(
      CourseDefined("c6", 10).toEvent,
      StudentSubscribedToCourse("s1", "c1").toEvent,
      StudentSubscribedToCourse("s1", "c2").toEvent,
      StudentSubscribedToCourse("s1", "c3").toEvent,
      StudentSubscribedToCourse("s1", "c4").toEvent,
      StudentSubscribedToCourse("s1", "c5").toEvent,
    )(_.handle(SubscribeStudentToCourse("s1", "c6")))
    val _ = result shouldBe Left(Rejected("Student is subscribed to too many courses"))
    newEvents shouldBe empty

  it should "subscribe a student to a course with capacity" in :
    val (result, newEvents) = scenario(CourseDefined("c1", 10).toEvent)(_.handle(SubscribeStudentToCourse("s1", "c1")))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(StudentSubscribedToCourse("s1", "c1").toEvent)

  // The append condition at work: the command handler decides on the state it read, while a concurrent writer appends
  // its event just before the handler's append.

  it should "fail if the course's last seat was taken concurrently" in :
    val underlying = InMemDcbStore[EventType, DataSuper]()
    underlying.append(List(CourseDefined("c1", 1).toEvent)).futureValue
    val concurrentSubscription = StudentSubscribedToCourse("s2", "c1").toEvent
    val store = ConcurrentWriterStore(underlying, List(concurrentSubscription))

    val failure = CourseSubscriptionsCommandHandler(store).handle(SubscribeStudentToCourse("s1", "c1")).failed
    val _ = failure.futureValue shouldBe an[InMemAppendConditionFailed]
    allEvents(underlying) shouldBe List(CourseDefined("c1", 1).toEvent, concurrentSubscription)

  it should "fail if the student subscribed to another course concurrently" in :
    // The student's subscription count is part of the decision, so even another course's subscription conflicts.
    val underlying = InMemDcbStore[EventType, DataSuper]()
    underlying.append(List(CourseDefined("c1", 10).toEvent, CourseDefined("c2", 10).toEvent)).futureValue
    val concurrentSubscription = StudentSubscribedToCourse("s1", "c2").toEvent
    val store = ConcurrentWriterStore(underlying, List(concurrentSubscription))

    val failure = CourseSubscriptionsCommandHandler(store).handle(SubscribeStudentToCourse("s1", "c1")).failed
    failure.futureValue shouldBe an[InMemAppendConditionFailed]

  it should "succeed despite a concurrent subscription of another student to another course" in :
    // Outside the consistency boundary of the decision, so no conflict.
    val underlying = InMemDcbStore[EventType, DataSuper]()
    underlying.append(List(CourseDefined("c1", 10).toEvent, CourseDefined("c2", 10).toEvent)).futureValue
    val concurrentSubscription = StudentSubscribedToCourse("s2", "c2").toEvent
    val store = ConcurrentWriterStore(underlying, List(concurrentSubscription))

    val result = CourseSubscriptionsCommandHandler(store).handle(SubscribeStudentToCourse("s1", "c1")).futureValue
    val _ = result shouldBe Right(())
    allEvents(underlying).drop(2) shouldBe List(concurrentSubscription, StudentSubscribedToCourse("s1", "c1").toEvent)
