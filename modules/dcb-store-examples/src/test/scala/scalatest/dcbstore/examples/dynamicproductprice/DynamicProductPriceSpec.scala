package scalatest.dcbstore.examples.dynamicproductprice

import dcbstore.Event
import dcbstore.examples.*
import dcbstore.examples.dynamicproductprice.*
import dcbstore.inmemdcbstore.{InMemAppendConditionFailed, InMemDcbStore}
import scalatest.dcbstore.examples.{ConcurrentWriterStore, ExampleSpec}

import scala.concurrent.ExecutionContext.Implicits.global

// The scenarios of https://dcb.events/examples/dynamic-product-price/ in its final form (feature 3), plus the append
// condition at work.
class DynamicProductPriceSpec
  extends ExampleSpec(DynamicProductPriceCommandHandler(_)):

  "OrderProducts" should "be rejected for a displayed price that was never valid" in :
    val (result, newEvents) = scenario(
      ProductDefined("p1", price = 123, at = 80).toEvent,
    )(_.handle(OrderProducts(List(Item("p1", price = 100)), now = 100)))
    val _ = result shouldBe Left(Rejected("Price is no longer valid"))
    newEvents shouldBe empty

  it should "be rejected for a price that was changed more than 10 minutes ago" in :
    val (result, newEvents) = scenario(
      ProductDefined("p1", price = 123, at = 80).toEvent,
      ProductPriceChanged("p1", newPrice = 134, at = 80).toEvent,
    )(_.handle(OrderProducts(List(Item("p1", price = 123)), now = 100)))
    val _ = result shouldBe Left(Rejected("Price is no longer valid"))
    newEvents shouldBe empty

  it should "order a product with its initial valid price" in :
    val (result, newEvents) = scenario(
      ProductDefined("p1", price = 123, at = 80).toEvent,
    )(_.handle(OrderProducts(List(Item("p1", price = 123)), now = 100)))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(ProductsOrdered(List(Item("p1", price = 123))).toEvent)

  it should "order a product with a price that was changed less than 10 minutes ago" in :
    val (result, newEvents) = scenario(
      ProductDefined("p1", price = 123, at = 80).toEvent,
      ProductPriceChanged("p1", newPrice = 134, at = 91).toEvent,
    )(_.handle(OrderProducts(List(Item("p1", price = 123)), now = 100)))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(ProductsOrdered(List(Item("p1", price = 123))).toEvent)

  it should "order multiple products with valid prices" in :
    val items = List(Item("p1", price = 123), Item("p2", price = 321))
    val (result, newEvents) = scenario(
      ProductDefined("p1", price = 123, at = 80).toEvent,
      ProductPriceChanged("p1", newPrice = 134, at = 91).toEvent,
      ProductDefined("p2", price = 321, at = 92).toEvent,
    )(_.handle(OrderProducts(items, now = 100)))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(ProductsOrdered(items).toEvent)

  it should "be rejected if only one of multiple products has an invalid price (not in the example)" in :
    val (result, newEvents) = scenario(
      ProductDefined("p1", price = 123, at = 80).toEvent,
      ProductDefined("p2", price = 321, at = 92).toEvent,
    )(_.handle(OrderProducts(List(Item("p1", price = 123), Item("p2", price = 300)), now = 100)))
    val _ = result shouldBe Left(Rejected("Price is no longer valid"))
    newEvents shouldBe empty

  // The append condition at work: the command handler decides on the state it read, while a concurrent writer appends
  // its events just before the handler's append.

  private def twoProducts(): InMemDcbStore[EventType, DataSuper] =
    val underlying = InMemDcbStore[EventType, DataSuper]()
    underlying.append(List(
      ProductDefined("p1", price = 123, at = 80).toEvent,
      ProductDefined("p2", price = 321, at = 92).toEvent,
    )).futureValue
    underlying

  private def cart = OrderProducts(List(Item("p1", price = 123), Item("p2", price = 321)), now = 100)

  it should "fail if the price of any of its products was changed concurrently" in :
    val store = ConcurrentWriterStore(twoProducts(), List(ProductPriceChanged("p2", newPrice = 333, at = 100).toEvent))

    val failure = DynamicProductPriceCommandHandler(store).handle(cart).failed
    failure.futureValue shouldBe an[InMemAppendConditionFailed]

  it should "succeed despite concurrent orders of the same products and a price change of another product" in :
    // Outside the consistency boundary of the decision, so no conflict.
    val underlying = twoProducts()
    val store = ConcurrentWriterStore(underlying, List[Event[EventType, DataSuper]](
      ProductsOrdered(List(Item("p1", price = 123), Item("p2", price = 321))).toEvent,
      ProductPriceChanged("p3", newPrice = 1, at = 100).toEvent,
    ))

    val result = DynamicProductPriceCommandHandler(store).handle(cart).futureValue
    val _ = result shouldBe Right(())
    allEvents(underlying).last shouldBe ProductsOrdered(cart.items).toEvent
