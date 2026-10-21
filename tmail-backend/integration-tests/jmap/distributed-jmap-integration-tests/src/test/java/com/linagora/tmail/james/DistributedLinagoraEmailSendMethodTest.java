/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  https://twake-mail.com/                                         *
 *  https://linagora.com                                            *
 *                                                                  *
 *  This file is subject to The Affero Gnu Public License           *
 *  version 3.                                                      *
 *                                                                  *
 *  https://www.gnu.org/licenses/agpl-3.0.en.html                   *
 *                                                                  *
 *  This program is distributed in the hope that it will be         *
 *  useful, but WITHOUT ANY WARRANTY; without even the implied      *
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR         *
 *  PURPOSE. See the GNU Affero General Public License for          *
 *  more details.                                                   *
 ********************************************************************/

package com.linagora.tmail.james;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Durations.TEN_SECONDS;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.james.GuiceJamesServer;
import org.apache.james.JamesServerBuilder;
import org.apache.james.JamesServerExtension;
import org.apache.james.backends.redis.RedisExtension;
import org.apache.james.junit.categories.BasicFeature;
import org.apache.james.mailbox.cassandra.ids.CassandraMessageId;
import org.apache.james.mailbox.model.MessageId;
import org.apache.james.mailbox.model.MultimailboxesSearchQuery;
import org.apache.james.mailbox.model.SearchQuery;
import org.apache.james.modules.AwsS3BlobStoreExtension;
import org.apache.james.modules.MailboxProbeImpl;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.linagora.tmail.blob.guice.BlobStoreConfiguration;
import com.linagora.tmail.james.app.CassandraExtension;
import com.linagora.tmail.james.app.DistributedEncryptedMailboxModule;
import com.linagora.tmail.james.app.DistributedJamesConfiguration;
import com.linagora.tmail.james.app.DistributedServer;
import com.linagora.tmail.james.app.DockerOpenSearchExtension;
import com.linagora.tmail.james.app.EventBusKeysChoice;
import com.linagora.tmail.james.app.RabbitMQExtension;
import com.linagora.tmail.james.common.LinagoraEmailSendMethodContract;
import com.linagora.tmail.james.common.LinagoraEmailSendMethodContract$;
import com.linagora.tmail.james.jmap.firebase.FirebaseModuleChooserConfiguration;
import com.linagora.tmail.module.LinagoraTestJMAPServerModule;

public class DistributedLinagoraEmailSendMethodTest implements LinagoraEmailSendMethodContract {

    public static final CassandraMessageId.Factory MESSAGE_ID_FACTORY = new CassandraMessageId.Factory();

    @RegisterExtension
    static JamesServerExtension
        testExtension = new JamesServerBuilder<DistributedJamesConfiguration>(tmpDir ->
        DistributedJamesConfiguration.builder()
            .workingDirectory(tmpDir)
            .configurationFromClasspath()
            .blobStore(BlobStoreConfiguration.builder()
                .s3()
                .noSecondaryS3BlobStore()
                .disableCache()
                .deduplication()
                .noCryptoConfig()
                .disableSingleSave())
            .eventBusKeysChoice(EventBusKeysChoice.REDIS)
            .firebaseModuleChooserConfiguration(FirebaseModuleChooserConfiguration.DISABLED)
            .build())
        .extension(new DockerOpenSearchExtension())
        .extension(new CassandraExtension())
        .extension(new RabbitMQExtension())
        .extension(new RedisExtension())
        .extension(new AwsS3BlobStoreExtension())
        .server(configuration -> DistributedServer.createServer(configuration)
            .overrideWith(new DistributedEncryptedMailboxModule())
            .overrideWith(new LinagoraTestJMAPServerModule()))
        .lifeCycle(JamesServerExtension.Lifecycle.PER_CLASS)
        .build();

    @Override
    public MessageId randomMessageId() {
        return MESSAGE_ID_FACTORY.of(Uuids.timeBased());
    }

