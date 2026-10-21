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
 *  WARRANTY OF MERCHANTABILITY or FITNESS FOR A PARTICULAR         *
 *  PURPOSE. See the Affero GNU General Public License for more    *
 *  details.                                                         *
 *                                                                  *
 *  You should have received a copy of the GNU Affero General       *
 *  Public License along with this program.                         *
 ********************************************************************/
package com.linagora.tmail.james;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.james.backends.postgres.PostgresExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.tmail.jmap.postgres.EmailSendOutboxDao;
import com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition;
import com.linagora.tmail.jmap.postgres.EmailSendOutboxDao.RegistrationDecision;

/** Exercises the actual PostgreSQL CAS and uniqueness constraints, including a DAO restart. */
class EmailSendOutboxDaoTest {
    @RegisterExtension
    static PostgresExtension postgresExtension = PostgresExtension.withoutRowLevelSecurity(EmailSendOutboxDataDefinition.MODULE);

    private EmailSendOutboxDao dao;

    @BeforeEach
    void setUp() {
        dao = new EmailSendOutboxDao(postgresExtension.getDefaultPostgresExecutor());
    }

    @Test
    void registrationShouldBeIdempotentAndRejectDifferentPayloadOnReplay() {
        String key = "a".repeat(64);
        String digest = "b".repeat(64);

        assertThat(dao.registerForDispatch(key, digest).block()).isEqualTo(RegistrationDecision.NEW_SUBMISSION);
        assertThat(dao.registerForDispatch(key, digest).block()).isEqualTo(RegistrationDecision.ALREADY_REGISTERED);
        assertThat(dao.persistedPayload(key).block()).isEqualTo(digest);
        assertThatThrownBy(() -> dao.registerForDispatch(key, "c".repeat(64)).block())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("different payload");

        // Replaying the same fingerprint must never make the claim available again.
        assertThat(dao.claim(key, 0, "first-dispatch").block()).isEqualTo(1);
        assertThat(dao.registerForDispatch(key, digest).block()).isEqualTo(RegistrationDecision.ALREADY_REGISTERED);
        assertThat(dao.claim(key, 0, "replay").block()).isZero();
    }

    @Test
    void onlyOneOwnerCanClaimAndDispatchAPreparedSubmission() {
        String key = "d".repeat(64);
        dao.registerForDispatch(key, "e".repeat(64)).block();

        assertThat(dao.mayDispatch(key, 0, "worker-1").block()).isFalse();
        assertThat(dao.claim(key, 0, "worker-1").block()).isEqualTo(1);
        assertThat(dao.claim(key, 0, "worker-2").block()).isZero();
        assertThat(dao.claim(key, 1, "worker-2").block()).isZero();
        assertThat(dao.mayDispatch(key, 1, "worker-1").block()).isTrue();
        assertThat(dao.mayDispatch(key, 1, "worker-2").block()).isFalse();
        assertThat(dao.mayDispatch(key, 0, "worker-1").block()).isFalse();
    }

    @Test
    void acceptedSubmissionCannotBeResentEvenAfterRestart() {
        String key = "f".repeat(64);
        dao.registerForDispatch(key, "0".repeat(64)).block();
        assertThat(dao.claim(key, 0, "publisher").block()).isEqualTo(1);

        assertThat(dao.finish(key, 1, "other-publisher", true).block()).isZero();
        assertThat(dao.finish(key, 0, "publisher", true).block()).isZero();
        assertThat(dao.finish(key, 1, "publisher", true).block()).isEqualTo(1);

        EmailSendOutboxDao restarted = new EmailSendOutboxDao(postgresExtension.getDefaultPostgresExecutor());
        assertThat(restarted.mayDispatch(key, 1, "publisher").block()).isFalse();
        assertThat(restarted.recoverUnknown(key, 1, "publisher").block()).isZero();
        assertThat(restarted.claim(key, 2, "restarted-publisher").block()).isZero();
        assertThat(restarted.registerForDispatch(key, "0".repeat(64)).block())
            .isEqualTo(RegistrationDecision.ALREADY_REGISTERED);
    }

    @Test
    void ambiguousAcceptanceMustRemainUnsendableAfterRestart() {
        String key = "1".repeat(64);
        String digest = "2".repeat(64);
        dao.registerForDispatch(key, digest).block();
        assertThat(dao.claim(key, 0, "publisher").block()).isEqualTo(1);

        // An interrupted publication cannot be interpreted as a confirmed rejection.
        EmailSendOutboxDao restarted = new EmailSendOutboxDao(postgresExtension.getDefaultPostgresExecutor());
        assertThat(restarted.recoverUnknown(key, 1, "another-owner").block()).isZero();
        assertThat(restarted.recoverUnknown(key, 1, "publisher").block()).isEqualTo(1);
        assertThat(restarted.recoverUnknown(key, 1, "publisher").block()).isZero();
        assertThat(restarted.mayDispatch(key, 2, "publisher").block()).isFalse();
        assertThat(restarted.claim(key, 2, "retry-publisher").block()).isZero();
        assertThat(restarted.registerForDispatch(key, digest).block())
            .isEqualTo(RegistrationDecision.ALREADY_REGISTERED);
    }

    @Test
    void invalidIdentityAndVersionsMustFailWithoutChangingState() {
        String key = "3".repeat(64);
        String digest = "4".repeat(64);
        assertThatThrownBy(() -> dao.registerForDispatch("not-a-sha256", digest).block())
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dao.registerForDispatch(key, "NOT_HEX".repeat(9)).block())
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(dao.registerForDispatch(key, digest).block()).isEqualTo(RegistrationDecision.NEW_SUBMISSION);
        assertThatThrownBy(() -> dao.claim(key, -1, "owner").block())
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dao.claim(key, Long.MAX_VALUE, "owner").block())
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dao.claim(key, 0, " ").block())
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(dao.claim(key, 0, "owner").block()).isEqualTo(1);
        assertThatThrownBy(() -> dao.finish(key, Long.MAX_VALUE, "owner", true).block())
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(dao.mayDispatch(key, 1, "owner").block()).isTrue();
    }
}
