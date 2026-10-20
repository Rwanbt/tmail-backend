package com.linagora.tmail.james.jmap.model

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Stable, account-scoped identity for a future durable Email/send outbox.
 *
 * These identifiers MUST NOT be interpreted as evidence that a message was
 * enqueued. Only durable claim/dispatch state may authorize a retry.
 */
object EmailSendIdempotency {
  final case class SubmissionIdentity(keyDigest: String, payloadDigest: String) {
    def matchesPayload(other: SubmissionIdentity): Boolean =
      keyDigest == other.keyDigest && payloadDigest == other.payloadDigest
  }

  def identity(accountId: String, clientKey: String, mime: Array[Byte],
               envelope: Array[Byte]): SubmissionIdentity = {
    require(accountId.nonEmpty, "account id must not be empty")
    require(clientKey.nonEmpty, "client submission key must not be empty")
    SubmissionIdentity(
      digest("tmail:email-send:submission-key:v1", Seq(accountId.getBytes(StandardCharsets.UTF_8),
        clientKey.getBytes(StandardCharsets.UTF_8))),
      digest("tmail:email-send:payload:v1", Seq(mime, envelope)))
  }

  // Length-prefixed fields prevent ambiguous concatenations such as
  // ["ab", "c"] and ["a", "bc"] from sharing an identity.
  private def digest(domain: String, fields: Seq[Array[Byte]]): String = {
    val sha = MessageDigest.getInstance("SHA-256")
    sha.update(domain.getBytes(StandardCharsets.UTF_8))
    sha.update(0.toByte)
    fields.foreach { value =>
      sha.update(ByteBuffer.allocate(4).putInt(value.length).array())
      sha.update(value)
    }
    sha.digest().map(b => f"${b & 0xff}%02x").mkString
  }
}
