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
 *  PURPOSE. See the Affero GNU General Public License for more    *
 *  details.                                                         *
 ********************************************************************/
package com.linagora.tmail.jmap.postgres;

import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.AUTH_USER;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.MAIL_FROM;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.MIME_PAYLOAD;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.OWNER_ID;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.PAYLOAD_DIGEST;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.RECIPIENTS_JSON;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.REQUEST_PAYLOAD;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.STATE;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.SUBMISSION_ID;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.SUBMISSION_KEY;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.TABLE_NAME;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.UPDATED_AT;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.VERSION;

import org.apache.james.backends.postgres.utils.PostgresExecutor;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Durable compare-and-set operations used by a future Email/send dispatcher.
 *
 * No caller may infer queue acceptance from a successful claim. In particular,
 * an interrupted dispatch must be marked uncertain, never requeued blindly.
 */
public class EmailSendOutboxDao {
    private final PostgresExecutor executor;

    public EmailSendOutboxDao(PostgresExecutor executor) {
        this.executor = executor;
    }

    public Mono<Integer> register(String key, String payload) {
        if (key == null || !key.matches("[0-9a-f]{64}") || payload == null || !payload.matches("[0-9a-f]{64}")) {
            return Mono.error(new IllegalArgumentException("invalid submission fingerprint"));
        }
        return executor.executeReturnAffectedRowsCount(dsl -> Mono.from(dsl.insertInto(TABLE_NAME)
            .set(SUBMISSION_KEY, key)
            .set(PAYLOAD_DIGEST, payload)
            .set(STATE, "prepared")
            .set(VERSION, 0L)
            .onConflict(SUBMISSION_KEY).doNothing()));
    }

    /** Detect replay-with-different-content rather than treating ON CONFLICT as success. */
    public Mono<Boolean> registerOrMatch(String key, String payload) {
        return register(key, payload)
            .flatMap(inserted -> persistedPayload(key)
                .switchIfEmpty(Mono.error(new IllegalStateException("outbox registration not visible")))
                .flatMap(stored -> stored.equals(payload)
                    ? Mono.just(inserted == 1)
                    : Mono.error(new IllegalArgumentException("submission key reused with different payload"))));
    }

    public Mono<String> persistedPayload(String key) {
        return executor.executeRows(dsl -> Flux.from(dsl.select(PAYLOAD_DIGEST)
                .from(TABLE_NAME).where(SUBMISSION_KEY.eq(key))))
            .next().map(row -> row.get(PAYLOAD_DIGEST));
    }

    /**
     * A replay must not publish: only a fresh registration can proceed to claim.
     * Return an explicit decision to make accidental resend by callers harder.
     */
    public enum RegistrationDecision {
        NEW_SUBMISSION,
        ALREADY_REGISTERED
    }

    public Mono<RegistrationDecision> registerForDispatch(String key, String payload) {
        return registerOrMatch(key, payload)
            .map(created -> created ? RegistrationDecision.NEW_SUBMISSION : RegistrationDecision.ALREADY_REGISTERED);
    }

    /**
     * Reserve a client-supplied stable identity before any mailbox append.
     * A repeated identity is terminal for automatic dispatch even if the
     * process died before MIME serialization. Operators must reconcile it.
     */
    public Mono<RegistrationDecision> reserve(String key, String digest, String requestJson) {
        if (key == null || !key.matches("[0-9a-f]{64}")
                || digest == null || !digest.matches("[0-9a-f]{64}")
                || requestJson == null || requestJson.isBlank()) {
            return Mono.error(new IllegalArgumentException("invalid durable submission"));
        }
        return executor.executeReturnAffectedRowsCount(dsl -> Mono.from(dsl.insertInto(TABLE_NAME)
                .set(SUBMISSION_KEY, key)
                .set(PAYLOAD_DIGEST, digest)
                .set(REQUEST_PAYLOAD, requestJson)
                .set(STATE, "prepared")
                .set(VERSION, 0L)
                .onConflict(SUBMISSION_KEY).doNothing()))
            .flatMap(inserted -> executor.executeRows(dsl -> Flux.from(dsl.select(PAYLOAD_DIGEST, REQUEST_PAYLOAD)
                    .from(TABLE_NAME).where(SUBMISSION_KEY.eq(key))))
                .next()
                .switchIfEmpty(Mono.error(new IllegalStateException("outbox reservation not visible")))
                .map(row -> {
                    if (!digest.equals(row.get(PAYLOAD_DIGEST)) || !requestJson.equals(row.get(REQUEST_PAYLOAD))) {
                        throw new IllegalArgumentException("submission key reused with different payload");
                    }
                    return inserted == 1 ? RegistrationDecision.NEW_SUBMISSION : RegistrationDecision.ALREADY_REGISTERED;
                }));
    }

    /** One atomic durable payload write and claim, strictly before queue publication. */
    public Mono<Integer> stageAndClaim(String key, String owner, byte[] mime, String from,
                                       String recipientsJson, String authUser, String submissionId) {
        if (owner == null || owner.isBlank() || mime == null || mime.length == 0
                || from == null || from.isBlank() || recipientsJson == null || recipientsJson.isBlank()
                || authUser == null || authUser.isBlank() || submissionId == null || submissionId.isBlank()) {
            return Mono.error(new IllegalArgumentException("invalid dispatch payload"));
        }
        return executor.executeReturnAffectedRowsCount(dsl -> Mono.from(dsl.update(TABLE_NAME)
            .set(MIME_PAYLOAD, mime)
            .set(MAIL_FROM, from)
            .set(RECIPIENTS_JSON, recipientsJson)
            .set(AUTH_USER, authUser)
            .set(SUBMISSION_ID, submissionId)
            .set(STATE, "dispatching")
            .set(OWNER_ID, owner)
            .set(VERSION, 1L)
            .set(UPDATED_AT, DSL.currentOffsetDateTime())
            .where(SUBMISSION_KEY.eq(key), STATE.eq("prepared"), VERSION.eq(0L),
                REQUEST_PAYLOAD.isNotNull(), MIME_PAYLOAD.isNull())));
    }

