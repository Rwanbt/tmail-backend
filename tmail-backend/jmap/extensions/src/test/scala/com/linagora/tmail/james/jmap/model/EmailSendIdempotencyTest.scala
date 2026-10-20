package com.linagora.tmail.james.jmap.model

import java.nio.charset.StandardCharsets

import org.assertj.core.api.Assertions.{assertThat, assertThatThrownBy}
import org.junit.jupiter.api.Test

class EmailSendIdempotencyTest {
  private def bytes(value: String): Array[Byte] =
    value.getBytes(StandardCharsets.UTF_8)

  @Test
  def stableForSameAccountKeyAndPayload(): Unit = {
    val first = EmailSendIdempotency.identity("alice", "retry-1", bytes("mime"), bytes("envelope"))
    val second = EmailSendIdempotency.identity("alice", "retry-1", bytes("mime"), bytes("envelope"))
    assertThat(first).isEqualTo(second)
    assertThat(first.keyDigest).hasSize(64)
    assertThat(first.payloadDigest).hasSize(64)
  }

  @Test
  def accountScopeAndLengthBoundariesAreUnambiguous(): Unit = {
    val one = EmailSendIdempotency.identity("ab", "c", bytes("mime"), bytes("env"))
    val two = EmailSendIdempotency.identity("a", "bc", bytes("mime"), bytes("env"))
    val anotherAccount = EmailSendIdempotency.identity("bob", "c", bytes("mime"), bytes("env"))
    assertThat(one.keyDigest).isNotEqualTo(two.keyDigest)
    assertThat(one.keyDigest).isNotEqualTo(anotherAccount.keyDigest)
  }

  @Test
  def detectsConflictingPayloadForSameRetryKey(): Unit = {
    val original = EmailSendIdempotency.identity("alice", "retry-1", bytes("mime"), bytes("env"))
    val changedMessage = EmailSendIdempotency.identity("alice", "retry-1", bytes("other"), bytes("env"))
    val changedEnvelope = EmailSendIdempotency.identity("alice", "retry-1", bytes("mime"), bytes("other"))
    assertThat(original.keyDigest).isEqualTo(changedMessage.keyDigest)
    assertThat(original.matchesPayload(changedMessage)).isFalse()
    assertThat(original.matchesPayload(changedEnvelope)).isFalse()
  }

  @Test
  def identityAndPayloadHashesAreDomainSeparated(): Unit = {
    // Even when all input fields are byte-for-byte equal, a submission key
    // cannot be substituted for a payload fingerprint.
    val identity = EmailSendIdempotency.identity("same", "same", bytes("same"), bytes("same"))
    assertThat(identity.keyDigest).isNotEqualTo(identity.payloadDigest)
  }

  @Test
  def rejectsMissingScopeOrKey(): Unit = {
    assertThatThrownBy(() => EmailSendIdempotency.identity("", "key", bytes("m"), bytes("e")))
      .isInstanceOf(classOf[IllegalArgumentException])
    assertThatThrownBy(() => EmailSendIdempotency.identity("alice", "", bytes("m"), bytes("e")))
      .isInstanceOf(classOf[IllegalArgumentException])
  }
}
