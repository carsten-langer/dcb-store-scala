package dcbstore.examples.bikerental

import dcbstore.decisionmodel.*
import dcbstore.decisionmodel.SingleProjection.handler
import dcbstore.examples.{*, given}
import dcbstore.{DcbStore, Event, Tags}

import scala.concurrent.{ExecutionContext, Future}

// The example https://dcb.events/examples/bike-rental/, in its final form with all three features: a bike's lifecycle,
// at most two rentals per member, who can only return the bikes they rented, and damaged returns going to repair.
// With DCB, the bike's lifecycle is an ordinary projection over the events tagged with the bike. BikeRented and
// BikeReturned are tagged with the bike and the member, so they also count towards the member's rentals.
// The example's entities Bike and Member only name their projections, mainly for the DCB Playground to draw the state
// machine, so here the commands read the projections directly.

type BikeId = String
type MemberId = String

enum BikeStatus derives CanEqual:
  case Unknown, Available, Rented, InRepair

def bikeTags(bikeId: BikeId): Tags = Tags("bike", bikeId)

def memberTags(memberId: MemberId): Tags = Tags("member", memberId)

def bikeAndMemberTags(bikeId: BikeId, memberId: MemberId): Tags = Tags(bikeTags(bikeId), memberTags(memberId))


////////////////////////////////////////
// Events
////////////////////////////////////////

final case class BikeAddedToFleet(bikeId: BikeId) derives CanEqual:
  def toEvent: Event[EventType, BikeAddedToFleet] = event(this, bikeTags(bikeId))

final case class BikeRented(bikeId: BikeId, memberId: MemberId) derives CanEqual:
  def toEvent: Event[EventType, BikeRented] = event(this, bikeAndMemberTags(bikeId, memberId))

final case class BikeReturned(bikeId: BikeId, memberId: MemberId) derives CanEqual:
  def toEvent: Event[EventType, BikeReturned] = event(this, bikeAndMemberTags(bikeId, memberId))

final case class BikeSentToRepair(bikeId: BikeId) derives CanEqual:
  def toEvent: Event[EventType, BikeSentToRepair] = event(this, bikeTags(bikeId))

final case class BikeRepaired(bikeId: BikeId) derives CanEqual:
  def toEvent: Event[EventType, BikeRepaired] = event(this, bikeTags(bikeId))


////////////////////////////////////////
// Projections
////////////////////////////////////////

// The bike's lifecycle. It sees all events tagged with the bike, including those also tagged with a member.
def bikeStatus(bikeId: BikeId) = SingleProjection(
  internalState = BikeStatus.Unknown,
  handlers = Map(
    handler(handlerF = (_: BikeStatus, _: BikeAddedToFleet) => BikeStatus.Available, tags = bikeTags(bikeId)),
    handler(handlerF = (_: BikeStatus, _: BikeRented) => BikeStatus.Rented, tags = bikeTags(bikeId)),
    handler(handlerF = (_: BikeStatus, _: BikeReturned) => BikeStatus.Available, tags = bikeTags(bikeId)),
    handler(handlerF = (_: BikeStatus, _: BikeSentToRepair) => BikeStatus.InRepair, tags = bikeTags(bikeId)),
    handler(handlerF = (_: BikeStatus, _: BikeRepaired) => BikeStatus.Available, tags = bikeTags(bikeId)),
  ),
)

// Across all bikes, as it only declares the member's tag.
def memberActiveRentals(memberId: MemberId) = SingleProjection(
  internalState = 0,
  handlers = Map(
    handler(handlerF = (count: Int, _: BikeRented) => count + 1, tags = memberTags(memberId)),
    handler(handlerF = (count: Int, _: BikeReturned) => count - 1, tags = memberTags(memberId)),
  ),
)

def bikeRentedByMember(bikeId: BikeId, memberId: MemberId) = SingleProjection(
  internalState = false,
  handlers = Map(
    handler(handlerF = (_: Boolean, _: BikeRented) => true, tags = bikeAndMemberTags(bikeId, memberId)),
    handler(handlerF = (_: Boolean, _: BikeReturned) => false, tags = bikeAndMemberTags(bikeId, memberId)),
  ),
)


