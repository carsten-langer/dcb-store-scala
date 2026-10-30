package dcbstore.examples.uniqueusername

import dcbstore.decisionmodel.*
import dcbstore.decisionmodel.SingleProjection.handler
import dcbstore.examples.{*, given}
import dcbstore.{DcbStore, Event, Tags}

import scala.concurrent.{ExecutionContext, Future}

// The example https://dcb.events/examples/unique-username/, in its final form with all four features: globally unique
// usernames, released when an account is closed or its username is changed, but only after a retention period.
// With DCB, every event affecting a username is tagged with it, so a decision on a username covers exactly its events.
// As in the example, the username is used directly as tag value; a real implementation would normalize and hash it.

type Username = String

// The current date as day number, a simplification of a timestamp. It is passed in with the commands and recorded in
// the events, which keeps the decisions deterministic.
type Day = Int

def usernameTags(username: Username): Tags = Tags("username", username)


////////////////////////////////////////
// Events
////////////////////////////////////////

final case class AccountRegistered(username: Username) derives CanEqual:
  def toEvent: Event[EventType, AccountRegistered] = event(this, usernameTags(username))

final case class AccountClosed(username: Username, closedOn: Day) derives CanEqual:
  def toEvent: Event[EventType, AccountClosed] = event(this, usernameTags(username))

final case class UsernameReleased(username: Username, releasedOn: Day) derives CanEqual:
  def toEvent: Event[EventType, UsernameReleased] = event(this, usernameTags(username))

final case class UsernameClaimed(username: Username) derives CanEqual:
  def toEvent: Event[EventType, UsernameClaimed] = event(this, usernameTags(username))


////////////////////////////////////////
// Projections
////////////////////////////////////////

def usernameTaken(username: Username) = SingleProjection(
  internalState = false,
  handlers = Map(
    handler(handlerF = (_: Boolean, _: AccountRegistered) => true, tags = usernameTags(username)),
    handler(handlerF = (_: Boolean, _: AccountClosed) => false, tags = usernameTags(username)),
    handler(handlerF = (_: Boolean, _: UsernameReleased) => false, tags = usernameTags(username)),
    handler(handlerF = (_: Boolean, _: UsernameClaimed) => true, tags = usernameTags(username)),
  ),
)

val RetentionDays = 3

// The example's projection UsernameReleasedOn as internal state, and its derived projection UsernameRetainedUntil as
// final state. The internal state starts far in the past, so a username that was never released is not retained.
def usernameRetainedUntil(username: Username) = SingleProjection(
  internalState = -1000,
  handlers = Map(
    handler(handlerF = (_: Day, e: AccountClosed) => e.closedOn, tags = usernameTags(username)),
    handler(handlerF = (_: Day, e: UsernameReleased) => e.releasedOn, tags = usernameTags(username)),
  ),
  finalStateMapperF = (releasedOn: Day) => releasedOn + RetentionDays,
)


////////////////////////////////////////
// Commands
////////////////////////////////////////

final case class RegisterAccount(username: Username, today: Day)

final case class ChangeUsername(username: Username, newUsername: Username, today: Day)

/**
 * Handles the commands: builds a decision model from a named tuple of projections, checks the business rules on its
 * typed state, and appends the resulting events with the decision model's append condition. See [[appendIfAccepted]]
 * for the outcomes.
 *
 * ChangeUsername decides on two usernames. The named tuple holds the same projection for each of them, distinguished by
 * name, and the single append condition covers both usernames: if either is claimed or released in the meantime, the
 * change fails.
 */
final class UniqueUsernameCommandHandler(store: DcbStore[EventType, DataSuper])(using ExecutionContext):

  def handle(command: RegisterAccount): Future[Either[Rejected, Unit]] =
    import command.*
    val projections = (taken = usernameTaken(username), retainedUntil = usernameRetainedUntil(username))
    store.buildDecisionModel(projections).flatMap: (state, appendConditionO) =>
      val decision =
        if state.taken || state.retainedUntil >= today then Left(Rejected("Username is already taken"))
        else Right(List(AccountRegistered(username).toEvent))
      appendIfAccepted(store)(decision, appendConditionO)

  def handle(command: ChangeUsername): Future[Either[Rejected, Unit]] =
    import command.*
    val projections = (
      taken = usernameTaken(username),
      newTaken = usernameTaken(newUsername),
      newRetainedUntil = usernameRetainedUntil(newUsername),
    )
    store.buildDecisionModel(projections).flatMap: (state, appendConditionO) =>
      val decision =
        if !state.taken then Left(Rejected("Username is not in use"))
        else if state.newTaken || state.newRetainedUntil >= today then Left(Rejected("Username is already taken"))
        // Events of two data types, so typed with their common super type, rather than inferring their union type.
        else Right(List[Event[EventType, DataSuper]](
          UsernameReleased(username, releasedOn = today).toEvent,
          UsernameClaimed(newUsername).toEvent,
        ))
      appendIfAccepted(store)(decision, appendConditionO)
