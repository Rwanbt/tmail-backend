package com.linagora.tmail.james.jmap.model

/**
 * Safety rules for durable Email/send submission state.
 *
 * Persistence and compare-and-set ownership MUST surround these transitions
 * before they are used by a queue dispatcher. This model deliberately never
 * treats a missing broker ACK, expired claim, or process restart as a reason
 * to publish again: the prior publish may already have succeeded.
 */
object EmailSendOutboxState {
  sealed trait State
  case object Prepared extends State
  final case class Dispatching(owner: String) extends State
  case object AcceptanceUnknown extends State
  case object Accepted extends State
  case object RejectedBeforePublish extends State

  sealed trait Decision
  final case class Transition(next: State) extends Decision
  case object Conflict extends Decision

  /** Only durable CAS from Prepared may grant exclusive dispatch ownership. */
  def claim(state: State, owner: String): Decision =
    if (state == Prepared && owner.nonEmpty) Transition(Dispatching(owner))
    else Conflict

  /** A missing ACK after dispatch can NEVER be interpreted as rejection. */
  def acknowledgementLost(state: State, owner: String): Decision = state match {
    case Dispatching(current) if current == owner && owner.nonEmpty =>
      Transition(AcceptanceUnknown)
    case _ => Conflict
  }

  def acknowledge(state: State, owner: String): Decision = state match {
    case Dispatching(current) if current == owner && owner.nonEmpty =>
      Transition(Accepted)
    case _ => Conflict
  }

  /** Only evidence that publication never started permits a retry. */
  def rejectBeforePublish(state: State): Decision = state match {
    case Prepared => Transition(RejectedBeforePublish)
    case _ => Conflict
  }

  /** After restart, an in-flight dispatch is uncertain, not retryable. */
  def recover(state: State): State = state match {
    case Dispatching(_) => AcceptanceUnknown
    case other => other
  }

  def mayPublish(state: State): Boolean = state == Prepared
}
