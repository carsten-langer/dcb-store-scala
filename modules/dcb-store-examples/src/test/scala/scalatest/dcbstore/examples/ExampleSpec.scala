package scalatest.dcbstore.examples

import dcbstore.examples.*
import dcbstore.inmemdcbstore.InMemDcbStore
import dcbstore.{DcbStore, Event, Query}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future

// The base of the examples' specs, to write the given/when/then scenarios of the examples' pages.
// Takes a factory for the example's command handler on a given store.
abstract class ExampleSpec[CommandHandler](newCommandHandler: DcbStore[EventType, DataSuper] => CommandHandler)
  extends AnyFlatSpec
    with Matchers
    with ScalaFutures
    with IntegrationPatience:

  // Event does not derive CanEqual in dcb-store, so under strictEquality comparing events needs it given here. The
  // read events have the data type DataSuper, the expected ones their specific data type.
  protected given [Data1 <: DataSuper, Data2 <: DataSuper] => CanEqual[Event[EventType, Data1], Event[EventType, Data2]] =
    CanEqual.derived

  /**
   * Given the events in a fresh in-memory store, when the command is handled by a command handler on that store, then
   * its result and the newly appended events. E.g.
   * {{{
   *   val (result, newEvents) = scenario(CourseDefined("c1", 10).toEvent)(_.handle(DefineCourse("c1", 15)))
   * }}}
   */
  protected def scenario(givenEvents: Event[EventType, DataSuper]*)
                        (when: CommandHandler => Future[Either[Rejected, Unit]])
  : (result: Either[Rejected, Unit], newEvents: List[Event[EventType, DataSuper]]) =
    val store = InMemDcbStore[EventType, DataSuper]()
    store.append(givenEvents.toList).futureValue
    val result = when(newCommandHandler(store)).futureValue
    (result = result, newEvents = allEvents(store).drop(givenEvents.size))

  protected def allEvents(store: DcbStore[EventType, DataSuper]): List[Event[EventType, DataSuper]] =
    store.read(Query.all).futureValue.iterator.map(_.event).toList
