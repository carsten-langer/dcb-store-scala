package cucumber.dcbstore

import dcbstore.*
import io.cucumber.datatable.DataTable
import io.cucumber.scala.Implicits.ScalaDataTable
import io.cucumber.scala.{EN, ScalaDsl}
import org.scalatest.TryValues
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers

import scala.compiletime.uninitialized
import scala.concurrent.Future
import scala.util.Try

// In the Cucumber tests, both event type values and data values are Strings from the feature file. Thus, a DcbStore
// usable to run these tests must support Strings for both EventType and DataSuper.
type EventType = String
type DataSuper = String

// These StepDefinitions need to be an abstract class instead of a trait, so that the value dcbStore is known/stable
// during construction time. This is required as parts of this class which are themselves not just defs, e.g. the var
// definitions, and some "When" steps, depend directly or indirectly on the type dcbStore.SequencePosition.
// As they depend at construction time on the type dcbStore.SequencePosition, it must be stable when this class is
// constructed. Therefore, dcbStore itself cannot just be a def or lazy val, which would only be known after the class
// is constructed.
// As a consequence of DcbStoreStepDefinitions not being a trait but an abstract class, also EventType and DataSuper
// must be fixed outside the class. They also cannot be opaque, as otherwise their concrete class types would not be
// known to DcbStoreStepDefinitions.
abstract class DcbStoreStepDefinitions(dcbStore: DcbStore[EventType, DataSuper])
  extends ScalaDsl
    with EN
    with ScalaFutures
    with IntegrationPatience
    with Matchers
    with TryValues:

  // This test suite actually stores the name of the test event row in the event data field and drops the data field.
  // I reflect this by setting the type of "name" to be "Data", and the type of "data" to be "Any".
  private case class TestEventRow(name: DataSuper, eventType: EventType, tags: Tags, data: Any)

  private var lastReadResult: Try[Seq[SequencedEvent[EventType, DataSuper, dcbStore.SequencePosition]]] = uninitialized
  private var lastAppendAttempt: Future[Unit] = uninitialized

  private def parseTag(value: String): Tag =
    val parts = value.trim.split(":", 2)
    val _ = parts.length shouldEqual 2
    Tag(parts(0), parts(1))

  private def parseTags(value: String): Tags =
    Option(value) // convert to Option such that after the trim and _.nonEmpty an empty String is considered a no tag
      .map(_.trim)
      .filter(_.nonEmpty)
      .map: tagsString =>
        tagsString
          .split(",")
          .map(parseTag)
          .toSet
      .getOrElse(Tags.empty)

  // This test suite actually stores the name of the test event row in the event data field and drops the data field.
  // This makes reading the feature file easy but does not test if the data is kept in the event store.
  private def eventFromRow(row: TestEventRow): Event[EventType, DataSuper] = Event(row.eventType, row.name, row.tags)

  private def eventRowsFromDataTable(dataTable: DataTable): Seq[TestEventRow] =
    dataTable
      .asScalaRawMaps[String, String]
      .map: row =>
        TestEventRow(
          name = row("name"),
          eventType = row("type"),
          tags = parseTags(row("tags")),
          data = row("data"),
        )

  // As the name is used as data to simplify the tests, I already note here the type of "name" to be "Data".
  private def expectedNamesFromDataTable(dataTable: DataTable): Seq[DataSuper] =
    dataTable
      .asScalaRawMaps[String, String]
      .map(_("name"))

  private def queryForEventType(eventType: EventType): Query[EventType] =
    Query(
      QueryItem(
        eventTypes = Set(eventType),
        tags = Tags.empty,
      )
    )

  private def queryForTag(tag: String): Query[EventType] =
    Query(
      QueryItem(
        eventTypes = Set.empty,
        tags = Tags(parseTag(tag)),
      )
    )

  private def queryForEventTypeAndTag(eventType: EventType, tag: String): Query[EventType] =
    Query(
      QueryItem(
        eventTypes = Set(eventType),
        tags = Tags(parseTag(tag)),
      )
    )

  private def queryForTags(tags: Seq[String]): Query[EventType] =
    Query(
      QueryItem(
        eventTypes = Set.empty,
        tags = tags.map(parseTag).toSet,
      )
    )

  private def tryAppendEvents(
                               eventRows: Seq[TestEventRow],
                               appendConditionO: Option[AppendCondition[EventType, dcbStore.SequencePosition]],
                             ): Unit =
    lastAppendAttempt = dcbStore.append(eventRows.map(eventFromRow), appendConditionO)

  private def sequencePositionOfEventMatching(query: Query[EventType]): dcbStore.SequencePosition =
    val matchingEvents = dcbStore.read(query).futureValue.iterator.toSeq
    val _ = matchingEvents should have size 1 // because the tests are designed this way
    matchingEvents.head.sequencePosition

  private def read(query: Query[EventType]): Unit =
    val eventualIterableOnce = dcbStore.read(query)
    lastReadResult = Try(eventualIterableOnce.futureValue.iterator.toSeq)

  //////////////////////////////
  // Givens
  //////////////////////////////

  Given("an empty DCB store"): () =>
    // Cucumber creates a new instance of each glue class per scenario.
    // Concrete implementations should provide a fresh empty store.
    ()

  Given("the following events have been appended:"): (dataTable: DataTable) =>
    val rows = eventRowsFromDataTable(dataTable)
    dcbStore.append(events = rows.map(eventFromRow), appendConditionO = None).futureValue

  //////////////////////////////
  // Whens
  //////////////////////////////

  When("I append the following events without an append condition:"): (dataTable: DataTable) =>
    val rows = eventRowsFromDataTable(dataTable)
    tryAppendEvents(rows, appendConditionO = None)

  When("I append the following events with append condition matching tag {string}:"):
    (tag: String, dataTable: DataTable) =>
      val rows = eventRowsFromDataTable(dataTable)
      val appendConditionO: Option[AppendCondition[EventType, dcbStore.SequencePosition]] =
        Some(AppendCondition(
          failIfEventsMatch = queryForTag(tag),
          afterO = None,
        ))
      tryAppendEvents(rows, appendConditionO)

  When("I append the following events with append condition matching tag {string} after event matching event type {string} and tag {string}:"):
    (conditionTag: String, afterEventType: String, afterTag: String, dataTable: DataTable) =>
      val rows = eventRowsFromDataTable(dataTable)
      val appendConditionO =
        Some(AppendCondition(
          failIfEventsMatch = queryForTag(conditionTag),
          afterO = Some(sequencePositionOfEventMatching(queryForEventTypeAndTag(afterEventType, afterTag))),
        ))
      tryAppendEvents(rows, appendConditionO)

  When("I read all events"): () =>
    read(Query.all)

  When("I read events matching event type {string}"): (eventType: String) =>
    read(queryForEventType(eventType))

  When("I read events matching tag {string}"): (tag: String) =>
    read(queryForTag(tag))

  When("I read events matching event type {string} and tag {string}"): (eventType: String, tag: String) =>
    read(queryForEventTypeAndTag(eventType, tag))

  When("I read events matching tags:"): (dataTable: DataTable) =>
    val tags =
      dataTable
        .asScalaRawMaps[String, String]
        .map(_("tag"))
    read(queryForTags(tags))

  When("I read events matching any of the following query items:"): (dataTable: DataTable) =>
    val queryItems =
      dataTable
        .asScalaMaps[String, String]
        .toSet
        .map: row =>
          val eventTypes =
            row("eventTypes")
              .map(_.trim)
              .filter(_.nonEmpty)
              .map: eventTypesString =>
                eventTypesString
                  .split(",")
                  .map(_.trim)
                  .filter(_.nonEmpty)
                  .toSet
              .getOrElse(Set.empty)
          val tags =
            row("tags")
              .map(parseTags)
              .getOrElse(Tags.empty)
          QueryItem(eventTypes, tags)
    read(Query(queryItems))

  //////////////////////////////
  // Thens
  //////////////////////////////

  Then("the read result should contain {int} events"): (expectedCount: Int) =>
    lastReadResult.success.value.size shouldEqual expectedCount

  Then("the read result should contain the following events in order:"): (dataTable: DataTable) =>
    val expectedNames = expectedNamesFromDataTable(dataTable)
    // Retrieves the name of the test event row from the event data field, see above for creating the event accordingly.
    val actualNames = lastReadResult.success.value.map(_.event.data)
    actualNames should contain theSameElementsInOrderAs expectedNames

  Then("the append should succeed"): () =>
    lastAppendAttempt.futureValue

  Then("the append should fail"): () =>
    lastAppendAttempt.failed.futureValue
