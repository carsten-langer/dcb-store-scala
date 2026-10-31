package scalatest.dcbstore.examples.preventrecordduplication

import dcbstore.examples.*
import dcbstore.examples.preventrecordduplication.*
import dcbstore.inmemdcbstore.{InMemAppendConditionFailed, InMemDcbStore}
import scalatest.dcbstore.examples.{ConcurrentWriterStore, ExampleSpec}

import scala.concurrent.ExecutionContext.Implicits.global

// The scenarios of https://dcb.events/examples/prevent-record-duplication/, plus the append condition at work.
class PreventRecordDuplicationSpec
  extends ExampleSpec(PreventRecordDuplicationCommandHandler(_)):

  "PlaceOrder" should "be rejected for a previously used idempotency token" in :
    val (result, newEvents) = scenario(
      OrderPlaced("o12345", idempotencyToken = "11111").toEvent,
    )(_.handle(PlaceOrder("o54321", idempotencyToken = "11111")))
    val _ = result shouldBe Left(Rejected("Order was already placed"))
    newEvents shouldBe empty

  it should "place an order with a new idempotency token" in :
    val (result, newEvents) = scenario(
      OrderPlaced("o12345", idempotencyToken = "11111").toEvent,
    )(_.handle(PlaceOrder("o54321", idempotencyToken = "22222")))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(OrderPlaced("o54321", idempotencyToken = "22222").toEvent)

  // The append condition at work: the command handler decides on the state it read, while a concurrent writer appends
  // its events just before the handler's append.

  it should "fail if a repeated request with the same idempotency token placed the order concurrently" in :
    val underlying = InMemDcbStore[EventType, DataSuper]()
    val concurrentOrder = OrderPlaced("o12345", idempotencyToken = "11111").toEvent
    val store = ConcurrentWriterStore(underlying, List(concurrentOrder))

    val failure = PreventRecordDuplicationCommandHandler(store).handle(PlaceOrder("o54321", "11111")).failed
    val _ = failure.futureValue shouldBe an[InMemAppendConditionFailed]
    allEvents(underlying) shouldBe List(concurrentOrder)

  it should "succeed despite an order with another idempotency token placed concurrently" in :
    val underlying = InMemDcbStore[EventType, DataSuper]()
    val concurrentOrder = OrderPlaced("o12345", idempotencyToken = "11111").toEvent
    val store = ConcurrentWriterStore(underlying, List(concurrentOrder))

    val result = PreventRecordDuplicationCommandHandler(store).handle(PlaceOrder("o54321", "22222")).futureValue
    val _ = result shouldBe Right(())
    allEvents(underlying) shouldBe List(concurrentOrder, OrderPlaced("o54321", idempotencyToken = "22222").toEvent)
