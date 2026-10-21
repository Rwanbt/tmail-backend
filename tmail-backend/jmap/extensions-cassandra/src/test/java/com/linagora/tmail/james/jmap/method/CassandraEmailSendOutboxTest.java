package com.linagora.tmail.james.jmap.method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import org.apache.james.backends.cassandra.CassandraCluster;
import org.apache.james.backends.cassandra.CassandraClusterExtension;
import org.apache.james.backends.cassandra.init.ClusterFactory;
import org.apache.james.backends.cassandra.init.configuration.KeyspaceConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;

import scala.jdk.javaapi.CollectionConverters;

class CassandraEmailSendOutboxTest {
    private static final String DIGEST = "b".repeat(64);

    @RegisterExtension
    static CassandraClusterExtension cassandra =
        new CassandraClusterExtension(CassandraEmailSendOutbox.module());

    private CassandraEmailSendOutbox nodeA;
    private CassandraEmailSendOutbox nodeB;
    private CqlSession independentConnection;
    private String key;

    @BeforeEach
    void setUp(CassandraCluster cluster) {
        key = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "");
        // Independent driver sessions emulate two separate James nodes against
        // the same real Cassandra Testcontainers database.
        independentConnection = ClusterFactory.create(cluster.getClusterConfiguration(),
            KeyspaceConfiguration.builder().keyspace("testing").replicationFactor(1).disableDurableWrites());
        nodeA = new CassandraEmailSendOutbox(cluster.getConf());
        nodeB = new CassandraEmailSendOutbox(independentConnection);
    }

    @AfterEach
    void closeIndependentConnection() {
        independentConnection.close();
    }

    @Test
    void onlyOneNodeCanReserveAndDispatchAndReplaysStayBlocked() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = CompletableFuture.supplyAsync(() -> nodeA.reserve(key, DIGEST, "{\"hello\":1}").asJava().block(), pool);
            var second = CompletableFuture.supplyAsync(() -> nodeB.reserve(key, DIGEST, "{\"hello\":1}").asJava().block(), pool);
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }

        OutboxMail mail = new OutboxMail("raw MIME".getBytes(StandardCharsets.UTF_8),
            "alice@example.com", CollectionConverters.asScala(List.of("bob@example.com")).toSeq(),
            "alice@example.com", "stable-submission");

        OutboxMail persisted = nodeA.stageAndClaim(key, "node-A", mail).asJava().block();
        assertThat(persisted.mime()).containsExactly(mail.mime());
        assertThat(persisted.from()).isEqualTo("alice@example.com");

        assertThatThrownBy(() -> nodeB.stageAndClaim(key, "node-B", mail).asJava().block())
            .hasMessageContaining("outbox dispatch claim unavailable");

        nodeA.finish(key, "node-A", false).asJava().block();
        assertThatThrownBy(() -> nodeB.stageAndClaim(key, "node-B", mail).asJava().block())
            .hasMessageContaining("outbox dispatch claim unavailable");
        assertThat(nodeB.reserve(key, DIGEST, "{\"hello\":1}").asJava().block()).isEqualTo(false);
        assertThatThrownBy(() -> nodeA.finish(key, "node-A", true).asJava().block())
            .hasMessageContaining("outbox finish CAS lost");
    }

    @Test
    void payloadConflictsFailClosedAcrossNodes() {
        assertThat(nodeA.reserve(key, DIGEST, "{\"hello\":1}").asJava().block()).isEqualTo(true);
        assertThatThrownBy(() -> nodeB.reserve(key, "c".repeat(64), "{\"hello\":2}").asJava().block())
            .hasMessageContaining("submission key reused with different payload");
    }

    @Test
    void unknownOwnerCannotCompleteAnotherNodesClaim() {
        nodeA.reserve(key, DIGEST, "{\"hello\":1}").asJava().block();
        OutboxMail mail = new OutboxMail("MIME".getBytes(StandardCharsets.UTF_8), "a@example.com",
            CollectionConverters.asScala(List.of("b@example.com")).toSeq(), "a@example.com", "id");
        nodeA.stageAndClaim(key, "owner-1", mail).asJava().block();
        assertThatThrownBy(() -> nodeB.finish(key, "owner-2", true).asJava().block())
            .hasMessageContaining("outbox finish CAS lost");
        nodeA.finish(key, "owner-1", true).asJava().block();
        assertThat(nodeB.reserve(key, DIGEST, "{\"hello\":1}").asJava().block()).isEqualTo(false);
    }

    @Test
    void stalePreparedReservationIsPermanentlyFencedAcrossNodesWithoutInventingMime() {
        seedStale("prepared", 0L, null, null);
        // A previous process may have appended a sender copy before crashing.
        // No replay may claim this original version-0 reservation.
        assertThat(nodeB.reserve(key, DIGEST, "{\"hello\":1}").asJava().block()).isFalse();
        Row quarantined = row();
        assertThat(quarantined.getString("state")).isEqualTo("prepared");
        assertThat(quarantined.getLong("version")).isEqualTo(1L);
        assertThat(quarantined.isNull("mime_payload")).isTrue();
        assertThat(quarantined.isNull("owner_id")).isTrue();

        OutboxMail late = mail("late MIME");
        assertThatThrownBy(() -> nodeA.stageAndClaim(key, "late-original", late).asJava().block())
            .hasMessageContaining("outbox dispatch claim unavailable");
        assertThat(nodeB.reserve(key, DIGEST, "{\"hello\":1}").asJava().block()).isFalse();
        assertThat(row().getLong("version")).isEqualTo(1L);
    }

    @Test
    void staleDispatchIsQuarantinedButOriginalMimeAndEnvelopeRemainForReconciliation() {
        byte[] original = "Subject: accepted-possibly\r\n\r\nOriginal MIME"
            .getBytes(StandardCharsets.UTF_8);
        seedStale("dispatching", 1L, "crashed-owner", original);

        assertThat(nodeB.reserve(key, DIGEST, "{\"hello\":1}").asJava().block()).isFalse();
        Row quarantined = row();
        assertThat(quarantined.getString("state")).isEqualTo("acceptance_unknown");
        assertThat(quarantined.getLong("version")).isEqualTo(2L);
        assertThat(quarantined.isNull("owner_id")).isTrue();
        ByteBuffer stored = quarantined.getByteBuffer("mime_payload").duplicate();
        byte[] persisted = new byte[stored.remaining()];
        stored.get(persisted);
        assertThat(persisted).containsExactly(original);
        assertThat(quarantined.getString("submission_id")).isEqualTo("stable-submission");
        assertThat(quarantined.getString("mail_from")).isEqualTo("alice@example.com");
        assertThat(quarantined.getString("recipients_json")).isEqualTo("[\"bob@example.com\"]");

        assertThatThrownBy(() -> nodeA.finish(key, "crashed-owner", true).asJava().block())
            .hasMessageContaining("outbox finish CAS lost");
        assertThatThrownBy(() -> nodeB.stageAndClaim(key, "replay-owner", mail("dangerous replay"))
            .asJava().block()).hasMessageContaining("outbox dispatch claim unavailable");
        assertThat(nodeB.reserve(key, DIGEST, "{\"hello\":1}").asJava().block()).isFalse();
        assertThat(row().getLong("version")).isEqualTo(2L);
    }

    @Test
    void recentDispatchAndTerminalSuccessCannotBeQuarantinedByConcurrentReplay() {
        assertThat(nodeA.reserve(key, DIGEST, "{\"hello\":1}").asJava().block()).isTrue();
        nodeA.stageAndClaim(key, "active-owner", mail("live MIME")).asJava().block();

        assertThat(nodeB.reserve(key, DIGEST, "{\"hello\":1}").asJava().block()).isFalse();
        assertThat(row().getString("state")).isEqualTo("dispatching");
        assertThat(row().getLong("version")).isEqualTo(1L);
        assertThat(row().getString("owner_id")).isEqualTo("active-owner");

        nodeA.finish(key, "active-owner", true).asJava().block();
        assertThat(nodeB.reserve(key, DIGEST, "{\"hello\":1}").asJava().block()).isFalse();
        assertThat(row().getString("state")).isEqualTo("accepted");
        assertThat(row().getLong("version")).isEqualTo(2L);
    }

    @Test
    void simultaneousReplaysOfAbandonedDispatchNeverRestorePublishPermission() throws Exception {
        byte[] mime = "Original immutable MIME".getBytes(StandardCharsets.UTF_8);
        seedStale("dispatching", 1L, "disconnected-node", mime);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = CompletableFuture.supplyAsync(
                () -> nodeA.reserve(key, DIGEST, "{\"hello\":1}").asJava().block(), pool);
            var b = CompletableFuture.supplyAsync(
                () -> nodeB.reserve(key, DIGEST, "{\"hello\":1}").asJava().block(), pool);
            assertThat(List.of(a.get(), b.get())).containsExactly(false, false);
        }
        Row quarantined = row();
        assertThat(quarantined.getString("state")).isEqualTo("acceptance_unknown");
        assertThat(quarantined.getLong("version")).isEqualTo(2L);
        assertThat(quarantined.isNull("owner_id")).isTrue();
        assertThatThrownBy(() -> nodeA.stageAndClaim(key, "node-after-crash", mail("repeat"))
            .asJava().block()).hasMessageContaining("outbox dispatch claim unavailable");
    }

    private OutboxMail mail(String body) {
        return new OutboxMail(body.getBytes(StandardCharsets.UTF_8), "alice@example.com",
            CollectionConverters.asScala(List.of("bob@example.com")).toSeq(),
            "alice@example.com", "stable-submission");
    }

    private void seedStale(String state, long version, String owner, byte[] mime) {
        // Create a genuinely aged Cassandra state cell with the driver, not a
        // fake clock or mocked CAS. This is a synthetic, test-only reservation.
        long twoHoursAgoMicros = Instant.now().minus(Duration.ofHours(2)).toEpochMilli() * 1000L;
        independentConnection.execute(independentConnection.prepare(
            "INSERT INTO tmail_email_send_outbox (submission_key, payload_digest, request_payload, "
                + "state, version, owner_id, mime_payload, mail_from, recipients_json, auth_user, "
                + "submission_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) USING TIMESTAMP ?")
            .bind(key, DIGEST, "{\"hello\":1}", state, version, owner,
                mime == null ? null : ByteBuffer.wrap(mime),
                mime == null ? null : "alice@example.com",
                mime == null ? null : "[\"bob@example.com\"]",
                mime == null ? null : "alice@example.com",
                mime == null ? null : "stable-submission",
                twoHoursAgoMicros));
    }

    private Row row() {
        return independentConnection.execute(independentConnection.prepare(
            "SELECT state, version, owner_id, mime_payload, mail_from, recipients_json, "
                + "submission_id FROM tmail_email_send_outbox WHERE submission_key = ?")
            .bind(key)).one();
    }
}
