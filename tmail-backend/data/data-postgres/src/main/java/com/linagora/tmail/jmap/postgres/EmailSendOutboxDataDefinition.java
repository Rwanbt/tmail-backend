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

import java.time.OffsetDateTime;

import org.apache.james.backends.postgres.PostgresDataDefinition;
import org.apache.james.backends.postgres.PostgresTable;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

/**
 * Creates the durable identity/claim table as part of the standard James
 * PostgreSQL data-definition lifecycle. Queue publication remains disabled:
 * table creation alone is NOT a transactional outbox implementation.
 */
public interface EmailSendOutboxDataDefinition {
    Table<Record> TABLE_NAME = DSL.table("tmail_email_send_outbox");
    Field<String> SUBMISSION_KEY = DSL.field("submission_key", SQLDataType.CHAR(64).nullable(false));
    Field<String> PAYLOAD_DIGEST = DSL.field("payload_digest", SQLDataType.CHAR(64).nullable(false));
    // Persist the original request before creating the sender copy. MIME and
    // resolved delivery metadata must be durable before the publication claim.
    Field<String> REQUEST_PAYLOAD = DSL.field("request_payload", SQLDataType.LONGVARCHAR);
    Field<byte[]> MIME_PAYLOAD = DSL.field("mime_payload", SQLDataType.BLOB);
    Field<String> MAIL_FROM = DSL.field("mail_from", SQLDataType.VARCHAR(320));
    Field<String> RECIPIENTS_JSON = DSL.field("recipients_json", SQLDataType.LONGVARCHAR);
    Field<String> AUTH_USER = DSL.field("auth_user", SQLDataType.VARCHAR(512));
    Field<String> SUBMISSION_ID = DSL.field("submission_id", SQLDataType.VARCHAR(255));
    Field<String> OWNER_ID = DSL.field("owner_id", SQLDataType.VARCHAR);
    Field<String> STATE = DSL.field("state", SQLDataType.VARCHAR(32).nullable(false).defaultValue("prepared"));
    Field<Long> VERSION = DSL.field("version", SQLDataType.BIGINT.nullable(false).defaultValue(0L));

    Field<OffsetDateTime> CREATED_AT = DSL.field("created_at", SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false).defaultValue(DSL.currentOffsetDateTime()));
    Field<OffsetDateTime> UPDATED_AT = DSL.field("updated_at", SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false).defaultValue(DSL.currentOffsetDateTime()));

    PostgresTable TABLE = PostgresTable.name(TABLE_NAME.getName())
        .createTableStep((dsl, name) -> dsl.createTableIfNotExists(name)
            .column(SUBMISSION_KEY)
            .column(PAYLOAD_DIGEST)
            .column(REQUEST_PAYLOAD)
            .column(MIME_PAYLOAD)
            .column(MAIL_FROM)
            .column(RECIPIENTS_JSON)
            .column(AUTH_USER)
            .column(SUBMISSION_ID)
            .column(OWNER_ID)
            .column(STATE)
            .column(VERSION)
            .column(CREATED_AT)
            .column(UPDATED_AT)
            .constraint(DSL.constraint("outbox_state_valid")
                .check(STATE.in("prepared", "dispatching", "acceptance_unknown", "accepted", "rejected_before_publish")))
            .constraint(DSL.constraint("outbox_digest_format")
                .check(DSL.length(SUBMISSION_KEY).eq(64)
                    .and(DSL.length(PAYLOAD_DIGEST).eq(64))
                    .and(DSL.condition("submission_key ~ '^[0-9a-f]+$'"))
                    .and(DSL.condition("payload_digest ~ '^[0-9a-f]+$'"))))
            .constraint(DSL.constraint("outbox_version_valid").check(VERSION.ge(0L)))
            .constraint(DSL.constraint("outbox_dispatch_payload_valid")
                .check(REQUEST_PAYLOAD.isNull().or(STATE.eq("prepared"))
                    .or(MIME_PAYLOAD.isNotNull()
                        .and(MAIL_FROM.isNotNull())
                        .and(RECIPIENTS_JSON.isNotNull())
                        .and(AUTH_USER.isNotNull())
                        .and(SUBMISSION_ID.isNotNull()))))
            .constraint(DSL.constraint("outbox_owner_valid")
                .check(STATE.eq("dispatching").and(OWNER_ID.isNotNull()).and(DSL.length(OWNER_ID).gt(0))
                    .or(STATE.ne("dispatching").and(OWNER_ID.isNull()))))
            .primaryKey(SUBMISSION_KEY))
        .disableRowLevelSecurity()
        .build();

    PostgresDataDefinition MODULE = PostgresDataDefinition.builder().addTable(TABLE).build();
}