    /**
     * Fence a stalled reservation after an extended period of inactivity.
     * Fully staged dispatches become acceptance_unknown. Prepared rows may
     * lack MIME, so they remain prepared but advance from version 0 to 1:
     * this permanently disables stageAndClaim's version-0 CAS without
     * violating the complete-MIME dispatch constraint. No resend is allowed.
     *
     * This is only called on a client replay of the same durable identity.
     * The PostgreSQL server clock, not a potentially skewed application node
     * clock, determines the age. The atomic update fences stale owners by
     * advancing the version and revoking the owner before any later replay.
     */
    public Mono<Integer> quarantineStaleReservation(String key) {
        if (key == null || !key.matches("[0-9a-f]{64}")) {
            return Mono.error(new IllegalArgumentException("invalid durable submission key"));
        }
        return executor.executeReturnAffectedRowsCount(dsl -> Mono.from(dsl.update(TABLE_NAME)
            .set(STATE, DSL.when(STATE.eq("prepared"), "prepared").otherwise("acceptance_unknown"))
            .set(OWNER_ID, (String) null)
            .set(VERSION, VERSION.add(1L))
            .set(UPDATED_AT, DSL.currentOffsetDateTime())
            .where(SUBMISSION_KEY.eq(key), REQUEST_PAYLOAD.isNotNull(),
                STATE.eq("dispatching").or(STATE.eq("prepared").and(VERSION.eq(0L))),
                VERSION.lt(Long.MAX_VALUE),
                UPDATED_AT.lt(DSL.field("CURRENT_TIMESTAMP - INTERVAL '30 minutes'",
                    SQLDataType.TIMESTAMPWITHTIMEZONE)))));
    }

    public record StoredMail(byte[] mime, String from, String recipientsJson, String authUser, String submissionId) { }

    /** Read the actual persisted delivery bytes while the exact claim is held. */
    public Mono<StoredMail> loadClaimed(String key, String owner) {
        return executor.executeRows(dsl -> Flux.from(dsl.select(MIME_PAYLOAD, MAIL_FROM,
                    RECIPIENTS_JSON, AUTH_USER, SUBMISSION_ID)
                .from(TABLE_NAME)
                .where(SUBMISSION_KEY.eq(key), STATE.eq("dispatching"),
                    VERSION.eq(1L), OWNER_ID.eq(owner))))
            .next()
            .map(row -> new StoredMail(row.get(MIME_PAYLOAD), row.get(MAIL_FROM),
                row.get(RECIPIENTS_JSON), row.get(AUTH_USER), row.get(SUBMISSION_ID)));
    }

    static boolean invalidVersion(long version) {
        return version < 0 || version == Long.MAX_VALUE;
    }

    public Mono<Integer> claim(String key, long version, String owner) {
        if (owner == null || owner.isBlank()) {
            return Mono.error(new IllegalArgumentException("owner must be nonempty"));
        }
        if (invalidVersion(version)) {
            return Mono.error(new IllegalArgumentException("version must allow monotonic increment"));
        }
        return executor.executeReturnAffectedRowsCount(dsl -> Mono.from(dsl.update(TABLE_NAME)
            .set(STATE, "dispatching")
            .set(OWNER_ID, owner)
            .set(VERSION, version + 1)
            // A new durable reservation cannot use this legacy hash-only
            // transition: it must persist MIME and envelope via stageAndClaim.
            .where(SUBMISSION_KEY.eq(key), VERSION.eq(version),
                STATE.eq("prepared"), REQUEST_PAYLOAD.isNull())));
    }

    public Mono<Integer> finish(String key, long version, String owner, boolean accepted) {
        if (owner == null || owner.isBlank()) {
            return Mono.error(new IllegalArgumentException("owner must be nonempty"));
        }
        if (invalidVersion(version)) {
            return Mono.error(new IllegalArgumentException("version must allow monotonic increment"));
        }
        return executor.executeReturnAffectedRowsCount(dsl -> Mono.from(dsl.update(TABLE_NAME)
            .set(STATE, accepted ? "accepted" : "acceptance_unknown")
            .set(OWNER_ID, (String) null)
            .set(VERSION, version + 1)
            .set(UPDATED_AT, DSL.currentOffsetDateTime())
            .where(SUBMISSION_KEY.eq(key), VERSION.eq(version),
                OWNER_ID.eq(owner), STATE.eq("dispatching"))));
    }

    /**
     * Fail closed after interrupted publication. Only the original owner and
     * version can record uncertainty; an unknown ACK never authorizes retry.
     */
    public Mono<Integer> recoverUnknown(String key, long version, String owner) {
        return finish(key, version, owner, false);
    }

    /** Prevent a concurrent or stale publisher from bypassing the prepared claim. */
    public Mono<Boolean> mayDispatch(String key, long version, String owner) {
        if (owner == null || owner.isBlank()) {
            return Mono.error(new IllegalArgumentException("owner must be nonempty"));
        }
        return executor.executeRows(dsl -> Flux.from(dsl.select(STATE)
                .from(TABLE_NAME)
                .where(SUBMISSION_KEY.eq(key), VERSION.eq(version),
                    OWNER_ID.eq(owner), STATE.eq("dispatching"))))
            .hasElements();
    }
}
