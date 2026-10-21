package com.linagora.tmail.james.jmap.method

import reactor.core.scala.publisher.SMono

/** Payload stored in PostgreSQL before broker publication. */
case class OutboxMail(mime: Array[Byte],
                      from: String,
                      recipients: Seq[String],
                      authUser: String,
                      submissionId: String)

/**
 * The Postgres implementation reserves the client identity before append,
 * then atomically persists the MIME and claims publication. A claimed row is
 * never retried automatically after a crash or missing RabbitMQ ACK.
 */
trait EmailSendOutbox {
  def enabled: Boolean
  def reserve(key: String, digest: String, requestJson: String): SMono[Boolean]
  def stageAndClaim(key: String, owner: String, mail: OutboxMail): SMono[OutboxMail]
  def finish(key: String, owner: String, accepted: Boolean): SMono[Unit]
}

/** Preserve the original in-memory/distributed semantics outside PostgreSQL. */
class DisabledEmailSendOutbox extends EmailSendOutbox {
  override val enabled: Boolean = false
  override def reserve(key: String, digest: String, requestJson: String): SMono[Boolean] = SMono.just(true)
  override def stageAndClaim(key: String, owner: String, mail: OutboxMail): SMono[OutboxMail] = SMono.just(mail)
  override def finish(key: String, owner: String, accepted: Boolean): SMono[Unit] = SMono.just(())
}
