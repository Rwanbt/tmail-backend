package com.linagora.tmail.james;

import static com.linagora.tmail.james.TmailJmapBase.MESSAGE_ID_FACTORY;
import static com.linagora.tmail.james.app.PostgresTmailConfiguration.EventBusImpl.RABBITMQ;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.MIME_PAYLOAD;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.OWNER_ID;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.REQUEST_PAYLOAD;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.STATE;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.SUBMISSION_ID;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.SUBMISSION_KEY;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.TABLE_NAME;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

import org.apache.james.DockerOpenSearchExtension;
import org.apache.james.GuiceJamesServer;
import org.apache.james.JamesServerBuilder;
import org.apache.james.JamesServerExtension;
import org.apache.james.SearchConfiguration;
import org.apache.james.backends.postgres.PostgresExtension;
import org.apache.james.mailbox.model.MessageId;
import org.apache.james.mailbox.model.MultimailboxesSearchQuery;
import org.apache.james.mailbox.model.SearchQuery;
import org.apache.james.modules.MailboxProbeImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.tmail.blob.guice.BlobStoreConfiguration;
import com.linagora.tmail.james.app.PostgresTmailConfiguration;
import com.linagora.tmail.james.app.PostgresTmailServer;
import com.linagora.tmail.james.common.LinagoraEmailSendMethodContract;
import com.linagora.tmail.james.common.LinagoraEmailSendMethodContract$;
import com.linagora.tmail.james.jmap.firebase.FirebaseModuleChooserConfiguration;
import com.linagora.tmail.module.LinagoraTestJMAPServerModule;

import reactor.core.publisher.Flux;

/**
 * Unlike the default PostgreSQL Email/send test this runs a REAL RabbitMQ
 * spool (not ActiveMQ). TCP fault drops a real broker publisher confirm only
 * after the Basic.Publish frame and MIME reference have reached RabbitMQ.
 */
public class PostgresRabbitMQConfirmLossIntegrationTest implements LinagoraEmailSendMethodContract {
    private static final PostgresExtension POSTGRES = PostgresExtension.empty();
    private static final RabbitMQConfirmLossExtension RABBIT = new RabbitMQConfirmLossExtension();

    @RegisterExtension
    static JamesServerExtension james = new JamesServerBuilder<PostgresTmailConfiguration>(tmpDir ->
        PostgresTmailConfiguration.builder()
            .workingDirectory(tmpDir)
            .configurationFromClasspath()
            .blobStore(BlobStoreConfiguration.builder().postgres()
                .disableCache().deduplication().noCryptoConfig().disableSingleSave())
            .searchConfiguration(SearchConfiguration.openSearch())
            .firebaseModuleChooserConfiguration(FirebaseModuleChooserConfiguration.DISABLED)
            .eventBusImpl(RABBITMQ)
            .build())
        .server(configuration -> PostgresTmailServer.createServer(configuration)
            .overrideWith(new LinagoraTestJMAPServerModule()))
        .extension(POSTGRES)
        .extension(new DockerOpenSearchExtension())
        .extension(RABBIT)
        .lifeCycle(JamesServerExtension.Lifecycle.PER_CLASS)
        .build();

    @Override
    public MessageId randomMessageId() {
        return MESSAGE_ID_FACTORY.generate();
    }

    @Test
    void acceptedSpoolPublishWithLostConfirmKeepsMimeAndNeverRepublishes(GuiceJamesServer server) throws Exception {
        String key = UUID.randomUUID().toString();
        String request = LinagoraEmailSendMethodContract$.MODULE$.bobSendsAMailToAndre(server)
            .replace("\"K87\": {", "\"K87\": {\"idempotencyKey\": \"" + key + "\",");
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest((bobUsername().asString() + "\u0000" + key).getBytes(StandardCharsets.UTF_8)));

        RABBIT.arm();
        String first = given().body(request).when().post()
            .then().statusCode(200).extract().body().asString();
        assertThat(first).contains("\"notCreated\"", "\"K87\"",
            "Queue acceptance is uncertain; retrying may send this email twice");
        assertThat(RABBIT.spoolPublishes()).isEqualTo(1);
        assertThat(RABBIT.droppedConfirms()).as("a real Basic.Ack frame from RabbitMQ must be suppressed")
            .isPositive();

        var durable = POSTGRES.getDefaultPostgresExecutor()
            .executeRows(dsl -> Flux.from(dsl.select(STATE, MIME_PAYLOAD, REQUEST_PAYLOAD,
                    OWNER_ID, SUBMISSION_ID).from(TABLE_NAME)
                .where(SUBMISSION_KEY.eq(fingerprint))))
            .next().block();
        assertThat(durable).as("keyed outbox must exist in the real PostgreSQL database").isNotNull();
        assertThat(durable.get(STATE)).isEqualTo("acceptance_unknown");
        assertThat(durable.get(OWNER_ID)).isNull();
        assertThat(durable.get(REQUEST_PAYLOAD)).contains(key);
        assertThat(new String(durable.get(MIME_PAYLOAD), StandardCharsets.UTF_8))
            .contains("World domination");

        // The actual AMQP body is the James MailReferenceDTO including the
        // referenced header/body blob identifiers. It is NOT the email MIME.
        byte[] reference = RABBIT.publishedReference();
        assertThat(reference).as("real spool reference published to RabbitMQ").isNotNull();
        String referenceJson = new String(reference, StandardCharsets.UTF_8);
        assertThat(referenceJson).contains("\"headerBlobId\"", "\"bodyBlobId\"",
            durable.get(SUBMISSION_ID));

        // A real recipient delivery proves the accepted broker reference can
        // still retrieve the MIME from James's storage after the lost ACK.
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
            assertThat(server.getProbe(MailboxProbeImpl.class)
                .searchMessage(MultimailboxesSearchQuery.from(SearchQuery.of(SearchQuery.all())).build(),
                    andreUsername().asString(), 100)).hasSize(1));

        String replay = given().body(request).when().post()
            .then().statusCode(200).extract().body().asString();
        assertThat(replay).contains("\"notCreated\"", "\"K87\"");
        assertThat(RABBIT.spoolPublishes()).as("same idempotencyKey must never publish another spool reference")
            .isEqualTo(1);
        assertThat(server.getProbe(MailboxProbeImpl.class)
            .searchMessage(MultimailboxesSearchQuery.from(SearchQuery.of(SearchQuery.all())).build(),
                bobUsername().asString(), 100)).hasSize(1);
        assertThat(server.getProbe(MailboxProbeImpl.class)
            .searchMessage(MultimailboxesSearchQuery.from(SearchQuery.of(SearchQuery.all())).build(),
                andreUsername().asString(), 100)).hasSize(1);
    }
}
