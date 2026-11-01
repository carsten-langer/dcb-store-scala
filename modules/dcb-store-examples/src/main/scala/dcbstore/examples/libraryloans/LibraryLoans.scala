package dcbstore.examples.libraryloans

import dcbstore.decisionmodel.*
import dcbstore.decisionmodel.SingleProjection.handler
import dcbstore.examples.{*, given}
import dcbstore.{AppendCondition, DcbStore, Event, Query, Tags}

import scala.concurrent.{ExecutionContext, Future}

// The example https://dcb.events/examples/library-loans/, in its final form with all three features: lending and
// returning copies of books, renewing loans at most twice and not for a suspended borrower, and not while the book is
// reserved.
// The point of this example: a loan is renewed by scanning the copy, so RenewLoan only knows the copy. The borrower and
// the book follow from the copy's state, and only then their states can be read. See RenewLoan for how this chain of
// reads still ends in a single consistency boundary.

type CopyId = String
type BookId = String
type MemberId = String

def copyTags(copyId: CopyId): Tags = Tags("copy", copyId)

def bookTags(bookId: BookId): Tags = Tags("book", bookId)

def memberTags(memberId: MemberId): Tags = Tags("member", memberId)


////////////////////////////////////////
// Events
////////////////////////////////////////

final case class CopyAddedToCatalog(copyId: CopyId, bookId: BookId) derives CanEqual:
  def toEvent: Event[EventType, CopyAddedToCatalog] = event(this, Tags(copyTags(copyId), bookTags(bookId)))

final case class CopyLent(copyId: CopyId, memberId: MemberId) derives CanEqual:
  def toEvent: Event[EventType, CopyLent] = event(this, Tags(copyTags(copyId), memberTags(memberId)))

final case class CopyReturned(copyId: CopyId, memberId: MemberId) derives CanEqual:
  def toEvent: Event[EventType, CopyReturned] = event(this, Tags(copyTags(copyId), memberTags(memberId)))

final case class LoanRenewed(copyId: CopyId) derives CanEqual:
  def toEvent: Event[EventType, LoanRenewed] = event(this, copyTags(copyId))

final case class MemberSuspended(memberId: MemberId) derives CanEqual:
  def toEvent: Event[EventType, MemberSuspended] = event(this, memberTags(memberId))

final case class MemberReinstated(memberId: MemberId) derives CanEqual:
  def toEvent: Event[EventType, MemberReinstated] = event(this, memberTags(memberId))

final case class BookReserved(bookId: BookId, memberId: MemberId) derives CanEqual:
  def toEvent: Event[EventType, BookReserved] = event(this, Tags(bookTags(bookId), memberTags(memberId)))

final case class ReservationCancelled(bookId: BookId, memberId: MemberId) derives CanEqual:
  def toEvent: Event[EventType, ReservationCancelled] = event(this, Tags(bookTags(bookId), memberTags(memberId)))


////////////////////////////////////////
// Projections
////////////////////////////////////////
// The example's null values are represented by None.

def copyInCatalog(copyId: CopyId) = SingleProjection(
  internalState = false,
  handlers = Map(
    handler(handlerF = (_: Boolean, _: CopyAddedToCatalog) => true, tags = copyTags(copyId)),
  ),
)

def copyBookId(copyId: CopyId) = SingleProjection(
  internalState = Option.empty[BookId],
  handlers = Map(
    handler(handlerF = (_: Option[BookId], e: CopyAddedToCatalog) => Some(e.bookId), tags = copyTags(copyId)),
  ),
)

def copyBorrowerId(copyId: CopyId) = SingleProjection(
  internalState = Option.empty[MemberId],
  handlers = Map(
    handler(handlerF = (_: Option[MemberId], e: CopyLent) => Some(e.memberId), tags = copyTags(copyId)),
    handler(handlerF = (_: Option[MemberId], _: CopyReturned) => None, tags = copyTags(copyId)),
  ),
)

def copyRenewals(copyId: CopyId) = SingleProjection(
  internalState = 0,
  handlers = Map(
    handler(handlerF = (_: Int, _: CopyLent) => 0, tags = copyTags(copyId)),
    handler(handlerF = (count: Int, _: LoanRenewed) => count + 1, tags = copyTags(copyId)),
  ),
)

def memberLoanCount(memberId: MemberId) = SingleProjection(
  internalState = 0,
  handlers = Map(
    handler(handlerF = (count: Int, _: CopyLent) => count + 1, tags = memberTags(memberId)),
    handler(handlerF = (count: Int, _: CopyReturned) => count - 1, tags = memberTags(memberId)),
  ),
)

