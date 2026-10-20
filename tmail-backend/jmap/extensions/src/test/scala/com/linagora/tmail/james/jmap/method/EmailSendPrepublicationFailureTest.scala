package com.linagora.tmail.james.jmap.method

import com.linagora.tmail.james.jmap.model.EmailSubmissionOutcomeUnknownException
import jakarta.mail.MessagingException
import org.apache.james.queue.api.MailQueue
import org.assertj.core.api.Assertions.{assertThat, assertThatThrownBy}
import org.junit.jupiter.api.Test
import reactor.core.scala.publisher.SMono

class EmailSendPrepublicationFailureTest {
  @Test
  def synchronousBlobFailureIsProvablyBeforePublication(): Unit = {
    val failure = new MailQueue.MailQueueException("Error while saving blob",
      new MessagingException("synthetic blob storage failure"))
    assertThat(EmailSendMethod.isProvenPrePublicationFailure(failure)).isTrue()
  }

  @Test
  def failedCompensationNeverMasksTheUncertainSendOutcome(): Unit = {
    val queueError = new IllegalStateException("synthetic queue failure")
    val uncertain = EmailSubmissionOutcomeUnknownException(queueError)
    val failedDeletion = SMono.error[String](new IllegalStateException("mailbox cleanup unavailable"))
    assertThatThrownBy(() =>
      EmailSendMethod.preserveUncertainOnCleanupFailure(failedDeletion, uncertain).block())
      .isSameAs(uncertain)
  }

  @Test
  def arbitraryQueueFailureNeverProvesRejection(): Unit = {
    assertThat(EmailSendMethod.isProvenPrePublicationFailure(
      new IllegalStateException("synthetic enqueue rejection before acceptance"))).isFalse()
    assertThat(EmailSendMethod.isProvenPrePublicationFailure(
      new MailQueue.MailQueueException("Publish was not acked"))).isFalse()
    assertThat(EmailSendMethod.isProvenPrePublicationFailure(
      new MailQueue.MailQueueException("Timeout enqueueing mail", new MessagingException("timeout")))).isFalse()
  }
}
