package com.linagora.tmail.jmap.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.apache.james.backends.postgres.PostgresExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.tmail.jmap.postgres.EmailSendOutboxDao.RegistrationDecision;

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
}
