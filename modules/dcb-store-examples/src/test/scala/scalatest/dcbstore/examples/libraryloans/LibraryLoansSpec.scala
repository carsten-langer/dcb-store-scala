package scalatest.dcbstore.examples.libraryloans

import dcbstore.examples.*
import dcbstore.examples.libraryloans.*
import dcbstore.inmemdcbstore.{InMemAppendConditionFailed, InMemDcbStore}
import scalatest.dcbstore.examples.ConcurrentWriterStore.Moment
import scalatest.dcbstore.examples.{ConcurrentWriterStore, ExampleSpec}

import scala.concurrent.ExecutionContext.Implicits.global

// The scenarios of https://dcb.events/examples/library-loans/ in its final form (feature 3), i.e. those of all three
// features. Plus scenarios for the rejections the example has no scenarios for, and the append condition at work.
class LibraryLoansSpec
  extends ExampleSpec(LibraryLoansCommandHandler(_)):

  "AddCopyToCatalog" should "be rejected for a copy already in the catalog (not in the example)" in :
    val (result, newEvents) = scenario(
      CopyAddedToCatalog("c1", "b1").toEvent,
    )(_.handle(AddCopyToCatalog("c1", "b2")))
    val _ = result shouldBe Left(Rejected("Copy is already in the catalog"))
    newEvents shouldBe empty

  "LendCopy" should "lend an available copy" in :
    val (result, newEvents) = scenario(CopyAddedToCatalog("c1", "b1").toEvent)(_.handle(LendCopy("c1", "m1")))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(CopyLent("c1", "m1").toEvent)

  it should "be rejected for a copy that is not in the catalog" in :
    val (result, newEvents) = scenario()(_.handle(LendCopy("c1", "m1")))
    val _ = result shouldBe Left(Rejected("Copy is not in the catalog"))
    newEvents shouldBe empty

  it should "be rejected for a copy that is lent" in :
    val (result, newEvents) = scenario(
      CopyAddedToCatalog("c1", "b1").toEvent,
      CopyLent("c1", "m2").toEvent,
    )(_.handle(LendCopy("c1", "m1")))
    val _ = result shouldBe Left(Rejected("Copy is not available"))
    newEvents shouldBe empty

  it should "lend a returned copy" in :
    val (result, newEvents) = scenario(
      CopyAddedToCatalog("c1", "b1").toEvent,
      CopyLent("c1", "m2").toEvent,
      CopyReturned("c1", "m2").toEvent,
    )(_.handle(LendCopy("c1", "m1")))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(CopyLent("c1", "m1").toEvent)

  it should "be rejected for a fourth copy" in :
    val (result, newEvents) = scenario(
      CopyAddedToCatalog("c1", "b1").toEvent,
      CopyAddedToCatalog("c2", "b2").toEvent,
      CopyAddedToCatalog("c3", "b3").toEvent,
      CopyAddedToCatalog("c4", "b4").toEvent,
      CopyLent("c1", "m1").toEvent,
      CopyLent("c2", "m1").toEvent,
      CopyLent("c3", "m1").toEvent,
    )(_.handle(LendCopy("c4", "m1")))
    val _ = result shouldBe Left(Rejected("Member already borrows three copies"))
    newEvents shouldBe empty

  "ReturnCopy" should "return a lent copy" in :
    val (result, newEvents) = scenario(
      CopyAddedToCatalog("c1", "b1").toEvent,
      CopyLent("c1", "m1").toEvent,
    )(_.handle(ReturnCopy("c1", "m1")))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(CopyReturned("c1", "m1").toEvent)

  it should "be rejected for a copy that is not lent" in :
    val (result, newEvents) = scenario(CopyAddedToCatalog("c1", "b1").toEvent)(_.handle(ReturnCopy("c1", "m1")))
    val _ = result shouldBe Left(Rejected("Copy is not lent"))
    newEvents shouldBe empty

  it should "be rejected for a copy lent to another member" in :
    val (result, newEvents) = scenario(
      CopyAddedToCatalog("c1", "b1").toEvent,
      CopyLent("c1", "m2").toEvent,
    )(_.handle(ReturnCopy("c1", "m1")))
    val _ = result shouldBe Left(Rejected("Copy is lent to another member"))
    newEvents shouldBe empty

  "RenewLoan" should "renew a loan" in :
    val (result, newEvents) = scenario(
      CopyAddedToCatalog("c1", "b1").toEvent,
      CopyLent("c1", "m1").toEvent,
    )(_.handle(RenewLoan("c1")))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(LoanRenewed("c1").toEvent)

  it should "be rejected for a copy that is not lent" in :
    val (result, newEvents) = scenario(CopyAddedToCatalog("c1", "b1").toEvent)(_.handle(RenewLoan("c1")))
    val _ = result shouldBe Left(Rejected("Copy is not lent"))
    newEvents shouldBe empty

  it should "be rejected for a third renewal" in :
    val (result, newEvents) = scenario(
      CopyAddedToCatalog("c1", "b1").toEvent,
      CopyLent("c1", "m1").toEvent,
      LoanRenewed("c1").toEvent,
      LoanRenewed("c1").toEvent,
    )(_.handle(RenewLoan("c1")))
    val _ = result shouldBe Left(Rejected("Loan was already renewed twice"))
    newEvents shouldBe empty

  it should "be rejected for a loan of a suspended member" in :
    val (result, newEvents) = scenario(
      CopyAddedToCatalog("c1", "b1").toEvent,
      CopyLent("c1", "m1").toEvent,
      MemberSuspended("m1").toEvent,
    )(_.handle(RenewLoan("c1")))
    val _ = result shouldBe Left(Rejected("Borrower is suspended"))
    newEvents shouldBe empty

  it should "renew a loan while another member is suspended" in :
    val (result, newEvents) = scenario(
      CopyAddedToCatalog("c1", "b1").toEvent,
      CopyLent("c1", "m1").toEvent,
      MemberSuspended("m2").toEvent,
    )(_.handle(RenewLoan("c1")))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(LoanRenewed("c1").toEvent)

  it should "renew a copy that was lent again" in :
    val (result, newEvents) = scenario(
      CopyAddedToCatalog("c1", "b1").toEvent,
      CopyLent("c1", "m1").toEvent,
      LoanRenewed("c1").toEvent,
      LoanRenewed("c1").toEvent,
      CopyReturned("c1", "m1").toEvent,
      CopyLent("c1", "m2").toEvent,
    )(_.handle(RenewLoan("c1")))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(LoanRenewed("c1").toEvent)

  it should "be rejected for a loan of a reserved book" in :
    val (result, newEvents) = scenario(
      CopyAddedToCatalog("c1", "b1").toEvent,
      CopyLent("c1", "m1").toEvent,
      BookReserved("b1", "m2").toEvent,
    )(_.handle(RenewLoan("c1")))
    val _ = result shouldBe Left(Rejected("Book is reserved"))
    newEvents shouldBe empty

  it should "renew a loan after the reservation was cancelled" in :
    val (result, newEvents) = scenario(
      CopyAddedToCatalog("c1", "b1").toEvent,
      CopyLent("c1", "m1").toEvent,
      BookReserved("b1", "m2").toEvent,
      ReservationCancelled("b1", "m2").toEvent,
    )(_.handle(RenewLoan("c1")))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(LoanRenewed("c1").toEvent)

  it should "renew a loan while another book is reserved" in :
    val (result, newEvents) = scenario(
      CopyAddedToCatalog("c1", "b1").toEvent,
      CopyLent("c1", "m1").toEvent,
      BookReserved("b2", "m2").toEvent,
    )(_.handle(RenewLoan("c1")))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(LoanRenewed("c1").toEvent)

  "SuspendMember" should "be rejected for a suspended member (not in the example)" in :
    val (result, newEvents) = scenario(MemberSuspended("m1").toEvent)(_.handle(SuspendMember("m1")))
    val _ = result shouldBe Left(Rejected("Member is already suspended"))
    newEvents shouldBe empty

  "ReinstateMember" should "be rejected for a member who is not suspended (not in the example)" in :
    val (result, newEvents) = scenario(
      MemberSuspended("m1").toEvent,
      MemberReinstated("m1").toEvent,
    )(_.handle(ReinstateMember("m1")))
    val _ = result shouldBe Left(Rejected("Member is not suspended"))
    newEvents shouldBe empty

  "ReserveBook" should "be rejected for a book the member already reserved (not in the example)" in :
    val (result, newEvents) = scenario(BookReserved("b1", "m1").toEvent)(_.handle(ReserveBook("b1", "m1")))
    val _ = result shouldBe Left(Rejected("Member already reserved the book"))
    newEvents shouldBe empty

  "CancelReservation" should "be rejected for a book the member has not reserved (not in the example)" in :
    val (result, newEvents) = scenario(BookReserved("b1", "m2").toEvent)(_.handle(CancelReservation("b1", "m1")))
    val _ = result shouldBe Left(Rejected("Member has not reserved the book"))
    newEvents shouldBe empty

  // The append condition at work: the command handler decides on the state it read, while a concurrent writer appends
  // its events just before the handler's append, or between its two reads.

  private def lentCopy(): InMemDcbStore[EventType, DataSuper] =
    val underlying = InMemDcbStore[EventType, DataSuper]()
    underlying.append[DataSuper](List(CopyAddedToCatalog("c1", "b1").toEvent, CopyLent("c1", "m1").toEvent)).futureValue
    underlying

  "RenewLoan" should "fail if the borrower was suspended concurrently" in :
    // The member was not known up front, but was read via the copy, and is part of the append condition.
    val store = ConcurrentWriterStore(lentCopy(), List(MemberSuspended("m1").toEvent))

    val failure = LibraryLoansCommandHandler(store).handle(RenewLoan("c1")).failed
    failure.futureValue shouldBe an[InMemAppendConditionFailed]

  it should "fail if the book was reserved concurrently" in :
    // Likewise, the book was read via the copy.
    val store = ConcurrentWriterStore(lentCopy(), List(BookReserved("b1", "m2").toEvent))

    val failure = LibraryLoansCommandHandler(store).handle(RenewLoan("c1")).failed
    failure.futureValue shouldBe an[InMemAppendConditionFailed]

  it should "succeed despite another member suspended and another book reserved concurrently" in :
    // Outside the consistency boundary of the decision, so no conflict.
    val underlying = lentCopy()
    val store = ConcurrentWriterStore(underlying, List(MemberSuspended("m2").toEvent, BookReserved("b2", "m2").toEvent))

    val result = LibraryLoansCommandHandler(store).handle(RenewLoan("c1")).futureValue
    val _ = result shouldBe Right(())
    allEvents(underlying).last shouldBe LoanRenewed("c1").toEvent

  // Between the two reads of RenewLoan, which the append condition does not cover.

  it should "be rejected if the copy was lent to another member between its two reads" in :
    // The first read yields borrower m1, but the loan to be renewed has ended. Without the check, the renewal would be
    // decided on m1's state, but apply to m2's loan.
    val underlying = lentCopy()
    val concurrentLoan = List(CopyReturned("c1", "m1").toEvent, CopyLent("c1", "m2").toEvent)
    val store = ConcurrentWriterStore(underlying, concurrentLoan, Moment.BeforeRead(2))

    val result = LibraryLoansCommandHandler(store).handle(RenewLoan("c1")).futureValue
    val _ = result shouldBe Left(Rejected("Copy was lent to another member in the meantime"))
    allEvents(underlying).takeRight(2) shouldBe concurrentLoan

  it should "be rejected if the copy was returned between its two reads" in :
    val store = ConcurrentWriterStore(lentCopy(), List(CopyReturned("c1", "m1").toEvent), Moment.BeforeRead(2))

    val result = LibraryLoansCommandHandler(store).handle(RenewLoan("c1")).futureValue
    result shouldBe Left(Rejected("Copy is not lent"))
