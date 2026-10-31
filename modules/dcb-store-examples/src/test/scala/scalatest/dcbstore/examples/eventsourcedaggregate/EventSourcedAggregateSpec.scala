package scalatest.dcbstore.examples.eventsourcedaggregate

import dcbstore.examples.*
import dcbstore.examples.eventsourcedaggregate.*
import dcbstore.inmemdcbstore.{InMemAppendConditionFailed, InMemDcbStore}
import dcbstore.SequencedEvent
import scalatest.dcbstore.examples.ExampleSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

// The usages shown on https://dcb.events/examples/event-sourced-aggregate/, which has no given/when/then scenarios,
// plus the aggregate's business rules and the repository's optimistic locking at work.
class EventSourcedAggregateSpec
  extends ExampleSpec(DcbCourseRepository(_)):

  // Loads the course, changes it, and saves it, like an application service would.
  private def update(repository: DcbCourseRepository, courseId: CourseId)
                    (change: repository.Course => Either[Rejected, repository.Course])
  : Future[Either[Rejected, Unit]] =
    repository.load(courseId).flatMap:
      case None => Future.successful(Left(Rejected(s"Course \"$courseId\" does not exist")))
      case Some(course) =>
        change(course) match
          case Left(rejected) => Future.successful(Left(rejected))
          case Right(changed) => repository.save(changed).map(Right(_))

  "A course aggregate" should "record the events of its creation and changes" in :
    val course = CourseAggregate.create[Long]("c1", "Course 01", capacity = 10).changeCapacity(15)
    course.map(_.recordedEvents) shouldBe Right(List(
      CourseDefined("c1", "Course 01", capacity = 10),
      CourseCapacityChanged("c1", newCapacity = 15),
    ))

  it should "be reconstituted from previously appended events" in :
    val sequencedEvents = List[SequencedEvent[EventType, DataSuper, Long]](
      SequencedEvent(CourseDefined("c1", "Course 01", capacity = 10).toEvent, 1L),
      SequencedEvent(CourseCapacityChanged("c1", newCapacity = 15).toEvent, 2L),
    )
    val course = CourseAggregate.reconstitute(sequencedEvents)
    val _ = course.map(_.version) shouldBe Some(Some(2L))
    val _ = course.map(_.recordedEvents) shouldBe Some(Nil)
    course.map(_.changeCapacity(15)) shouldBe Some(Left(Rejected("Course \"c1\" already has a capacity of \"15\"")))

  it should "not be reconstituted without events (not in the example)" in :
    CourseAggregate.reconstitute(List.empty[SequencedEvent[EventType, DataSuper, Long]]) shouldBe None

  it should "reject a student subscription if fully booked (not in the example)" in :
    val course = CourseAggregate.create[Long]("c1", "Course 01", capacity = 1).subscribeStudent("s1")
    course.flatMap(_.subscribeStudent("s2")) shouldBe Left(Rejected("Course \"c1\" is already fully booked"))

  it should "reject a capacity below its number of subscriptions (not in the example)" in :
    val course = CourseAggregate.create[Long]("c1", "Course 01", capacity = 2)
      .subscribeStudent("s1").flatMap(_.subscribeStudent("s2"))
    course.flatMap(_.changeCapacity(1)) shouldBe Left(Rejected(
      "Course \"c1\" already has 2 active subscriptions, can't set the capacity below that"
    ))

  "A DCB course repository" should "save a new course and update an existing one" in :
    val store = InMemDcbStore[EventType, DataSuper]()
    val repository = DcbCourseRepository(store)

    val _ = repository.save(CourseAggregate.create("c1", "Course 01", capacity = 10)).futureValue shouldBe ()
    val _ = update(repository, "c1")(_.changeCapacity(15)).futureValue shouldBe Right(())

    allEvents(store) shouldBe List(
      CourseDefined("c1", "Course 01", capacity = 10).toEvent,
      CourseCapacityChanged("c1", newCapacity = 15).toEvent,
    )

  it should "pass on a rejection, without appending (not in the example)" in :
    val (result, newEvents) = scenario(
      CourseDefined("c1", "Course 01", capacity = 10).toEvent,
    )(update(_, "c1")(_.changeCapacity(10)))
    val _ = result shouldBe Left(Rejected("Course \"c1\" already has a capacity of \"10\""))
    newEvents shouldBe empty

  it should "load no course that does not exist (not in the example)" in :
    DcbCourseRepository(InMemDcbStore()).load("c1").futureValue shouldBe None

  // Optimistic locking: the repository's append condition at work.

  it should "fail to save a course changed concurrently" in :
    val store = InMemDcbStore[EventType, DataSuper]()
    val repository = DcbCourseRepository(store)
    repository.save(CourseAggregate.create("c1", "Course 01", capacity = 10)).futureValue

    val course1 = repository.load("c1").futureValue.get
    val course2 = repository.load("c1").futureValue.get
    repository.save(course1.subscribeStudent("s1").toOption.get).futureValue

    val failure = repository.save(course2.changeCapacity(15).toOption.get).failed
    failure.futureValue shouldBe an[InMemAppendConditionFailed]

  it should "fail to save a new course if it already exists" in :
    val store = InMemDcbStore[EventType, DataSuper]()
    val repository = DcbCourseRepository(store)
    repository.save(CourseAggregate.create("c1", "Course 01", capacity = 10)).futureValue

    val failure = repository.save(CourseAggregate.create("c1", "Course 01 again", capacity = 20)).failed
    failure.futureValue shouldBe an[InMemAppendConditionFailed]

  it should "save a course despite another course changed concurrently" in :
    // Unlike a single stream for all courses, the query of a course only covers its own events.
    val store = InMemDcbStore[EventType, DataSuper]()
    val repository = DcbCourseRepository(store)
    repository.save(CourseAggregate.create("c1", "Course 01", capacity = 10)).futureValue
    repository.save(CourseAggregate.create("c2", "Course 02", capacity = 10)).futureValue

    val course1 = repository.load("c1").futureValue.get
    val _ = update(repository, "c2")(_.changeCapacity(20)).futureValue shouldBe Right(())

    val _ = repository.save(course1.changeCapacity(15).toOption.get).futureValue shouldBe ()
    allEvents(store).last shouldBe CourseCapacityChanged("c1", newCapacity = 15).toEvent
