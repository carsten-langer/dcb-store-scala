package dcbstore.examples.productreviews

import dcbstore.decisionmodel.*
import dcbstore.decisionmodel.SingleProjection.handler
import dcbstore.examples.{*, given}
import dcbstore.{DcbStore, Event, Tags}

import scala.concurrent.{ExecutionContext, Future}

// The example https://dcb.events/examples/product-reviews/, in its final form with all three features: a customer can
// review a product only once, only if they bought it, and only as long as it is listed.
// With DCB, the relation between product and customer needs no entity of its own: events about the relation carry the
// tags of both, and a projection declaring both tags only sees the events carrying both, as the tags of a query item
// are combined with AND.

type ProductId = String
type CustomerId = String

// 1 to 5, see ReviewProduct.
type Rating = Int

def productTags(productId: ProductId): Tags = Tags("product", productId)

def customerTags(customerId: CustomerId): Tags = Tags("customer", customerId)

def productAndCustomerTags(productId: ProductId, customerId: CustomerId): Tags =
  Tags(productTags(productId), customerTags(customerId))


////////////////////////////////////////
// Events
////////////////////////////////////////

final case class ProductReviewed(productId: ProductId, customerId: CustomerId, rating: Rating) derives CanEqual:
  def toEvent: Event[EventType, ProductReviewed] = event(this, productAndCustomerTags(productId, customerId))

final case class OrderLine(productId: ProductId, quantity: Int) derives CanEqual

// Tagged with the customer and each product it contains.
final case class OrderPlaced(customerId: CustomerId, lines: List[OrderLine]) derives CanEqual:
  def toEvent: Event[EventType, OrderPlaced] =
    event(this, Tags(customerTags(customerId) :: lines.map(line => productTags(line.productId))*))

final case class ProductDelisted(productId: ProductId) derives CanEqual:
  def toEvent: Event[EventType, ProductDelisted] = event(this, productTags(productId))


////////////////////////////////////////
// Projections
////////////////////////////////////////

def productReviewedByCustomer(productId: ProductId, customerId: CustomerId) = SingleProjection(
  internalState = false,
  handlers = Map(
    handler(handlerF = (_: Boolean, _: ProductReviewed) => true, tags = productAndCustomerTags(productId, customerId)),
  ),
)

def productBoughtByCustomer(productId: ProductId, customerId: CustomerId) = SingleProjection(
  internalState = false,
  handlers = Map(
    handler(handlerF = (_: Boolean, _: OrderPlaced) => true, tags = productAndCustomerTags(productId, customerId)),
  ),
)

def productListed(productId: ProductId) = SingleProjection(
  internalState = true,
  handlers = Map(
    handler(handlerF = (_: Boolean, _: ProductDelisted) => false, tags = productTags(productId)),
  ),
)


////////////////////////////////////////
// Commands
////////////////////////////////////////

// The example's Rating type is limited to 1 to 5. As a type constraint rather than a business rule, it is checked when
// creating the command, not by rejecting it.
final case class ReviewProduct(productId: ProductId, customerId: CustomerId, rating: Rating):
  require(1 <= rating && rating <= 5, s"Rating must be between 1 and 5, but is $rating")

final case class DelistProduct(productId: ProductId)

/**
 * Handles the commands: builds a decision model from a named tuple of projections, checks the business rules on its
 * typed state, and appends the resulting events with the decision model's append condition. See [[appendIfAccepted]]
 * for the outcomes.
 *
 * ReviewProduct decides on projections with different tags. Its consistency boundary is the combination of their query
 * items: the orders and reviews of this customer and product, and the delisting of the product. Reviews of other
 * customers, or of other products, do not affect it.
 */
final class ProductReviewsCommandHandler(store: DcbStore[EventType, DataSuper])(using ExecutionContext):

  def handle(command: ReviewProduct): Future[Either[Rejected, Unit]] =
    import command.*
    val projections = (
      listed = productListed(productId),
      bought = productBoughtByCustomer(productId, customerId),
      reviewed = productReviewedByCustomer(productId, customerId),
    )
    store.buildDecisionModel(projections).flatMap: (state, appendConditionO) =>
      val decision =
        if !state.listed then Left(Rejected("Product was delisted"))
        else if !state.bought then Left(Rejected("Only customers who bought the product can review it"))
        else if state.reviewed then Left(Rejected("Customer already reviewed this product"))
        else Right(List(ProductReviewed(productId, customerId, rating).toEvent))
      appendIfAccepted(store)(decision, appendConditionO)

  def handle(command: DelistProduct): Future[Either[Rejected, Unit]] =
    import command.*
    store.buildDecisionModel((listed = productListed(productId))).flatMap: (state, appendConditionO) =>
      val decision =
        if !state.listed then Left(Rejected("Product was delisted"))
        else Right(List(ProductDelisted(productId).toEvent))
      appendIfAccepted(store)(decision, appendConditionO)
