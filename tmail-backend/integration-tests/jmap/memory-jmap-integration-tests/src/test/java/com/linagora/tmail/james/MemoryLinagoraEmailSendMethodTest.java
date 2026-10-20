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
import static org.apache.james.data.UsersRepositoryModuleChooser.Implementation.DEFAULT;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.util.concurrent.ThreadLocalRandom;

import jakarta.inject.Inject;
import org.apache.james.GuiceJamesServer;
import org.apache.james.JamesServerBuilder;
import org.apache.james.JamesServerExtension;
import org.apache.james.mailbox.inmemory.InMemoryMessageId;
import org.apache.james.mailbox.model.MessageId;
import org.apache.james.mailbox.model.MultimailboxesSearchQuery;
import org.apache.james.mailbox.model.SearchQuery;
import org.apache.james.modules.MailboxProbeImpl;
import org.apache.james.queue.api.MailQueue;
import org.apache.james.utils.GuiceProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.inject.multibindings.Multibinder;
import com.linagora.tmail.james.app.MemoryConfiguration;
import com.linagora.tmail.james.app.MemoryEncryptedMailboxModule;
import com.linagora.tmail.james.app.MemoryServer;
import com.linagora.tmail.james.common.LinagoraEmailSendMethodContract;
import com.linagora.tmail.james.common.LinagoraEmailSendMethodContract$;
import com.linagora.tmail.james.jmap.firebase.FirebaseModuleChooserConfiguration;
import com.linagora.tmail.james.jmap.method.EmailSendMethod;
import com.linagora.tmail.module.LinagoraTestJMAPServerModule;

import reactor.core.publisher.Mono;

public class MemoryLinagoraEmailSendMethodTest implements LinagoraEmailSendMethodContract {

    @RegisterExtension
    static JamesServerExtension
        jamesServerExtension = new JamesServerBuilder<MemoryConfiguration>(tmpDir ->
        MemoryConfiguration.builder()
            .workingDirectory(tmpDir)
            .configurationFromClasspath()
            .usersRepository(DEFAULT)
            .firebaseModuleChooserConfiguration(FirebaseModuleChooserConfiguration.DISABLED)
            .build())
        .server(configuration -> MemoryServer.createServer(configuration)
            .overrideWith(new MemoryEncryptedMailboxModule())
            .overrideWith(new LinagoraTestJMAPServerModule())
            .overrideWith(binder -> Multibinder.newSetBinder(binder, GuiceProbe.class)
                .addBinding().to(EnqueueFaultProbe.class)))
        .lifeCycle(JamesServerExtension.Lifecycle.PER_CLASS)
        .build();

    @Override
    public MessageId randomMessageId() {
        return InMemoryMessageId.of(ThreadLocalRandom.current().nextInt(100000) + 100);
    }

    @Test
    public void rejectedQueueSubmissionMustNotKeepAnOrphanEmail(GuiceJamesServer server) {
        EnqueueFaultProbe fault = server.getProbe(EnqueueFaultProbe.class);
        String request = LinagoraEmailSendMethodContract$.MODULE$.bobSendsAMailToAndre(server);
        fault.failBeforeAccept();
        try {
            String response = given().body(request).when().post()
                .then().statusCode(200).extract().body().asString();
            assertThat(response).contains("\"notCreated\"", "\"K87\"");
            int persisted = server.getProbe(MailboxProbeImpl.class)
                .searchMessage(MultimailboxesSearchQuery.from(SearchQuery.of(SearchQuery.all())).build(),
                    bobUsername().asString(), 100)
                .size();
            assertThat(persisted).as("failed Email/send must not leave an orphan in sender's mailbox").isZero();
        } finally {
            fault.restore();
        }
    }

    public static class EnqueueFaultProbe implements GuiceProbe {
        private final EmailSendMethod emailSendMethod;
        private MailQueue originalQueue;

        @Inject
        public EnqueueFaultProbe(EmailSendMethod emailSendMethod) {
            this.emailSendMethod = emailSendMethod;
        }

        public void failBeforeAccept() {
            if (originalQueue != null) {
                throw new IllegalStateException("fault already installed");
            }
            originalQueue = emailSendMethod.queue();
            MailQueue failureQueue = (MailQueue) Proxy.newProxyInstance(
                MailQueue.class.getClassLoader(), new Class<?>[] { MailQueue.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("enqueueReactive")) {
                        return Mono.error(new IllegalStateException("synthetic enqueue rejection before acceptance"));
                    }
                    return method.invoke(originalQueue, args);
                });
            emailSendMethod.queue_$eq(failureQueue);
        }

        public void restore() {
            if (originalQueue != null) {
                emailSendMethod.queue_$eq(originalQueue);
                originalQueue = null;
            }
        }
    }
}
