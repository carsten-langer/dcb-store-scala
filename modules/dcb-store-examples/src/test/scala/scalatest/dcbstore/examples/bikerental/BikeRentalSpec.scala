package scalatest.dcbstore.examples.bikerental

import dcbstore.examples.*
import dcbstore.examples.bikerental.*
import dcbstore.inmemdcbstore.{InMemAppendConditionFailed, InMemDcbStore}
import scalatest.dcbstore.examples.{ConcurrentWriterStore, ExampleSpec}

import scala.concurrent.ExecutionContext.Implicits.global

// The scenarios of https://dcb.events/examples/bike-rental/ in its final form (feature 3), i.e. those of feature 1, of
// feature 2 for RentBike, and of feature 3 for ReturnBike. Plus scenarios for the rejections the example has no
// scenarios for, and the append condition at work.
class BikeRentalSpec
  extends ExampleSpec(BikeRentalCommandHandler(_)):

  "AddBikeToFleet" should "be rejected for a bike already part of the fleet (not in the example)" in :
    val (result, newEvents) = scenario(BikeAddedToFleet("b1").toEvent)(_.handle(AddBikeToFleet("b1")))
    val _ = result shouldBe Left(Rejected("Bike is already part of the fleet"))
    newEvents shouldBe empty

  "SendBikeToRepair" should "be rejected for a rented bike" in :
    val (result, newEvents) = scenario(
      BikeAddedToFleet("b1").toEvent,
      BikeRented("b1", "m1").toEvent,
    )(_.handle(SendBikeToRepair("b1")))
    val _ = result shouldBe Left(Rejected("Bike is not available"))
    newEvents shouldBe empty

  "RepairBike" should "be rejected for a bike not in repair (not in the example)" in :
    val (result, newEvents) = scenario(BikeAddedToFleet("b1").toEvent)(_.handle(RepairBike("b1")))
    val _ = result shouldBe Left(Rejected("Bike is not in repair"))
    newEvents shouldBe empty

  "RentBike" should "rent an available bike" in :
    val (result, newEvents) = scenario(BikeAddedToFleet("b1").toEvent)(_.handle(RentBike("b1", "m1")))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(BikeRented("b1", "m1").toEvent)

  it should "be rejected for a bike that is rented" in :
    val (result, newEvents) = scenario(
      BikeAddedToFleet("b1").toEvent,
      BikeRented("b1", "m2").toEvent,
    )(_.handle(RentBike("b1", "m1")))
    val _ = result shouldBe Left(Rejected("Bike is not available"))
    newEvents shouldBe empty

  it should "be rejected for a bike that is in repair" in :
    val (result, newEvents) = scenario(
      BikeAddedToFleet("b1").toEvent,
      BikeSentToRepair("b1").toEvent,
    )(_.handle(RentBike("b1", "m1")))
    val _ = result shouldBe Left(Rejected("Bike is not available"))
    newEvents shouldBe empty

  it should "rent a repaired bike" in :
    val (result, newEvents) = scenario(
      BikeAddedToFleet("b1").toEvent,
      BikeSentToRepair("b1").toEvent,
      BikeRepaired("b1").toEvent,
    )(_.handle(RentBike("b1", "m1")))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(BikeRented("b1", "m1").toEvent)

  it should "be rejected for a third bike" in :
    val (result, newEvents) = scenario(
      BikeAddedToFleet("b1").toEvent,
      BikeAddedToFleet("b2").toEvent,
      BikeAddedToFleet("b3").toEvent,
      BikeRented("b1", "m1").toEvent,
      BikeRented("b2", "m1").toEvent,
    )(_.handle(RentBike("b3", "m1")))
    val _ = result shouldBe Left(Rejected("Member already rents two bikes"))
    newEvents shouldBe empty

  it should "rent a bike after returning one" in :
    val (result, newEvents) = scenario(
      BikeAddedToFleet("b1").toEvent,
      BikeAddedToFleet("b2").toEvent,
      BikeAddedToFleet("b3").toEvent,
      BikeRented("b1", "m1").toEvent,
      BikeRented("b2", "m1").toEvent,
      BikeReturned("b1", "m1").toEvent,
    )(_.handle(RentBike("b3", "m1")))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(BikeRented("b3", "m1").toEvent)

  "ReturnBike" should "be rejected for a bike that is not rented (not in the example)" in :
    val (result, newEvents) = scenario(BikeAddedToFleet("b1").toEvent)(_.handle(ReturnBike("b1", "m1", damaged = false)))
    val _ = result shouldBe Left(Rejected("Bike is not rented"))
    newEvents shouldBe empty

  it should "be rejected for a bike rented by another member" in :
    val (result, newEvents) = scenario(
      BikeAddedToFleet("b1").toEvent,
      BikeRented("b1", "m2").toEvent,
    )(_.handle(ReturnBike("b1", "m1", damaged = false)))
    val _ = result shouldBe Left(Rejected("Bike is rented by another member"))
    newEvents shouldBe empty

  it should "return a rented bike" in :
    val (result, newEvents) = scenario(
      BikeAddedToFleet("b1").toEvent,
      BikeRented("b1", "m1").toEvent,
    )(_.handle(ReturnBike("b1", "m1", damaged = false)))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(BikeReturned("b1", "m1").toEvent)

  it should "return a damaged bike" in :
    val (result, newEvents) = scenario(
      BikeAddedToFleet("b1").toEvent,
      BikeRented("b1", "m1").toEvent,
    )(_.handle(ReturnBike("b1", "m1", damaged = true)))
    val _ = result shouldBe Right(())
    newEvents shouldBe List(BikeReturned("b1", "m1").toEvent, BikeSentToRepair("b1").toEvent)

  // The append condition at work: the command handler decides on the state it read, while a concurrent writer appends
  // its events just before the handler's append.

  "RentBike" should "fail if another member rented the bike concurrently" in :
    val underlying = InMemDcbStore[EventType, DataSuper]()
    underlying.append(List(BikeAddedToFleet("b1").toEvent)).futureValue
    val store = ConcurrentWriterStore(underlying, List(BikeRented("b1", "m2").toEvent))

    val failure = BikeRentalCommandHandler(store).handle(RentBike("b1", "m1")).failed
    failure.futureValue shouldBe an[InMemAppendConditionFailed]

  it should "fail if the member rented another bike concurrently" in :
    // Even though a second rental would still be within the limit: the member's rentals are part of the decision.
    val underlying = InMemDcbStore[EventType, DataSuper]()
    underlying.append(List(BikeAddedToFleet("b1").toEvent, BikeAddedToFleet("b2").toEvent)).futureValue
    val store = ConcurrentWriterStore(underlying, List(BikeRented("b2", "m1").toEvent))

    val failure = BikeRentalCommandHandler(store).handle(RentBike("b1", "m1")).failed
    failure.futureValue shouldBe an[InMemAppendConditionFailed]

  it should "succeed despite another member renting another bike concurrently" in :
    // Outside the consistency boundary of the decision, so no conflict.
    val underlying = InMemDcbStore[EventType, DataSuper]()
    underlying.append(List(BikeAddedToFleet("b1").toEvent, BikeAddedToFleet("b2").toEvent)).futureValue
    val store = ConcurrentWriterStore(underlying, List(BikeRented("b2", "m2").toEvent))

    val result = BikeRentalCommandHandler(store).handle(RentBike("b1", "m1")).futureValue
    val _ = result shouldBe Right(())
    allEvents(underlying).drop(2) shouldBe List(BikeRented("b2", "m2").toEvent, BikeRented("b1", "m1").toEvent)
