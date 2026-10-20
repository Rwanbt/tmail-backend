package com.linagora.tmail.james.jmap.model

/**
 * Atomic-claim contract for a future durable outbox adapter.
 *
 * A process-local implementation is intentionally NOT provided: an in-memory
 * claim would permit duplicate dispatch on two James nodes or after restart.
 * Implementations must atomically persist identity, payload digest and state,
 * and must make compare-and-set decisions inside the backing-store transaction.
 */
trait EmailSendOutboxClaims {
  import EmailSendOutboxClaims._

  def register(identity: EmailSendIdempotency.SubmissionIdentity): Registration

  /** Claim must be persisted before attempting to publish to RabbitMQ. */
  def claim(keyDigest: String, expectedVersion: Long, owner: String): Claim

  /** A lost broker acknowledgement is never a confirmed refusal. */
  def markUnknown(keyDigest: String, expectedVersion: Long, owner: String): Claim

  /** Recovery must not automatically publish entries in an uncertain state. */
  def read(keyDigest: String): Option[Snapshot]
}

object EmailSendOutboxClaims {
  final case class Snapshot(identity: EmailSendIdempotency.SubmissionIdentity,
                            state: EmailSendOutboxState.State,
                            version: Long)

  sealed trait Registration
  final case class Registered(snapshot: Snapshot) extends Registration
  final case class Existing(snapshot: Snapshot) extends Registration
  case object PayloadConflict extends Registration

  sealed trait Claim
  final case class Applied(snapshot: Snapshot) extends Claim
  case object StaleOrForbidden extends Claim
}