////////////////////////////////////////
// Commands
////////////////////////////////////////

final case class AddBikeToFleet(bikeId: BikeId)

final case class SendBikeToRepair(bikeId: BikeId)

final case class RepairBike(bikeId: BikeId)

final case class RentBike(bikeId: BikeId, memberId: MemberId)

final case class ReturnBike(bikeId: BikeId, memberId: MemberId, damaged: Boolean)

val MaxActiveRentalsPerMember = 2

/**
 * Handles the commands: builds a decision model from a named tuple of projections, checks the business rules on its
 * typed state, and appends the resulting events with the decision model's append condition. See [[appendIfAccepted]]
 * for the outcomes.
 *
 * RentBike reads the bike and the member, so its consistency boundary covers both, but nothing else: two members
 * renting two different bikes do not affect each other. ReturnBike of a damaged bike appends BikeReturned and
 * BikeSentToRepair atomically, so there is no moment in which the damaged bike is available for rent.
 */
final class BikeRentalCommandHandler(store: DcbStore[EventType, DataSuper])(using ExecutionContext):

  def handle(command: AddBikeToFleet): Future[Either[Rejected, Unit]] =
    import command.*
    store.buildDecisionModel((status = bikeStatus(bikeId))).flatMap: (state, appendConditionO) =>
      val decision =
        if state.status != BikeStatus.Unknown then Left(Rejected("Bike is already part of the fleet"))
        else Right(List(BikeAddedToFleet(bikeId).toEvent))
      appendIfAccepted(store)(decision, appendConditionO)

  def handle(command: SendBikeToRepair): Future[Either[Rejected, Unit]] =
    import command.*
    store.buildDecisionModel((status = bikeStatus(bikeId))).flatMap: (state, appendConditionO) =>
      val decision =
        if state.status != BikeStatus.Available then Left(Rejected("Bike is not available"))
        else Right(List(BikeSentToRepair(bikeId).toEvent))
      appendIfAccepted(store)(decision, appendConditionO)

  def handle(command: RepairBike): Future[Either[Rejected, Unit]] =
    import command.*
    store.buildDecisionModel((status = bikeStatus(bikeId))).flatMap: (state, appendConditionO) =>
      val decision =
        if state.status != BikeStatus.InRepair then Left(Rejected("Bike is not in repair"))
        else Right(List(BikeRepaired(bikeId).toEvent))
      appendIfAccepted(store)(decision, appendConditionO)

  def handle(command: RentBike): Future[Either[Rejected, Unit]] =
    import command.*
    val projections = (bikeStatus = bikeStatus(bikeId), memberActiveRentals = memberActiveRentals(memberId))
    store.buildDecisionModel(projections).flatMap: (state, appendConditionO) =>
      val decision =
        if state.bikeStatus != BikeStatus.Available then Left(Rejected("Bike is not available"))
        else if state.memberActiveRentals >= MaxActiveRentalsPerMember
        then Left(Rejected("Member already rents two bikes"))
        else Right(List(BikeRented(bikeId, memberId).toEvent))
      appendIfAccepted(store)(decision, appendConditionO)

  def handle(command: ReturnBike): Future[Either[Rejected, Unit]] =
    import command.*
    val projections = (bikeStatus = bikeStatus(bikeId), rentedByMember = bikeRentedByMember(bikeId, memberId))
    store.buildDecisionModel(projections).flatMap: (state, appendConditionO) =>
      val decision =
        if state.bikeStatus != BikeStatus.Rented then Left(Rejected("Bike is not rented"))
        else if !state.rentedByMember then Left(Rejected("Bike is rented by another member"))
        else
          // The example's conditional emit. Events of two data types, so typed with their common super type, rather
          // than inferring their union type.
          val returned = List[Event[EventType, DataSuper]](BikeReturned(bikeId, memberId).toEvent)
          val sentToRepair = if damaged then List(BikeSentToRepair(bikeId).toEvent) else Nil
          Right(returned ++ sentToRepair)
      appendIfAccepted(store)(decision, appendConditionO)
