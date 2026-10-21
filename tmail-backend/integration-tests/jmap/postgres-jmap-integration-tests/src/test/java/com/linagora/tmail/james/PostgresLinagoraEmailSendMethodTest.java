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
import static com.linagora.tmail.james.TmailJmapBase.JAMES_SERVER_EXTENSION_FUNCTION;

import java.util.UUID;

import org.apache.james.JamesServerExtension;
import org.apache.james.GuiceJamesServer;
import org.apache.james.mailbox.model.MessageId;
import org.apache.james.mailbox.model.MultimailboxesSearchQuery;
import org.apache.james.mailbox.model.SearchQuery;
import org.apache.james.modules.MailboxProbeImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.inject.util.Modules;
import com.linagora.tmail.james.app.PostgresEncryptedMailboxModule;
import com.linagora.tmail.james.common.LinagoraEmailSendMethodContract;
import com.linagora.tmail.james.common.LinagoraEmailSendMethodContract$;

public class PostgresLinagoraEmailSendMethodTest implements LinagoraEmailSendMethodContract {

    @RegisterExtension
    static JamesServerExtension testExtension = JAMES_SERVER_EXTENSION_FUNCTION
        .apply(Modules.combine(new PostgresEncryptedMailboxModule()))
        .lifeCycle(JamesServerExtension.Lifecycle.PER_CLASS)
        .build();

    @Override
    public MessageId randomMessageId() {
        return TmailJmapBase.MESSAGE_ID_FACTORY.generate();
    }

    @Test
    void explicitIdempotencyKeyShouldPreventSecondPublishAndSecondSenderAppend(GuiceJamesServer server) {
        String idempotencyKey = UUID.randomUUID().toString();
        String request = LinagoraEmailSendMethodContract$.MODULE$.bobSendsAMailToAndre(server)
            .replace("\"K87\": {", "\"K87\": {\"idempotencyKey\": \"" + idempotencyKey + "\",");
        String first = given().body(request).when().post()
            .then().statusCode(200).extract().body().asString();
        assertThat(first).contains("\"created\"", "\"K87\"");

        String replay = given().body(request).when().post()
            .then().statusCode(200).extract().body().asString();
        assertThat(replay).contains("\"notCreated\"", "\"K87\"",
            "Queue acceptance is uncertain; retrying may send this email twice");

        String changedPayload = given().body(request.replace("World domination", "different subject")).when().post()
            .then().statusCode(200).extract().body().asString();
        assertThat(changedPayload).contains("\"notCreated\"", "\"K87\"");
        assertThat(changedPayload).doesNotContain("submission key reused with different payload");
        assertThat(server.getProbe(MailboxProbeImpl.class)
            .searchMessage(MultimailboxesSearchQuery.from(SearchQuery.of(SearchQuery.all())).build(),
                bobUsername().asString(), 100))
            .as("replay of a durable client identity must not append a second sender copy")
            .hasSize(1);
    }
}