    @Test
    @Tag(BasicFeature.TAG)
    void durableIdentityShouldFenceSecondEmailSendOnDistributedCassandraAndRabbitMQ(GuiceJamesServer server) {
        String idempotencyKey = UUID.randomUUID().toString();
        String request = LinagoraEmailSendMethodContract$.MODULE$.bobSendsAMailToAndre(server)
            .replace("\"K87\": {", "\"K87\": {\"idempotencyKey\": \"" + idempotencyKey + "\",");

        String created = given().body(request).when().post()
            .then().statusCode(200).extract().body().asString();
        assertThat(created).contains("\"created\"", "\"K87\"");

        // OpenSearch indexes the first append asynchronously. Prove it exists
        // and record its durable identity before attempting the replay.
        MessageId originalId = Awaitility.await().atMost(TEN_SECONDS).until(
            () -> server.getProbe(MailboxProbeImpl.class)
                .searchMessage(MultimailboxesSearchQuery.from(SearchQuery.of(SearchQuery.all())).build(),
                    bobUsername().asString(), 100),
            messages -> messages.size() == 1).iterator().next();

        String replay = given().body(request).when().post()
            .then().statusCode(200).extract().body().asString();
        assertThat(replay).contains("\"notCreated\"", "\"K87\"",
            "Queue acceptance is uncertain; retrying may send this email twice");

        // Reject a second append and preserve the exact original message ID.
        Awaitility.await().atMost(TEN_SECONDS).untilAsserted(() ->
            assertThat(server.getProbe(MailboxProbeImpl.class)
                .searchMessage(MultimailboxesSearchQuery.from(SearchQuery.of(SearchQuery.all())).build(),
                    bobUsername().asString(), 100))
                .as("durable replay must not append a second sender copy")
                .containsExactly(originalId));
    }

    @Test
    @Tag(BasicFeature.TAG)
    void concurrentKeyedJmapRequestsDeliverOnlyOneRecipientCopyThroughRealRabbitMQ(GuiceJamesServer server)
        throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String request = LinagoraEmailSendMethodContract$.MODULE$.bobSendsAMailToAndre(server)
            .replace("\"K87\": {", "\"K87\": {\"idempotencyKey\": \"" + idempotencyKey + "\",");

        // Two independent HTTP invocations race for the SAME Cassandra LWT
        // reservation, then the genuine James/RabbitMQ delivery path runs.
        CountDownLatch releaseBoth = new CountDownLatch(1);
        List<String> responses;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = CompletableFuture.supplyAsync(() -> sendWhenReleased(releaseBoth, request), executor);
            var second = CompletableFuture.supplyAsync(() -> sendWhenReleased(releaseBoth, request), executor);
            releaseBoth.countDown();
            responses = List.of(first.get(2, TimeUnit.MINUTES), second.get(2, TimeUnit.MINUTES));
        }
        assertThat(responses.stream().filter(response -> response.contains("\"created\"")).count())
            .as("exactly one keyed Email/send may be accepted")
            .isEqualTo(1L);
        assertThat(responses.stream().filter(response -> response.contains("\"notCreated\"")).count())
            .as("the losing HTTP caller must not be authorized to send")
            .isEqualTo(1L);
        assertThat(responses.stream().filter(response -> response.contains("Queue acceptance is uncertain")).count())
            .isEqualTo(1L);

        // The original test checked the sender's Sent copy only. In addition
        // prove actual RabbitMQ delivery to the recipient, and keep both
        // mailboxes at ONE message across an observation interval.
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(server.getProbe(MailboxProbeImpl.class)
                .searchMessage(MultimailboxesSearchQuery.from(SearchQuery.of(SearchQuery.all())).build(),
                    bobUsername().asString(), 100))
                .as("one sender append across concurrent JMAP calls").hasSize(1);
            assertThat(server.getProbe(MailboxProbeImpl.class)
                .searchMessage(MultimailboxesSearchQuery.from(SearchQuery.of(SearchQuery.all())).build(),
                    andreUsername().asString(), 100))
                .as("one genuine delivered recipient message").hasSize(1);
        });
        Awaitility.await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(15))
            .untilAsserted(() -> {
                assertThat(server.getProbe(MailboxProbeImpl.class)
                    .searchMessage(MultimailboxesSearchQuery.from(SearchQuery.of(SearchQuery.all())).build(),
                        bobUsername().asString(), 100)).hasSize(1);
                assertThat(server.getProbe(MailboxProbeImpl.class)
                    .searchMessage(MultimailboxesSearchQuery.from(SearchQuery.of(SearchQuery.all())).build(),
                        andreUsername().asString(), 100)).hasSize(1);
            });
    }

    private static String sendWhenReleased(CountDownLatch releaseBoth, String request) {
        try {
            if (!releaseBoth.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent Email/send start timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent Email/send interrupted", e);
        }
        return given().body(request).when().post()
            .then().statusCode(200).extract().body().asString();
    }

}
