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

import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.OWNER_ID;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.PAYLOAD_DIGEST;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.STATE;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.SUBMISSION_KEY;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.TABLE_NAME;
import static com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition.VERSION;

import org.apache.james.backends.postgres.utils.PostgresExecutor;
import org.jooq.impl.DSL;

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
        return executor.executeReturnAffectedRowsCount(dsl -> Mono.from(dsl.insertInto(TABLE_NAME)
            .set(SUBMISSION_KEY, key)
            .set(PAYLOAD_DIGEST, payload)
            .set(STATE, "prepared")
            .set(VERSION, 0L)
            .onConflict(SUBMISSION_KEY).doNothing()));
    }

    public Mono<String> persistedPayload(String key) {
        return executor.executeRows(dsl -> Mono.from(dsl.select(PAYLOAD_DIGEST)
                .from(TABLE_NAME).where(SUBMISSION_KEY.eq(key))))
            .map(row -> row.get(PAYLOAD_DIGEST));
    }

    public Mono<Integer> claim(String key, long version, String owner) {
        if (owner == null || owner.isBlank()) {
            return Mono.error(new IllegalArgumentException("owner must be nonempty"));
        }
        return executor.executeReturnAffectedRowsCount(dsl -> Mono.from(dsl.update(TABLE_NAME)
            .set(STATE, "dispatching")
            .set(OWNER_ID, owner)
            .set(VERSION, version + 1)
            .where(SUBMISSION_KEY.eq(key), VERSION.eq(version), STATE.eq("prepared"))));
    }

    public Mono<Integer> finish(String key, long version, String owner, boolean accepted) {
        if (owner == null || owner.isBlank()) {
            return Mono.error(new IllegalArgumentException("owner must be nonempty"));
        }
        return executor.executeReturnAffectedRowsCount(dsl -> Mono.from(dsl.update(TABLE_NAME)
            .set(STATE, accepted ? "accepted" : "acceptance_unknown")
            .set(OWNER_ID, (String) null)
            .set(VERSION, version + 1)
            .where(SUBMISSION_KEY.eq(key), VERSION.eq(version),
                OWNER_ID.eq(owner), STATE.eq("dispatching"))));
    }

    public Mono<Integer> recoverUnknown(String key, long version, String owner) {
        return finish(key, version, owner, false);
    }
}
