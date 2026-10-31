package dcbstore.examples.optintoken

import dcbstore.decisionmodel.*
import dcbstore.decisionmodel.SingleProjection.handler
import dcbstore.examples.{*, given}
import dcbstore.{DcbStore, Event, Tags}

import scala.concurrent.{ExecutionContext, Future}

// The example https://dcb.events/examples/opt-in-token/, in its final form with both features: a double opt-in, where
// a sign-up is confirmed with a short one-time password (OTP) sent to the email address, which expires.
// With DCB, the OTP is stored with the SignUpInitiated event and both are tagged with email address and OTP. A decision
// model on both tags verifies the OTP, so neither a read model of pending tokens nor cryptography is needed.

type EmailAddress = String
type Otp = String

// As in the example, the time is part of the data: SignUpInitiated stores when the OTP expires, and the command is
// passed the current minute. Typically, these would be timestamps.
type Minute = Int

def signUpTags(emailAddress: EmailAddress, otp: Otp): Tags = Tags(Tags("email", emailAddress), Tags("otp", otp))


////////////////////////////////////////
// Events
////////////////////////////////////////

final case class SignUpInitiated(emailAddress: EmailAddress, otp: Otp, name: String, expiresAt: Minute)
  derives CanEqual:
  def toEvent: Event[EventType, SignUpInitiated] = event(this, signUpTags(emailAddress, otp))

final case class SignUpConfirmed(emailAddress: EmailAddress, otp: Otp, name: String) derives CanEqual:
  def toEvent: Event[EventType, SignUpConfirmed] = event(this, signUpTags(emailAddress, otp))


////////////////////////////////////////
// Projections
////////////////////////////////////////

// The name of the pending sign-up, None if there is none. This also covers the example's projection SignUpPending, as
// both are set by the same event: a sign-up is pending if and only if there is a name.
def signUpName(emailAddress: EmailAddress, otp: Otp) = SingleProjection(
  internalState = Option.empty[String],
  handlers = Map(
    handler(handlerF = (_: Option[String], e: SignUpInitiated) => Some(e.name), tags = signUpTags(emailAddress, otp)),
  ),
)

def otpUsed(emailAddress: EmailAddress, otp: Otp) = SingleProjection(
  internalState = false,
  handlers = Map(
    handler(handlerF = (_: Boolean, _: SignUpConfirmed) => true, tags = signUpTags(emailAddress, otp)),
  ),
)

def otpExpiresAt(emailAddress: EmailAddress, otp: Otp) = SingleProjection(
  internalState = 0,
  handlers = Map(
    handler(handlerF = (_: Minute, e: SignUpInitiated) => e.expiresAt, tags = signUpTags(emailAddress, otp)),
  ),
)


////////////////////////////////////////
// Commands
////////////////////////////////////////

final case class ConfirmSignUp(emailAddress: EmailAddress, otp: Otp, now: Minute)

/**
 * Handles the command: builds a decision model from a named tuple of projections, checks the business rules on its
 * typed state, and appends the resulting event with the decision model's append condition. See [[appendIfAccepted]]
 * for the outcomes. The append condition covers the email address and OTP, so of two concurrent confirmations only
 * one succeeds.
 */
final class OptInTokenCommandHandler(store: DcbStore[EventType, DataSuper])(using ExecutionContext):

  def handle(command: ConfirmSignUp): Future[Either[Rejected, Unit]] =
    import command.*
    val projections = (
      name = signUpName(emailAddress, otp),
      used = otpUsed(emailAddress, otp),
      expiresAt = otpExpiresAt(emailAddress, otp),
    )
    store.buildDecisionModel(projections).flatMap: (state, appendConditionO) =>
      val decision = state.name match
        case None => Left(Rejected("No sign-up is pending for this code"))
        case Some(_) if state.used => Left(Rejected("Code was already used"))
        case Some(_) if state.expiresAt <= now => Left(Rejected("Code has expired"))
        case Some(name) => Right(List(SignUpConfirmed(emailAddress, otp, name).toEvent))
      appendIfAccepted(store)(decision, appendConditionO)
