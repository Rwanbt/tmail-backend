package com.linagora.tmail.james.jmap.method

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import com.datastax.oss.driver.api.core.{CqlSession, DefaultConsistencyLevel}
import com.datastax.oss.driver.api.core.`type`.DataTypes
import com.datastax.oss.driver.api.core.cql.{BoundStatement, Row}
import com.google.inject.AbstractModule
import com.google.inject.multibindings.Multibinder
import jakarta.inject.Inject
import org.apache.james.backends.cassandra.components.CassandraDataDefinition
import play.api.libs.json.{JsArray, JsString, Json}
import reactor.core.publisher.Mono
import reactor.core.scala.publisher.SMono

/**
 * A persistent, single-partition LWT fence shared by all distributed James nodes.
 * The claim is committed before broker publication. An interrupted claim remains
 * dispatching indefinitely; there is deliberately no automatic reclaim or retry.
 */
object CassandraEmailSendOutbox {
  private val table = "tmail_email_send_outbox"
  // A large MIME should use an external durable blob store in a future design.
  // Reject oversized opted-in requests instead of silently overloading a partition.
  private val maxPayloadBytes = 4 * 1024 * 1024

  val module: CassandraDataDefinition = CassandraDataDefinition.table(table)
    .comment("Durable Email/send identity, MIME and publication fencing")
    .statement(statement => _ => statement
      .withPartitionKey("submission_key", DataTypes.TEXT)
      .withColumn("payload_digest", DataTypes.TEXT)
      .withColumn("request_payload", DataTypes.TEXT)
      .withColumn("mime_payload", DataTypes.BLOB)
      .withColumn("mail_from", DataTypes.TEXT)
      .withColumn("recipients_json", DataTypes.TEXT)
      .withColumn("auth_user", DataTypes.TEXT)
      .withColumn("submission_id", DataTypes.TEXT)
      .withColumn("owner_id", DataTypes.TEXT)
      .withColumn("state", DataTypes.TEXT)
      .withColumn("version", DataTypes.BIGINT))
    .build

  private def validFingerprint(value: String): Boolean = value != null && value.matches("[0-9a-f]{64}")
  private def validText(value: String): Boolean = value != null && value.nonEmpty
  private def safeSize(bytes: Array[Byte]): Boolean = bytes != null && bytes.nonEmpty && bytes.length <= maxPayloadBytes
}

class CassandraEmailSendOutbox @Inject()(session: CqlSession) extends EmailSendOutbox {
  import CassandraEmailSendOutbox._

  override val enabled: Boolean = true

  private val insert = session.prepare(s"INSERT INTO $table (submission_key, payload_digest, request_payload, state, version) VALUES (?, ?, ?, 'prepared', 0) IF NOT EXISTS")
  private val readReservation = session.prepare(s"SELECT payload_digest, request_payload FROM $table WHERE submission_key = ?")
  private val stage = session.prepare(s"UPDATE $table SET mime_payload = ?, mail_from = ?, recipients_json = ?, auth_user = ?, submission_id = ?, owner_id = ?, state = 'dispatching', version = 1 WHERE submission_key = ? IF state = 'prepared' AND version = 0")
  private val readClaim = session.prepare(s"SELECT mime_payload, mail_from, recipients_json, auth_user, submission_id, owner_id, state, version FROM $table WHERE submission_key = ?")
  private val complete = session.prepare(s"UPDATE $table SET state = ?, owner_id = null, version = 2 WHERE submission_key = ? IF state = 'dispatching' AND version = 1 AND owner_id = ?")

  private def execute(statement: BoundStatement, conditional: Boolean = false): SMono[com.datastax.oss.driver.api.core.cql.AsyncResultSet] = {
    val quorum = statement.setConsistencyLevel(DefaultConsistencyLevel.LOCAL_QUORUM)
    val prepared = if (conditional) quorum.setSerialConsistencyLevel(DefaultConsistencyLevel.LOCAL_SERIAL) else quorum
    SMono.fromPublisher(Mono.fromCompletionStage(() => session.executeAsync(prepared)))
  }

  private def load(key: String): SMono[Row] =
    execute(readReservation.bind(key)).flatMap(result =>
      SMono.fromPublisher(Mono.justOrEmpty(result.one())))

  override def reserve(key: String, digest: String, requestJson: String): SMono[Boolean] = {
    val requestBytes = Option(requestJson).map(_.getBytes(StandardCharsets.UTF_8)).orNull
    if (!validFingerprint(key) || !validFingerprint(digest) || !safeSize(requestBytes)) {
      SMono.error(new IllegalArgumentException("invalid distributed outbox reservation"))
    } else {
      execute(insert.bind(key, digest, requestJson), conditional = true).flatMap(result =>
        load(key)
          .switchIfEmpty(SMono.error(new IllegalStateException("outbox reservation not visible")))
          .map(row => {
            if (row.getString("payload_digest") != digest || row.getString("request_payload") != requestJson) {
              throw new IllegalArgumentException("submission key reused with different payload")
            }
            result.wasApplied()
          }))
    }
  }

  override def stageAndClaim(key: String, owner: String, mail: OutboxMail): SMono[OutboxMail] = {
    if (!validFingerprint(key) || !validText(owner) || mail == null || !safeSize(mail.mime)
      || !validText(mail.from) || mail.recipients.isEmpty || !validText(mail.authUser)
      || !validText(mail.submissionId)) {
      SMono.error(new IllegalArgumentException("invalid distributed outbox payload"))
    } else {
      val recipients = Json.stringify(JsArray(mail.recipients.map(JsString.apply)))
      execute(stage.bind(ByteBuffer.wrap(mail.mime), mail.from, recipients,
        mail.authUser, mail.submissionId, owner, key), conditional = true)
        .flatMap(result => if (!result.wasApplied()) {
          SMono.error(new IllegalStateException("outbox dispatch claim unavailable"))
        } else {
          execute(readClaim.bind(key)).flatMap(read =>
            SMono.fromPublisher(Mono.justOrEmpty(read.one())))
            .filter(row => row.getString("owner_id") == owner && row.getString("state") == "dispatching"
              && row.getLong("version") == 1L)
            .switchIfEmpty(SMono.error(new IllegalStateException("outbox durable claim missing")))
            .map(row => {
              val buffer = row.getByteBuffer("mime_payload").duplicate()
              val bytes = new Array[Byte](buffer.remaining())
              buffer.get(bytes)
              OutboxMail(bytes, row.getString("mail_from"),
                Json.parse(row.getString("recipients_json")).as[Seq[String]],
                row.getString("auth_user"), row.getString("submission_id"))
            })
        })
    }
  }

  override def finish(key: String, owner: String, accepted: Boolean): SMono[Unit] = {
    if (!validFingerprint(key) || !validText(owner)) {
      SMono.error(new IllegalArgumentException("invalid outbox completion owner"))
    } else {
      execute(complete.bind(if (accepted) "accepted" else "acceptance_unknown", key, owner), conditional = true)
        .flatMap(result => if (result.wasApplied()) SMono.just(())
          else SMono.error(new IllegalStateException("outbox finish CAS lost")))
    }
  }
}

/** Bind the durable fence and provision its table in the distributed server. */
class CassandraEmailSendOutboxModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[EmailSendOutbox]).to(classOf[CassandraEmailSendOutbox])
    Multibinder.newSetBinder(binder(), classOf[CassandraDataDefinition])
      .addBinding().toInstance(CassandraEmailSendOutbox.module)
  }
}
