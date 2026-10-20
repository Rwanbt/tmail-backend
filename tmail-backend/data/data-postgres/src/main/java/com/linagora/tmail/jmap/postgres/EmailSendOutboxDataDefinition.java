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
    Field<String> OWNER_ID = DSL.field("owner_id", SQLDataType.VARCHAR);
    Field<String> STATE = DSL.field("state", SQLDataType.VARCHAR(32).nullable(false).defaultValue("prepared"));
    Field<Long> VERSION = DSL.field("version", SQLDataType.BIGINT.nullable(false).defaultValue(0L));

    Field<OffsetDateTime> CREATED_AT = DSL.field("created_at", SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false).defaultValue(DSL.currentOffsetDateTime()));
    Field<OffsetDateTime> UPDATED_AT = DSL.field("updated_at", SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false).defaultValue(DSL.currentOffsetDateTime()));

    PostgresTable TABLE = PostgresTable.name(TABLE_NAME.getName())
        .createTableStep((dsl, name) -> dsl.createTableIfNotExists(name)
            .column(SUBMISSION_KEY)
            .column(PAYLOAD_DIGEST)
            .column(OWNER_ID)
            .column(STATE)
            .column(VERSION)
            .column(CREATED_AT)
            .column(UPDATED_AT)
            .primaryKey(SUBMISSION_KEY))
        .disableRowLevelSecurity()
        .build();

    PostgresDataDefinition MODULE = PostgresDataDefinition.builder().addTable(TABLE).build();
}
