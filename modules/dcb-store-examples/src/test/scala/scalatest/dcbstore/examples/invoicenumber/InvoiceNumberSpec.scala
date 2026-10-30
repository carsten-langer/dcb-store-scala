package scalatest.dcbstore.examples.invoicenumber

import dcbstore.examples.*
import dcbstore.examples.invoicenumber.*
import dcbstore.inmemdcbstore.{InMemAppendConditionFailed, InMemDcbStore}
import scalatest.dcbstore.examples.{ConcurrentWriterStore, ExampleSpec}

import scala.concurrent.ExecutionContext.Implicits.global

// The scenarios of https://dcb.events/examples/invoice-number/, plus the append condition at work.
class InvoiceNumberSpec
  extends ExampleSpec(InvoiceNumberCommandHandler(_)):

  "CreateInvoice" should "create the first invoice" in :
    val (result, newEvents) = scenario()(_.handle(CreateInvoice(Map("foo" -> "bar"))))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(InvoiceCreated(1, Map("foo" -> "bar")).toEvent)

  it should "create the second invoice" in :
    val (result, newEvents) = scenario(
      InvoiceCreated(1, Map("foo" -> "bar")).toEvent,
    )(_.handle(CreateInvoice(Map("bar" -> "baz"))))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(InvoiceCreated(2, Map("bar" -> "baz")).toEvent)

  // The append condition at work: the command handler decides on the state it read, while a concurrent writer appends
  // its events just before the handler's append.

  it should "fail if any invoice was created concurrently, and succeed with the next number when retried" in :
    val underlying = InMemDcbStore[EventType, DataSuper]()
    val concurrentInvoice = InvoiceCreated(1, Map("concurrent" -> "invoice")).toEvent
    val store = ConcurrentWriterStore(underlying, List(concurrentInvoice))
    val commandHandler = InvoiceNumberCommandHandler(store)

    val failure = commandHandler.handle(CreateInvoice(Map("foo" -> "bar"))).failed
    val _ = failure.futureValue shouldBe an[InMemAppendConditionFailed]

    val result = commandHandler.handle(CreateInvoice(Map("foo" -> "bar"))).futureValue
    val _ = result shouldBe Right(())
    allEvents(underlying) shouldBe List(concurrentInvoice, InvoiceCreated(2, Map("foo" -> "bar")).toEvent)
