package scalatest.dcbstore.examples.uniqueusername

import dcbstore.examples.*
import dcbstore.examples.uniqueusername.*
import dcbstore.inmemdcbstore.{InMemAppendConditionFailed, InMemDcbStore}
import scalatest.dcbstore.examples.{ConcurrentWriterStore, ExampleSpec}

import scala.concurrent.ExecutionContext.Implicits.global

// The scenarios of https://dcb.events/examples/unique-username/ in its final form (feature 4), plus the append
// condition at work.
class UniqueUsernameSpec
  extends ExampleSpec(UniqueUsernameCommandHandler(_)):

  "RegisterAccount" should "be rejected for a claimed username" in :
    val (result, newEvents) = scenario(AccountRegistered("u1").toEvent)(_.handle(RegisterAccount("u1", today = 10)))
    val _ = result shouldBe Left(Rejected("Username is already taken"))
    newEvents shouldBe empty

  it should "register an account with an unused username" in :
    val (result, newEvents) = scenario()(_.handle(RegisterAccount("u1", today = 10)))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(AccountRegistered("u1").toEvent)

  it should "register an account with the username of a closed account" in :
    val (result, newEvents) = scenario(
      AccountRegistered("u1").toEvent,
      AccountClosed("u1", closedOn = 1).toEvent,
    )(_.handle(RegisterAccount("u1", today = 10)))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(AccountRegistered("u1").toEvent)

  it should "register an account with a username that was previously used and then changed" in :
    val (result, newEvents) = scenario(
      AccountRegistered("u1").toEvent,
      UsernameReleased("u1", releasedOn = 1).toEvent,
      UsernameClaimed("u1changed").toEvent,
    )(_.handle(RegisterAccount("u1", today = 10)))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(AccountRegistered("u1").toEvent)

  it should "be rejected for a username that another username was changed to" in :
    val (result, newEvents) = scenario(
      AccountRegistered("u1").toEvent,
      UsernameReleased("u1", releasedOn = 1).toEvent,
      UsernameClaimed("u1changed").toEvent,
    )(_.handle(RegisterAccount("u1changed", today = 10)))
    val _ = result shouldBe Left(Rejected("Username is already taken"))
    newEvents shouldBe empty

  it should "be rejected for the username of a closed account before the retention period" in :
    val (result, newEvents) = scenario(
      AccountRegistered("u1").toEvent,
      AccountClosed("u1", closedOn = 7).toEvent,
    )(_.handle(RegisterAccount("u1", today = 10)))
    val _ = result shouldBe Left(Rejected("Username is already taken"))
    newEvents shouldBe empty

  it should "be rejected for a changed username before the retention period" in :
    val (result, newEvents) = scenario(
      AccountRegistered("u1").toEvent,
      UsernameReleased("u1", releasedOn = 7).toEvent,
      UsernameClaimed("u1changed").toEvent,
    )(_.handle(RegisterAccount("u1", today = 10)))
    val _ = result shouldBe Left(Rejected("Username is already taken"))
    newEvents shouldBe empty

  it should "register the username of a closed account after the retention period" in :
    val (result, newEvents) = scenario(
      AccountRegistered("u1").toEvent,
      AccountClosed("u1", closedOn = 6).toEvent,
    )(_.handle(RegisterAccount("u1", today = 10)))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(AccountRegistered("u1").toEvent)

  it should "register a changed username after the retention period" in :
    val (result, newEvents) = scenario(
      AccountRegistered("u1").toEvent,
      UsernameReleased("u1", releasedOn = 6).toEvent,
      UsernameClaimed("u1changed").toEvent,
    )(_.handle(RegisterAccount("u1", today = 10)))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(AccountRegistered("u1").toEvent)

  "ChangeUsername" should "change the username" in :
    val (result, newEvents) = scenario(AccountRegistered("u1").toEvent)(
      _.handle(ChangeUsername("u1", newUsername = "u1changed", today = 10))
    )
    val _ = result shouldBe Right(())
    newEvents shouldBe List(UsernameReleased("u1", releasedOn = 10).toEvent, UsernameClaimed("u1changed").toEvent)

  it should "be rejected for a claimed username" in :
    val (result, newEvents) = scenario(
      AccountRegistered("u1").toEvent,
      AccountRegistered("u2").toEvent,
    )(_.handle(ChangeUsername("u1", newUsername = "u2", today = 10)))
    val _ = result shouldBe Left(Rejected("Username is already taken"))
    newEvents shouldBe empty

  it should "be rejected for a username that is not in use" in :
    val (result, newEvents) = scenario()(_.handle(ChangeUsername("u1", newUsername = "u1changed", today = 10)))
    val _ = result shouldBe Left(Rejected("Username is not in use"))
    newEvents shouldBe empty

  it should "be rejected for the username of a closed account before the retention period" in :
    val (result, newEvents) = scenario(
      AccountRegistered("u1").toEvent,
      AccountRegistered("u2").toEvent,
      AccountClosed("u2", closedOn = 7).toEvent,
    )(_.handle(ChangeUsername("u1", newUsername = "u2", today = 10)))
    val _ = result shouldBe Left(Rejected("Username is already taken"))
    newEvents shouldBe empty

  // The append condition at work: the command handler decides on the state it read, while a concurrent writer appends
  // its events just before the handler's append.

  "RegisterAccount" should "fail if the username was registered concurrently" in :
    val underlying = InMemDcbStore[EventType, DataSuper]()
    val store = ConcurrentWriterStore(underlying, List(AccountRegistered("u1").toEvent))

    val failure = UniqueUsernameCommandHandler(store).handle(RegisterAccount("u1", today = 10)).failed
    val _ = failure.futureValue shouldBe an[InMemAppendConditionFailed]
    allEvents(underlying) shouldBe List(AccountRegistered("u1").toEvent)

  it should "succeed despite another username registered concurrently" in :
    val underlying = InMemDcbStore[EventType, DataSuper]()
    val store = ConcurrentWriterStore(underlying, List(AccountRegistered("u2").toEvent))

    val result = UniqueUsernameCommandHandler(store).handle(RegisterAccount("u1", today = 10)).futureValue
    val _ = result shouldBe Right(())
    allEvents(underlying) shouldBe List(AccountRegistered("u2").toEvent, AccountRegistered("u1").toEvent)

  "ChangeUsername" should "fail if the new username was registered concurrently" in :
    val underlying = InMemDcbStore[EventType, DataSuper]()
    underlying.append(List(AccountRegistered("u1").toEvent)).futureValue
    val store = ConcurrentWriterStore(underlying, List(AccountRegistered("u2").toEvent))

    val failure = UniqueUsernameCommandHandler(store).handle(ChangeUsername("u1", newUsername = "u2", today = 10)).failed
    failure.futureValue shouldBe an[InMemAppendConditionFailed]

  it should "fail if the old username was changed concurrently" in :
    val underlying = InMemDcbStore[EventType, DataSuper]()
    underlying.append(List(AccountRegistered("u1").toEvent)).futureValue
    val concurrentChange = List(UsernameReleased("u1", releasedOn = 10).toEvent, UsernameClaimed("u3").toEvent)
    val store = ConcurrentWriterStore(underlying, concurrentChange)

    val failure = UniqueUsernameCommandHandler(store).handle(ChangeUsername("u1", newUsername = "u2", today = 10)).failed
    failure.futureValue shouldBe an[InMemAppendConditionFailed]
