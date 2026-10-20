package com.linagora.tmail.james.jmap.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EmailSendOutboxStateTest {
  import EmailSendOutboxState._

  @Test
  def onlyOneOwnerMayClaimPreparedSubmission(): Unit = {
    assertThat(claim(Prepared, "node-1")).isEqualTo(Transition(Dispatching("node-1")))
    assertThat(claim(Dispatching("node-1"), "node-2")).isEqualTo(Conflict)
    assertThat(claim(Prepared, "")).isEqualTo(Conflict)
  }

  @Test
  def onlyOwnerMayAcknowledgeOrMarkUncertain(): Unit = {
    val pending = Dispatching("node-1")
    assertThat(acknowledge(pending, "node-2")).isEqualTo(Conflict)
    assertThat(acknowledgementLost(pending, "node-2")).isEqualTo(Conflict)
    assertThat(acknowledge(pending, "node-1")).isEqualTo(Transition(Accepted))
    assertThat(acknowledgementLost(pending, "node-1")).isEqualTo(Transition(AcceptanceUnknown))
  }

  @Test
  def restartAndLostAcknowledgementNeverAuthorizeResend(): Unit = {
    assertThat(recover(Dispatching("dead-node"))).isEqualTo(AcceptanceUnknown)
    assertThat(mayPublish(AcceptanceUnknown)).isFalse()
    assertThat(mayPublish(Accepted)).isFalse()
    assertThat(claim(AcceptanceUnknown, "new-node")).isEqualTo(Conflict)
    assertThat(recover(Prepared)).isEqualTo(Prepared)
    assertThat(mayPublish(Prepared)).isTrue()
  }

  @Test
  def provenRejectionCannotBeInferredOnceDispatchStarted(): Unit = {
    assertThat(rejectBeforePublish(Prepared)).isEqualTo(Transition(RejectedBeforePublish))
    assertThat(rejectBeforePublish(Dispatching("node-1"))).isEqualTo(Conflict)
    assertThat(rejectBeforePublish(AcceptanceUnknown)).isEqualTo(Conflict)
    assertThat(rejectBeforePublish(Accepted)).isEqualTo(Conflict)
    assertThat(mayPublish(RejectedBeforePublish)).isFalse()
  }
}
