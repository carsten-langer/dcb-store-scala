package dcbstore.examples.invoicenumber

import dcbstore.decisionmodel.*
import dcbstore.decisionmodel.SingleProjection.handler
import dcbstore.examples.{*, given}
import dcbstore.{DcbStore, Event, Tags}

import scala.concurrent.{ExecutionContext, Future}

// The example https://dcb.events/examples/invoice-number/: invoices with unique numbers forming an unbroken sequence.
// With DCB, the projection of the next invoice number is untagged, so its query only filters by event type, and the
// append condition fails if any invoice was created in the meantime.
//
// The example's "better performance" variants, which read only the last InvoiceCreated event (backwards, with a limit
// of 1), are not possible with this library: DcbStore.read has no such options. Every decision reads all invoices.

type InvoiceNumber = Int

// The example's InvoiceData is an arbitrary object.
type InvoiceData = Map[String, String]

def invoiceTags(invoiceNumber: InvoiceNumber): Tags = Tags("invoice", invoiceNumber)


////////////////////////////////////////
// Events
////////////////////////////////////////

final case class InvoiceCreated(invoiceNumber: InvoiceNumber, invoiceData: InvoiceData) derives CanEqual:
  def toEvent: Event[EventType, InvoiceCreated] = event(this, invoiceTags(invoiceNumber))


////////////////////////////////////////
// Projections
////////////////////////////////////////

// Untagged, i.e. without tags its handler matches every InvoiceCreated event.
def nextInvoiceNumber = SingleProjection(
  internalState = 1,
  handlers = Map(
    handler(handlerF = (_: InvoiceNumber, e: InvoiceCreated) => e.invoiceNumber + 1),
  ),
)


////////////////////////////////////////
// Commands
////////////////////////////////////////

final case class CreateInvoice(invoiceData: InvoiceData)

/**
 * Handles the command: builds a decision model from a named tuple of projections and appends the resulting event with
 * the decision model's append condition. See [[appendIfAccepted]] for the outcomes; CreateInvoice is never rejected,
 * but fails if another invoice was created concurrently, and can then be retried with the then next number.
 */
final class InvoiceNumberCommandHandler(store: DcbStore[EventType, DataSuper])(using ExecutionContext):

  def handle(command: CreateInvoice): Future[Either[Rejected, Unit]] =
    import command.*
    store.buildDecisionModel((next = nextInvoiceNumber)).flatMap: (state, appendConditionO) =>
      val decision = Right(List(InvoiceCreated(state.next, invoiceData).toEvent))
      appendIfAccepted(store)(decision, appendConditionO)
