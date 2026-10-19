package com.linagora.tmail.james.jmap.method

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import org.assertj.core.api.Assertions.{assertThat, assertThatThrownBy}
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

class EmailSendQueueCleanupTest {
  @Test
  def shouldReleaseMailAfterSuccessfulEnqueue(): Unit = {
    val cleanupCalls = new AtomicInteger()
    val result = EmailSendMethod.withResourceCleanup(
      "mail",
      (_: String) => Mono.just("accepted"),
      (_: String) => cleanupCalls.incrementAndGet())

    assertThat(result.block()).isEqualTo("accepted")
    assertThat(cleanupCalls.get()).isEqualTo(1)
  }

  @Test
  def shouldReleaseMailWhenEnqueueFails(): Unit = {
    val cleanupCalls = new AtomicInteger()
    val result = EmailSendMethod.withResourceCleanup(
      "mail",
      (_: String) => Mono.error[String](new IllegalStateException("enqueue failed")),
      (_: String) => cleanupCalls.incrementAndGet())

    assertThatThrownBy(() => result.block())
      .isInstanceOf(classOf[IllegalStateException])
      .hasMessage("enqueue failed")
    assertThat(cleanupCalls.get()).isEqualTo(1)
  }

  @Test
  def shouldReleaseMailWhenEnqueueIsCancelled(): Unit = {
    val cleanedUp = new CountDownLatch(1)
    val result = EmailSendMethod.withResourceCleanup(
      "mail",
      (_: String) => Mono.never[String](),
      (_: String) => cleanedUp.countDown())

    val subscription = result.subscribe()
    subscription.dispose()
    assertThat(cleanedUp.await(5, TimeUnit.SECONDS)).isTrue()
  }
}