def memberIsSuspended(memberId: MemberId) = SingleProjection(
  internalState = false,
  handlers = Map(
    handler(handlerF = (_: Boolean, _: MemberSuspended) => true, tags = memberTags(memberId)),
    handler(handlerF = (_: Boolean, _: MemberReinstated) => false, tags = memberTags(memberId)),
  ),
)

// The members who reserved the book.
def bookReservations(bookId: BookId) = SingleProjection(
  internalState = Set.empty[MemberId],
  handlers = Map(
    handler(handlerF = (members: Set[MemberId], e: BookReserved) => members + e.memberId, tags = bookTags(bookId)),
    handler(
      handlerF = (members: Set[MemberId], e: ReservationCancelled) => members - e.memberId,
      tags = bookTags(bookId),
    ),
  ),
)


////////////////////////////////////////
// Commands
////////////////////////////////////////

final case class AddCopyToCatalog(copyId: CopyId, bookId: BookId)

final case class LendCopy(copyId: CopyId, memberId: MemberId)

final case class ReturnCopy(copyId: CopyId, memberId: MemberId)

final case class RenewLoan(copyId: CopyId)

final case class SuspendMember(memberId: MemberId)

final case class ReinstateMember(memberId: MemberId)

final case class ReserveBook(bookId: BookId, memberId: MemberId)

final case class CancelReservation(bookId: BookId, memberId: MemberId)

val MaxLoansPerMember = 3

val MaxRenewalsPerLoan = 2

/**
 * Handles the commands: builds a decision model from a named tuple of projections, checks the business rules on its
 * typed state, and appends the resulting events with the decision model's append condition. See [[appendIfAccepted]]
 * for the outcomes. RenewLoan reads in two steps, see there.
 */
