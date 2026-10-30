package dcbstore.examples.dynamicproductprice

import dcbstore.decisionmodel.*
import dcbstore.decisionmodel.SingleProjection.handler
import dcbstore.examples.{*, given}
import dcbstore.{DcbStore, Event, Tags}

import scala.concurrent.{ExecutionContext, Future}

// The example https://dcb.events/examples/dynamic-product-price/, in its final form with all three features: orders
// must use the displayed prices, which are valid if they are current or were changed within a grace period of 10
// minutes, and a shopping cart orders several products at once.
// The point of this example: the number of products, and thus of projections, is only known at runtime. This is where
// a named tuple does not fit, but an Iterable of projections of one state type does, see OrderProducts.

type ProductId = String

// The example's Money is a number of at least 0.
type Money = BigDecimal

// As in the example, the time is part of the data: the events carry the minute they were recorded at, and the command
// is passed the current minute. Typically, this would be a timestamp in the event's payload or metadata.
type Minute = Int

val GracePeriodMinutes = 10

def productTags(productId: ProductId): Tags = Tags("product", productId)


////////////////////////////////////////
// Events
////////////////////////////////////////

final case class ProductDefined(productId: ProductId, price: Money, at: Minute) derives CanEqual:
  def toEvent: Event[EventType, ProductDefined] = event(this, productTags(productId))

final case class ProductPriceChanged(productId: ProductId, newPrice: Money, at: Minute) derives CanEqual:
  def toEvent: Event[EventType, ProductPriceChanged] = event(this, productTags(productId))

final case class Item(productId: ProductId, price: Money) derives CanEqual

// Tagged with each product it contains.
final case class ProductsOrdered(items: List[Item]) derives CanEqual:
  def toEvent: Event[EventType, ProductsOrdered] = event(this, Tags(items.map(item => productTags(item.productId))*))


////////////////////////////////////////
// Projections
////////////////////////////////////////

// The prices of the product valid now: the current one, and those changed within the grace period. The example's
// scripted projection with the parameter now.
def productPrices(productId: ProductId, now: Minute) = SingleProjection(
  internalState = List.empty[Money],
  handlers = Map(
    handler(handlerF = (_: List[Money], e: ProductDefined) => List(e.price), tags = productTags(productId)),
    handler(
      handlerF = (prices: List[Money], e: ProductPriceChanged) =>
        if now - e.at <= GracePeriodMinutes then prices :+ e.newPrice else List(e.newPrice),
      tags = productTags(productId),
    ),
  ),
)


////////////////////////////////////////
// Commands
////////////////////////////////////////

final case class OrderProducts(items: List[Item], now: Minute)

/**
 * Handles the command: builds a decision model from an Iterable of projections, one per item in the cart, checks each
 * displayed price against the valid prices of its product, and appends the resulting event with the decision model's
 * append condition. See [[appendIfAccepted]] for the outcomes.
 *
 * The append condition has one query item per ordered product, so if the price of any of them changes in the meantime,
 * the whole order fails. Orders of the same products by others do not affect it.
 */
final class DynamicProductPriceCommandHandler(store: DcbStore[EventType, DataSuper])(using ExecutionContext):

  def handle(command: OrderProducts): Future[Either[Rejected, Unit]] =
    import command.*
    val projections: List[Projection[List[Money], EventType]] = items.map(item => productPrices(item.productId, now))
    store.buildDecisionModel(projections).flatMap: (state, appendConditionO) =>
      // The final state has one entry per projection, in the order of the projections, i.e. of the items.
      val allPricesValid = items.lazyZip(state).forall((item, validPrices) => validPrices.contains(item.price))
      val decision =
        if !allPricesValid then Left(Rejected("Price is no longer valid"))
        else Right(List(ProductsOrdered(items).toEvent))
      appendIfAccepted(store)(decision, appendConditionO)
