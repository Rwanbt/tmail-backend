package com.linagora.tmail.james.jmap.method

import com.google.inject.AbstractModule
import com.google.inject.multibindings.Multibinder
import com.linagora.tmail.jmap.postgres.{EmailSendOutboxDao, EmailSendOutboxDataDefinition}
import jakarta.inject.Inject
import org.apache.james.backends.postgres.PostgresDataDefinition
import org.apache.james.backends.postgres.utils.PostgresExecutor
import org.apache.james.backends.postgres.utils.PostgresExecutor.DEFAULT_INJECT
import play.api.libs.json.{JsArray, JsString, Json}
import reactor.core.scala.publisher.SMono

import jakarta.inject.Named
import scala.jdk.CollectionConverters._

class PostgresEmailSendOutbox @Inject()(@Named(DEFAULT_INJECT) executor: PostgresExecutor) extends EmailSendOutbox {
  private val dao = new EmailSendOutboxDao(executor)
  override val enabled: Boolean = true

  override def reserve(key: String, digest: String, requestJson: String): SMono[Boolean] =
    SMono.fromPublisher(dao.reserve(key, digest, requestJson))
      .map(_ == EmailSendOutboxDao.RegistrationDecision.NEW_SUBMISSION)

  override def stageAndClaim(key: String, owner: String, mail: OutboxMail): SMono[OutboxMail] = {
    val recipients = Json.stringify(JsArray(mail.recipients.map(JsString.apply)))
    SMono.fromPublisher(dao.stageAndClaim(key, owner, mail.mime, mail.from,
        recipients, mail.authUser, mail.submissionId))
      .flatMap(updated => if (updated == 1) {
        SMono.fromPublisher(dao.loadClaimed(key, owner))
          .switchIfEmpty(SMono.error(new IllegalStateException("outbox durable claim missing")))
          .map(stored => OutboxMail(stored.mime(), stored.from(),
            Json.parse(stored.recipientsJson()).as[Seq[String]],
            stored.authUser(), stored.submissionId()))
      } else {
        SMono.error(new IllegalStateException("outbox dispatch claim unavailable"))
      })
  }

  override def finish(key: String, owner: String, accepted: Boolean): SMono[Unit] =
    SMono.fromPublisher(dao.finish(key, 1L, owner, accepted))
      .flatMap(affected => if (affected == 1) SMono.just(())
        else SMono.error(new IllegalStateException("outbox finish CAS lost")))
}

/** Override DisabledEmailSendOutbox only in the Postgres server Guice graph. */
class PostgresEmailSendOutboxModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[EmailSendOutbox]).to(classOf[PostgresEmailSendOutbox])
    Multibinder.newSetBinder(binder(), classOf[PostgresDataDefinition])
      .addBinding().toInstance(EmailSendOutboxDataDefinition.MODULE)
  }
}
