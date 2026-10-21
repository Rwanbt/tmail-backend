package com.linagora.tmail.james.jmap.method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import org.apache.james.backends.cassandra.CassandraCluster;
import org.apache.james.backends.cassandra.CassandraClusterExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import scala.jdk.javaapi.CollectionConverters;

class CassandraEmailSendOutboxTest {
    private static final String DIGEST = "b".repeat(64);

    @RegisterExtension
    static CassandraClusterExtension cassandra =
        new CassandraClusterExtension(CassandraEmailSendOutbox.module());

    private CassandraEmailSendOutbox nodeA;
    private CassandraEmailSendOutbox nodeB;
    private String key;

    @BeforeEach
    void setUp(CassandraCluster cluster) {
        key = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "");
        // Two independent runtime objects sharing the real Cassandra cluster.
        nodeA = new CassandraEmailSendOutbox(cluster.getConf());
        nodeB = new CassandraEmailSendOutbox(cluster.getConf());
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
        assertThat(nodeB.reserve(key, DIGEST, "{\"hello\":1}").asJava().block()).isFalse();
        assertThatThrownBy(() -> nodeA.finish(key, "node-A", true).asJava().block())
            .hasMessageContaining("outbox finish CAS lost");
    }

    @Test
    void payloadConflictsFailClosedAcrossNodes() {
        assertThat(nodeA.reserve(key, DIGEST, "{\"hello\":1}").asJava().block()).isTrue();
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
        assertThat(nodeB.reserve(key, DIGEST, "{\"hello\":1}").asJava().block()).isFalse();
    }
}
