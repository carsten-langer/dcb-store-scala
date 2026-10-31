package scalatest.dcbstore.examples.optintoken

import dcbstore.examples.*
import dcbstore.examples.optintoken.*
import dcbstore.inmemdcbstore.{InMemAppendConditionFailed, InMemDcbStore}
import scalatest.dcbstore.examples.{ConcurrentWriterStore, ExampleSpec}

import scala.concurrent.ExecutionContext.Implicits.global

// The scenarios of https://dcb.events/examples/opt-in-token/ in its final form (feature 2), plus the append condition
// at work.
class OptInTokenSpec
  extends ExampleSpec(OptInTokenCommandHandler(_)):

  private val john = "john.doe@example.com"

  "ConfirmSignUp" should "be rejected for a non-existing OTP" in :
    val (result, newEvents) = scenario()(_.handle(ConfirmSignUp(john, "000000", now = 100)))
    val _ = result shouldBe Left(Rejected("No sign-up is pending for this code"))
    newEvents shouldBe empty

  it should "be rejected for an OTP assigned to a different email address" in :
    val (result, newEvents) = scenario(
      SignUpInitiated(john, "111111", "John Doe", expiresAt = 160).toEvent,
    )(_.handle(ConfirmSignUp("jane.doe@example.com", "111111", now = 100)))
    val _ = result shouldBe Left(Rejected("No sign-up is pending for this code"))
    newEvents shouldBe empty

  it should "be rejected for an already used OTP" in :
    val (result, newEvents) = scenario(
      SignUpInitiated(john, "222222", "John Doe", expiresAt = 160).toEvent,
      SignUpConfirmed(john, "222222", "John Doe").toEvent,
    )(_.handle(ConfirmSignUp(john, "222222", now = 100)))
    val _ = result shouldBe Left(Rejected("Code was already used"))
    newEvents shouldBe empty

  it should "be rejected for an expired OTP" in :
    val (result, newEvents) = scenario(
      SignUpInitiated(john, "333333", "John Doe", expiresAt = 99).toEvent,
    )(_.handle(ConfirmSignUp(john, "333333", now = 100)))
    val _ = result shouldBe Left(Rejected("Code has expired"))
    newEvents shouldBe empty

  it should "confirm the sign-up for a valid OTP" in :
    val (result, newEvents) = scenario(
      SignUpInitiated(john, "444444", "John Doe", expiresAt = 160).toEvent,
    )(_.handle(ConfirmSignUp(john, "444444", now = 100)))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(SignUpConfirmed(john, "444444", "John Doe").toEvent)

  // The append condition at work: the command handler decides on the state it read, while a concurrent writer appends
  // its events just before the handler's append.

  it should "fail if the sign-up was confirmed concurrently" in :
    val underlying = InMemDcbStore[EventType, DataSuper]()
    underlying.append(List(SignUpInitiated(john, "444444", "John Doe", expiresAt = 160).toEvent)).futureValue
    val store = ConcurrentWriterStore(underlying, List(SignUpConfirmed(john, "444444", "John Doe").toEvent))

    val failure = OptInTokenCommandHandler(store).handle(ConfirmSignUp(john, "444444", now = 100)).failed
    val _ = failure.futureValue shouldBe an[InMemAppendConditionFailed]
    allEvents(underlying).count(_.eventType == "SignUpConfirmed") shouldBe 1

  it should "succeed despite another sign-up with the same OTP confirmed concurrently" in :
    // Outside the consistency boundary of the decision, which covers email address and OTP together.
    val underlying = InMemDcbStore[EventType, DataSuper]()
    underlying.append(List(
      SignUpInitiated(john, "444444", "John Doe", expiresAt = 160).toEvent,
      SignUpInitiated("jane.doe@example.com", "444444", "Jane Doe", expiresAt = 160).toEvent,
    )).futureValue
    val concurrentConfirmation = SignUpConfirmed("jane.doe@example.com", "444444", "Jane Doe").toEvent
    val store = ConcurrentWriterStore(underlying, List(concurrentConfirmation))

    val result = OptInTokenCommandHandler(store).handle(ConfirmSignUp(john, "444444", now = 100)).futureValue
    val _ = result shouldBe Right(())
    allEvents(underlying).drop(2) shouldBe List(
      concurrentConfirmation,
      SignUpConfirmed(john, "444444", "John Doe").toEvent,
    )
