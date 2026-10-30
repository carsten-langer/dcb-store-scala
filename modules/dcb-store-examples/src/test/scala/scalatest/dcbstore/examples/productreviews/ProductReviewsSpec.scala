package scalatest.dcbstore.examples.productreviews

import dcbstore.examples.*
import dcbstore.examples.productreviews.*
import dcbstore.inmemdcbstore.{InMemAppendConditionFailed, InMemDcbStore}
import scalatest.dcbstore.examples.{ConcurrentWriterStore, ExampleSpec}

import scala.concurrent.ExecutionContext.Implicits.global

// The scenarios of https://dcb.events/examples/product-reviews/ in its final form (feature 3), plus the append
// condition at work.
class ProductReviewsSpec
  extends ExampleSpec(ProductReviewsCommandHandler(_)):

  "ReviewProduct" should "be rejected for a product reviewed twice" in :
    val (result, newEvents) = scenario(
      OrderPlaced("c1", List(OrderLine("p1", quantity = 1))).toEvent,
      ProductReviewed("p1", "c1", rating = 4).toEvent,
    )(_.handle(ReviewProduct("p1", "c1", rating = 5)))
    val _ = result shouldBe Left(Rejected("Customer already reviewed this product"))
    newEvents shouldBe empty

  it should "review a product another customer has reviewed" in :
    val (result, newEvents) = scenario(
      OrderPlaced("c1", List(OrderLine("p1", quantity = 1))).toEvent,
      ProductReviewed("p1", "c2", rating = 4).toEvent,
    )(_.handle(ReviewProduct("p1", "c1", rating = 5)))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(ProductReviewed("p1", "c1", rating = 5).toEvent)

  it should "review another product" in :
    val (result, newEvents) = scenario(
      OrderPlaced("c1", List(OrderLine("p1", quantity = 1), OrderLine("p2", quantity = 2))).toEvent,
      ProductReviewed("p2", "c1", rating = 4).toEvent,
    )(_.handle(ReviewProduct("p1", "c1", rating = 5)))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(ProductReviewed("p1", "c1", rating = 5).toEvent)

  it should "be rejected for a product that was not bought" in :
    val (result, newEvents) = scenario()(_.handle(ReviewProduct("p1", "c1", rating = 5)))
    val _ = result shouldBe Left(Rejected("Only customers who bought the product can review it"))
    newEvents shouldBe empty

  it should "be rejected for a product only another customer bought" in :
    val (result, newEvents) = scenario(
      OrderPlaced("c2", List(OrderLine("p1", quantity = 1))).toEvent,
    )(_.handle(ReviewProduct("p1", "c1", rating = 5)))
    val _ = result shouldBe Left(Rejected("Only customers who bought the product can review it"))
    newEvents shouldBe empty

  it should "review a product the customer bought with other products" in :
    val (result, newEvents) = scenario(
      OrderPlaced("c1", List(OrderLine("p2", quantity = 1), OrderLine("p1", quantity = 3))).toEvent,
    )(_.handle(ReviewProduct("p1", "c1", rating = 5)))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(ProductReviewed("p1", "c1", rating = 5).toEvent)

  it should "be rejected for a delisted product" in :
    val (result, newEvents) = scenario(
      OrderPlaced("c1", List(OrderLine("p1", quantity = 1))).toEvent,
      ProductDelisted("p1").toEvent,
    )(_.handle(ReviewProduct("p1", "c1", rating = 5)))
    val _ = result shouldBe Left(Rejected("Product was delisted"))
    newEvents shouldBe empty

  it should "not be created with a rating outside of 1 to 5" in :
    val _ = an[IllegalArgumentException] should be thrownBy ReviewProduct("p1", "c1", rating = 0)
    an[IllegalArgumentException] should be thrownBy ReviewProduct("p1", "c1", rating = 6)

  "DelistProduct" should "be rejected for a product delisted twice" in :
    val (result, newEvents) = scenario(ProductDelisted("p1").toEvent)(_.handle(DelistProduct("p1")))
    val _ = result shouldBe Left(Rejected("Product was delisted"))
    newEvents shouldBe empty

  it should "delist a product that was reviewed" in :
    val (result, newEvents) = scenario(
      ProductReviewed("p1", "c1", rating = 4).toEvent,
    )(_.handle(DelistProduct("p1")))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(ProductDelisted("p1").toEvent)

  // The append condition at work: the command handler decides on the state it read, while a concurrent writer appends
  // its events just before the handler's append.

  "ReviewProduct" should "fail if the product was delisted concurrently" in :
    val underlying = InMemDcbStore[EventType, DataSuper]()
    underlying.append(List(OrderPlaced("c1", List(OrderLine("p1", quantity = 1))).toEvent)).futureValue
    val store = ConcurrentWriterStore(underlying, List(ProductDelisted("p1").toEvent))

    val failure = ProductReviewsCommandHandler(store).handle(ReviewProduct("p1", "c1", rating = 5)).failed
    failure.futureValue shouldBe an[InMemAppendConditionFailed]

  it should "fail if the customer reviewed the product concurrently" in :
    val underlying = InMemDcbStore[EventType, DataSuper]()
    underlying.append(List(OrderPlaced("c1", List(OrderLine("p1", quantity = 1))).toEvent)).futureValue
    val store = ConcurrentWriterStore(underlying, List(ProductReviewed("p1", "c1", rating = 3).toEvent))

    val failure = ProductReviewsCommandHandler(store).handle(ReviewProduct("p1", "c1", rating = 5)).failed
    failure.futureValue shouldBe an[InMemAppendConditionFailed]

  it should "succeed despite concurrent reviews of the same product by another customer and of another product" in :
    // Outside the consistency boundary of the decision, so no conflict.
    val underlying = InMemDcbStore[EventType, DataSuper]()
    underlying.append(List(
      OrderPlaced("c1", List(OrderLine("p1", quantity = 1), OrderLine("p2", quantity = 1))).toEvent,
      OrderPlaced("c2", List(OrderLine("p1", quantity = 1))).toEvent,
    )).futureValue
    val concurrentReviews = List(
      ProductReviewed("p1", "c2", rating = 3).toEvent,
      ProductReviewed("p2", "c1", rating = 2).toEvent,
    )
    val store = ConcurrentWriterStore(underlying, concurrentReviews)

    val result = ProductReviewsCommandHandler(store).handle(ReviewProduct("p1", "c1", rating = 5)).futureValue
    val _ = result shouldBe Right(())
    allEvents(underlying).drop(2) shouldBe concurrentReviews :+ ProductReviewed("p1", "c1", rating = 5).toEvent
