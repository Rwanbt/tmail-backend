package com.linagora.tmail.james;

import static org.apache.james.data.UsersRepositoryModuleChooser.Implementation.DEFAULT;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.apache.james.GuiceJamesServer;
import org.apache.james.JamesServerBuilder;
import org.apache.james.JamesServerExtension;
import org.apache.james.data.DataProbeImpl;
import org.apache.james.jmap.JmapGuiceProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.tmail.james.app.MemoryConfiguration;
import com.linagora.tmail.james.app.MemoryServer;
import com.linagora.tmail.james.jmap.firebase.FirebaseModuleChooserConfiguration;
import com.linagora.tmail.module.LinagoraTestJMAPServerModule;

public class MemoryWebSocketTransportEnabledTest {
    private static final String USER = "websocket-enabled@example.com";
    private static final String PASSWORD = "secret";

    @RegisterExtension
    static JamesServerExtension testExtension = new JamesServerBuilder<MemoryConfiguration>(tmpDir ->
        MemoryConfiguration.builder()
            .workingDirectory(tmpDir)
            .configurationFromClasspath()
            .usersRepository(DEFAULT)
            .firebaseModuleChooserConfiguration(FirebaseModuleChooserConfiguration.DISABLED)
            .build())
        .server(configuration -> MemoryServer.createServer(configuration)
            .overrideWith(new LinagoraTestJMAPServerModule()))
        .lifeCycle(JamesServerExtension.Lifecycle.PER_TEST)
        .build();

    @Test
    void enabledWebSocketAcceptsAuthenticatedUpgrade(GuiceJamesServer server) throws Exception {
        server.getProbe(DataProbeImpl.class).fluent()
            .addDomain("example.com")
            .addUser(USER, PASSWORD);

        int port = server.getProbe(JmapGuiceProbe.class).getJmapPort().getValue();
        String credentials = Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        try (HttpClient client = HttpClient.newHttpClient()) {
            WebSocket socket = client.newWebSocketBuilder()
                .header("Authorization", "Basic " + credentials)
                .header("Accept", "application/json; jmapVersion=rfc-8621")
                .subprotocols("jmap")
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/jmap/ws"), new WebSocket.Listener() { })
                .join();

            assertThat(socket.getSubprotocol()).isEqualTo("jmap");
            socket.abort();
        }
    }
}
