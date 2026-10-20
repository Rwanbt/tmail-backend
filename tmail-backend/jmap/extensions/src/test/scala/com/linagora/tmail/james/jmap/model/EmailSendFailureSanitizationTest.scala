package com.linagora.tmail.james.jmap.model

import com.linagora.tmail.james.jmap.json.{EmailSendSerializer, Fixture}
import eu.timepit.refined.auto._
import org.apache.james.jmap.core.UuidState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import play.api.libs.json.Json

class EmailSendFailureSanitizationTest {
  @Test
  def internalQueueFailureMustNotExposeCredentialsOrInfrastructureDetails(): Unit = {
    val privateDiagnostic = "connection to jdbc:postgresql://db.internal:5432/mail failed password=private-token"
    val result = EmailSendResults.notCreated(EmailSendCreationId("K87"), new IllegalStateException(privateDiagnostic))

    val response = Json.stringify(EmailSendSerializer.serializeEmailSendResponse(
      result.asResponse(Fixture.ACCOUNT_ID, UuidState.INSTANCE)))

    assertThat(response)
      .contains("\"type\":\"serverFail\"")
      .contains("Email submission failed")
      .doesNotContain("private-token", "jdbc:postgresql", "db.internal")
  }

  @Test
  def ambiguousQueueAcceptanceWarnsAgainstRetriesWithoutExposingBrokerSecrets(): Unit = {
    val brokerDetail = "rabbitmq://queue.internal:5672 user=alice password=private-token"
    val result = EmailSendResults.notCreated(
      EmailSendCreationId("K87"),
      EmailSubmissionOutcomeUnknownException(new IllegalStateException(brokerDetail)))

    val response = Json.stringify(EmailSendSerializer.serializeEmailSendResponse(
      result.asResponse(Fixture.ACCOUNT_ID, UuidState.INSTANCE)))

    assertThat(response)
      .contains("\"type\":\"serverFail\"")
      .contains(EmailSubmissionOutcomeUnknownException.MESSAGE)
      .doesNotContain("private-token", "rabbitmq", "queue.internal")
  }

  @Test
  def missingSenderStillReturnsTheActionableValidationDescription(): Unit = {
    val result = EmailSendResults.notCreated(EmailSendCreationId("K87"), new MissingEnvelopeFromException)
    val response = Json.stringify(EmailSendSerializer.serializeEmailSendResponse(
      result.asResponse(Fixture.ACCOUNT_ID, UuidState.INSTANCE)))

    assertThat(response)
      .contains("\"type\":\"serverFail\"")
      .contains("\"description\":\"Implicit envelope detection requires a from field\"")
  }
}