final class LibraryLoansCommandHandler(store: DcbStore[EventType, DataSuper])(using ExecutionContext):

  def handle(command: AddCopyToCatalog): Future[Either[Rejected, Unit]] =
    import command.*
    store.buildDecisionModel((inCatalog = copyInCatalog(copyId))).flatMap: (state, appendConditionO) =>
      val decision =
        if state.inCatalog then Left(Rejected("Copy is already in the catalog"))
        else Right(List(CopyAddedToCatalog(copyId, bookId).toEvent))
      appendIfAccepted(store)(decision, appendConditionO)

  def handle(command: LendCopy): Future[Either[Rejected, Unit]] =
    import command.*
    val projections = (
      inCatalog = copyInCatalog(copyId),
      borrowerId = copyBorrowerId(copyId),
      memberLoanCount = memberLoanCount(memberId),
    )
    store.buildDecisionModel(projections).flatMap: (state, appendConditionO) =>
      val decision =
        if !state.inCatalog then Left(Rejected("Copy is not in the catalog"))
        else if state.borrowerId.nonEmpty then Left(Rejected("Copy is not available"))
        else if state.memberLoanCount >= MaxLoansPerMember then Left(Rejected("Member already borrows three copies"))
        else Right(List(CopyLent(copyId, memberId).toEvent))
      appendIfAccepted(store)(decision, appendConditionO)

  def handle(command: ReturnCopy): Future[Either[Rejected, Unit]] =
    import command.*
    store.buildDecisionModel((borrowerId = copyBorrowerId(copyId))).flatMap: (state, appendConditionO) =>
      val decision =
        if state.borrowerId.isEmpty then Left(Rejected("Copy is not lent"))
        else if !state.borrowerId.contains(memberId) then Left(Rejected("Copy is lent to another member"))
        else Right(List(CopyReturned(copyId, memberId).toEvent))
      appendIfAccepted(store)(decision, appendConditionO)

  /**
   * RenewLoan only knows the copy, but its rules are also about the borrower and the book, which follow from the copy's
   * state. It therefore reads in two steps:
   *
   *  1. A read model of the copy yields the borrower and the book. If there is no borrower, the copy is not lent.
   *  1. A decision model of all projections the rules need, for the copy, the borrower and the book, yields the state
   *     to decide on and a single append condition covering all three. A suspension of another member, or a
   *     reservation of another book, is outside of it.
   *
   * The two reads are not atomic: the copy may be returned, or even lent again, in between. The append condition
   * cannot detect this, as it only covers events after the second read. So the second step reads the copy's borrower
   * again and rejects the renewal if it is no longer the borrower of the first step: the loan to be renewed has ended.
   * The copy's book needs no such check, as it never changes: AddCopyToCatalog rejects a copy already in the catalog.
   * The example on dcb.events reads in one go, so it does not have this case.
   */
  def handle(command: RenewLoan): Future[Either[Rejected, Unit]] =
    import command.*
    store.buildReadModel((borrowerId = copyBorrowerId(copyId), bookId = copyBookId(copyId))).flatMap:
      // A copy that is not in the catalog has no book, but cannot be lent either.
      case (Some(borrowerId), Some(bookId)) =>
        val projections = (
          borrowerId = copyBorrowerId(copyId),
          renewals = copyRenewals(copyId),
          borrowerIsSuspended = memberIsSuspended(borrowerId),
          bookReservations = bookReservations(bookId),
        )
        store.buildDecisionModel(projections).flatMap: (state, appendConditionO) =>
          val decision =
            if state.borrowerId.isEmpty then Left(Rejected("Copy is not lent"))
            else if !state.borrowerId.contains(borrowerId)
            then Left(Rejected("Copy was lent to another member in the meantime"))
            else if state.renewals >= MaxRenewalsPerLoan then Left(Rejected("Loan was already renewed twice"))
            else if state.borrowerIsSuspended then Left(Rejected("Borrower is suspended"))
            else if state.bookReservations.nonEmpty then Left(Rejected("Book is reserved"))
            else Right(List(LoanRenewed(copyId).toEvent))
          appendIfAccepted(store)(decision, appendConditionO)
      case _ => Future.successful(Left(Rejected("Copy is not lent")))

  // interpret head as the last sequence numbe of the store even before the first projection read
  def handle2(command: RenewLoan): Future[Either[Rejected, Unit]] =
    import command.*

    val read1 = store.read(Query.all)
    read1.flatMap: iterable =>
      val head = iterable.iterator.toList.last.sequencePosition
      val projections1 = (
        borrowerId = copyBorrowerId(copyId),
        renewals = copyRenewals(copyId),
        bookId = copyBookId(copyId),
      )
      val read2 = store.buildReadModel(projections1)
      read2.flatMap:
        // A copy that is not in the catalog has no book, but cannot be lent either.
        case (Some(borrowerId), renewals, Some(bookId)) =>
          val projections2 = (
            borrowerIsSuspended = memberIsSuspended(borrowerId),
            bookReservations = bookReservations(bookId),
          )
          val read3 = store.buildReadModel(projections2)
          read3.flatMap:
            case (borrowerIsSuspended, bookReservations) =>
            val decision =
              if borrowerId.isEmpty then Left(Rejected("Copy is not lent"))
              else if renewals >= MaxRenewalsPerLoan then Left(Rejected("Loan was already renewed twice"))
              else if borrowerIsSuspended then Left(Rejected("Borrower is suspended"))
              else if bookReservations.nonEmpty then Left(Rejected("Book is reserved"))
              else Right(List(LoanRenewed(copyId).toEvent))
            val aqOs: List[Option[Query[EventType]]] = projections1.toList.map(_.queryO) ++ projections2.toList.map(_.queryO)
            val aqO = aqOs.flatten.reduceOption(_.merge(_))
            val ac = AppendCondition(aqO.get, Some(head))
            appendIfAccepted(store)(decision, Some(ac))
        case _ => Future.successful(Left(Rejected("Perhaps copy is not lent")))

  def handle(command: SuspendMember): Future[Either[Rejected, Unit]] =
    import command.*
    store.buildDecisionModel((suspended = memberIsSuspended(memberId))).flatMap: (state, appendConditionO) =>
      val decision =
        if state.suspended then Left(Rejected("Member is already suspended"))
        else Right(List(MemberSuspended(memberId).toEvent))
      appendIfAccepted(store)(decision, appendConditionO)

  def handle(command: ReinstateMember): Future[Either[Rejected, Unit]] =
    import command.*
    store.buildDecisionModel((suspended = memberIsSuspended(memberId))).flatMap: (state, appendConditionO) =>
      val decision =
        if !state.suspended then Left(Rejected("Member is not suspended"))
        else Right(List(MemberReinstated(memberId).toEvent))
      appendIfAccepted(store)(decision, appendConditionO)

  def handle(command: ReserveBook): Future[Either[Rejected, Unit]] =
    import command.*
    store.buildDecisionModel((reservations = bookReservations(bookId))).flatMap: (state, appendConditionO) =>
      val decision =
        if state.reservations.contains(memberId) then Left(Rejected("Member already reserved the book"))
        else Right(List(BookReserved(bookId, memberId).toEvent))
      appendIfAccepted(store)(decision, appendConditionO)

  def handle(command: CancelReservation): Future[Either[Rejected, Unit]] =
    import command.*
    store.buildDecisionModel((reservations = bookReservations(bookId))).flatMap: (state, appendConditionO) =>
      val decision =
        if !state.reservations.contains(memberId) then Left(Rejected("Member has not reserved the book"))
        else Right(List(ReservationCancelled(bookId, memberId).toEvent))
      appendIfAccepted(store)(decision, appendConditionO)
