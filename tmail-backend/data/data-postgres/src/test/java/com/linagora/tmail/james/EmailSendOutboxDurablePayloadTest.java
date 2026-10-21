package com.linagora.tmail.james;

import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.STATE;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.SUBMISSION_KEY;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.TABLE_NAME;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.UPDATED_AT;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.apache.james.backends.postgres.PostgresExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.tmail.jmap.postgres.EmailSendOutboxDao;
import com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition;
import com.linagora.tmail.jmap.postgres.EmailSendOutboxDao.RegistrationDecision;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class EmailSendOutboxDurablePayloadTest {
    @RegisterExtension
    static PostgresExtension postgres = PostgresExtension.withoutRowLevelSecurity(EmailSendOutboxDataDefinition.MODULE);

    private EmailSendOutboxDao dao;

    @BeforeEach
    void beforeEach() {
        dao = new EmailSendOutboxDao(postgres.getDefaultPostgresExecutor());
    }

    @Test
    void mustPersistRequestAndMimeBeforeAllowingOneClaimAndBlockReplayAfterUnknown() {
        String key = "5".repeat(64);
        String digest = "6".repeat(64);
        byte[] mime = "Subject: persisted\r\n\r\nBody".getBytes(StandardCharsets.UTF_8);

        assertThat(dao.reserve(key, digest, "{\"draft\":1}").block()).isEqualTo(RegistrationDecision.NEW_SUBMISSION);
        assertThat(dao.reserve(key, digest, "{\"draft\":1}").block()).isEqualTo(RegistrationDecision.ALREADY_REGISTERED);
        assertThatThrownBy(() -> dao.reserve(key, digest, "{\"draft\":2}").block())
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(dao.claim(key, 0, "bypass-payload-stage").block()).isZero();
        assertThat(dao.loadClaimed(key, "owner").block()).isNull();

        assertThat(dao.stageAndClaim(key, "owner", mime, "sender@example.com",
            "[\"recipient@example.com\"]", "user@example.com", "submission-123").block()).isEqualTo(1);
        assertThat(dao.stageAndClaim(key, "second-owner", mime, "sender@example.com",
            "[\"recipient@example.com\"]", "user@example.com", "submission-456").block()).isZero();

        EmailSendOutboxDao afterRestart = new EmailSendOutboxDao(postgres.getDefaultPostgresExecutor());
        EmailSendOutboxDao.StoredMail durable = afterRestart.loadClaimed(key, "owner").block();
        assertThat(durable.mime()).containsExactly(mime);
        assertThat(durable.from()).isEqualTo("sender@example.com");
        assertThat(durable.recipientsJson()).isEqualTo("[\"recipient@example.com\"]");
        assertThat(durable.authUser()).isEqualTo("user@example.com");
        assertThat(durable.submissionId()).isEqualTo("submission-123");
        assertThat(afterRestart.loadClaimed(key, "second-owner").block()).isNull();

        assertThat(afterRestart.recoverUnknown(key, 1, "owner").block()).isEqualTo(1);
        assertThat(afterRestart.loadClaimed(key, "owner").block()).isNull();
        assertThat(afterRestart.claim(key, 2, "retry-owner").block()).isZero();
        assertThat(afterRestart.reserve(key, digest, "{\"draft\":1}").block())
            .isEqualTo(RegistrationDecision.ALREADY_REGISTERED);
    }

    @Test
    void stalePreparedReplayMustRemainBlockedWithoutInventingMimeOrDispatch() {
        String key = "7".repeat(64);
        byte[] mime = "Subject: late\r\n\r\nBody".getBytes(StandardCharsets.UTF_8);
        assertThat(dao.reserve(key, "8".repeat(64), "{\"draft\":7}").block())
            .isEqualTo(RegistrationDecision.NEW_SUBMISSION);

        // The first process can crash between reservation and mailbox append.
        // The server cannot infer whether an append happened, and may not
        // invent MIME. It fences the original version-0 claim permanently.
        makeReservationStale(key);
        EmailSendOutboxDao restarted = new EmailSendOutboxDao(postgres.getDefaultPostgresExecutor());
        assertThat(restarted.quarantineStaleReservation(key).block()).isEqualTo(1);
        assertThat(storedState(key)).isEqualTo("prepared");
        assertThat(storedVersion(key)).isEqualTo(1L);
        assertThat(restarted.quarantineStaleReservation(key).block()).isZero();
        assertThat(restarted.reserve(key, "8".repeat(64), "{\"draft\":7}").block())
            .isEqualTo(RegistrationDecision.ALREADY_REGISTERED);
        assertThat(restarted.claim(key, 0L, "restarted-node").block()).isZero();
        assertThat(restarted.stageAndClaim(key, "late-node", mime, "sender@example.com",
            "[\"recipient@example.com\"]", "user@example.com", "submission-late").block()).isZero();
    }

    @Test
    void staleDispatchClaimMustRevokeOriginalOwnerWithoutRetryingBrokerPublication() {
        String key = "9".repeat(64);
        byte[] mime = "Subject: already-published-maybe\r\n\r\nBody".getBytes(StandardCharsets.UTF_8);
        assertThat(dao.reserve(key, "a".repeat(64), "{\"draft\":9}").block())
            .isEqualTo(RegistrationDecision.NEW_SUBMISSION);
        assertThat(dao.stageAndClaim(key, "crashed-owner", mime, "sender@example.com",
            "[\"recipient@example.com\"]", "user@example.com", "submission-9").block()).isEqualTo(1);

        makeReservationStale(key);
        EmailSendOutboxDao restarted = new EmailSendOutboxDao(postgres.getDefaultPostgresExecutor());
        assertThat(restarted.quarantineStaleReservation(key).block()).isEqualTo(1);
        assertThat(storedState(key)).isEqualTo("acceptance_unknown");
        assertThat(storedVersion(key)).isEqualTo(2L);
        assertThat(restarted.loadClaimed(key, "crashed-owner").block()).isNull();
        assertThat(restarted.finish(key, 1L, "crashed-owner", true).block()).isZero();
        assertThat(restarted.claim(key, 2L, "new-publisher").block()).isZero();
        assertThat(restarted.quarantineStaleReservation(key).block()).isZero();
        assertThat(restarted.reserve(key, "a".repeat(64), "{\"draft\":9}").block())
            .isEqualTo(RegistrationDecision.ALREADY_REGISTERED);
    }

    @Test
    void freshAndLegacyHashOnlyReservationsMustNotBeQuarantined() {
        String fresh = "b".repeat(64);
        String legacy = "c".repeat(64);
        assertThat(dao.reserve(fresh, "d".repeat(64), "{\"draft\":11}").block())
            .isEqualTo(RegistrationDecision.NEW_SUBMISSION);
        assertThat(dao.quarantineStaleReservation(fresh).block()).isZero();
        assertThat(dao.registerForDispatch(legacy, "e".repeat(64)).block())
            .isEqualTo(RegistrationDecision.NEW_SUBMISSION);
        makeReservationStale(legacy);
        assertThat(dao.quarantineStaleReservation(legacy).block()).isZero();
        assertThat(storedState(legacy)).isEqualTo("prepared");
        assertThat(dao.claim(legacy, 0L, "legacy-owner").block()).isEqualTo(1);
        assertThat(dao.stageAndClaim(fresh, "fresh-owner",
            "Subject: fresh\r\n\r\nBody".getBytes(StandardCharsets.UTF_8), "sender@example.com",
            "[\"recipient@example.com\"]", "user@example.com", "submission-fresh").block()).isEqualTo(1);
        assertThat(dao.quarantineStaleReservation(fresh).block()).isZero();

        // A long-running append must not make a recent atomic dispatch claim
        // look old merely because its original reservation was created earlier.
        String stagedAfterOldReservation = "f".repeat(64);
        assertThat(dao.reserve(stagedAfterOldReservation, "0".repeat(64), "{\"draft\":12}").block())
            .isEqualTo(RegistrationDecision.NEW_SUBMISSION);
        makeReservationStale(stagedAfterOldReservation);
        assertThat(dao.stageAndClaim(stagedAfterOldReservation, "active-owner",
            "Subject: recovered\r\n\r\nBody".getBytes(StandardCharsets.UTF_8), "sender@example.com",
            "[\"recipient@example.com\"]", "user@example.com", "submission-recent").block()).isEqualTo(1);
        assertThat(dao.quarantineStaleReservation(stagedAfterOldReservation).block()).isZero();
        assertThat(storedState(stagedAfterOldReservation)).isEqualTo("dispatching");
    }

    private static void makeReservationStale(String key) {
        assertThat(postgres.getDefaultPostgresExecutor().executeReturnAffectedRowsCount(dsl ->
            Mono.from(dsl.update(TABLE_NAME)
                .set(UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC).minusHours(2))
                .where(SUBMISSION_KEY.eq(key)))).block()).isEqualTo(1);
    }

    private static String storedState(String key) {
        return postgres.getDefaultPostgresExecutor().executeRows(dsl -> Flux.from(dsl.select(STATE)
                .from(TABLE_NAME).where(SUBMISSION_KEY.eq(key))))
            .next().map(row -> row.get(STATE)).block();
    }

    private static Long storedVersion(String key) {
        return postgres.getDefaultPostgresExecutor().executeRows(dsl -> Flux.from(dsl.select(VERSION)
                .from(TABLE_NAME).where(SUBMISSION_KEY.eq(key))))
            .next().map(row -> row.get(VERSION)).block();
    }
}
