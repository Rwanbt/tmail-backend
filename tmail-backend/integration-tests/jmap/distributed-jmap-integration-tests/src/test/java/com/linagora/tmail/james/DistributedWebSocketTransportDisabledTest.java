package com.linagora.tmail.james;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletionException;

import org.apache.james.GuiceJamesServer;
import org.apache.james.JamesServerBuilder;
import org.apache.james.JamesServerExtension;
import org.apache.james.backends.redis.RedisExtension;
import org.apache.james.jmap.JmapGuiceProbe;
import org.apache.james.junit.categories.BasicFeature;
import org.apache.james.modules.AwsS3BlobStoreExtension;
import org.apache.james.utils.DataProbeImpl;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.tmail.blob.guice.BlobStoreConfiguration;
import com.linagora.tmail.james.app.CassandraExtension;
import com.linagora.tmail.james.app.DistributedJamesConfiguration;
import com.linagora.tmail.james.app.DistributedServer;
import com.linagora.tmail.james.app.DockerOpenSearchExtension;
import com.linagora.tmail.james.app.EventBusKeysChoice;
import com.linagora.tmail.james.app.RabbitMQExtension;
import com.linagora.tmail.james.jmap.firebase.FirebaseModuleChooserConfiguration;
import com.linagora.tmail.module.LinagoraTestJMAPServerModule;

@Tag(BasicFeature.TAG)
public class DistributedWebSocketTransportDisabledTest {
    private static final String USER = "distributed-websocket-disabled@example.com";
    private static final String PASSWORD = "secret";

    @RegisterExtension
    static JamesServerExtension testExtension = new JamesServerBuilder<DistributedJamesConfiguration>(tmpDir ->
        DistributedJamesConfiguration.builder()
            .workingDirectory(tmpDir)
            .configurationFromClasspath()
            .blobStore(BlobStoreConfiguration.builder()
                .s3()
                .noSecondaryS3BlobStore()
                .disableCache()
                .deduplication()
                .noCryptoConfig()
                .disableSingleSave())
            .eventBusKeysChoice(EventBusKeysChoice.REDIS)
            .firebaseModuleChooserConfiguration(FirebaseModuleChooserConfiguration.DISABLED)
            .build())
        .extension(new DockerOpenSearchExtension())
        .extension(new CassandraExtension())
        .extension(new RabbitMQExtension())
        .extension(new RedisExtension())
        .extension(new AwsS3BlobStoreExtension())
        .server(configuration -> DistributedServer.createServer(configuration)
            .overrideWith(new LinagoraTestJMAPServerModule(Map.of("websocket.enabled", false))))
        .lifeCycle(JamesServerExtension.Lifecycle.PER_CLASS)
        .build();

    @Test
    void disabledWebSocketKeepsJmapHttpButExposesNoWebSocketIngress(GuiceJamesServer server) throws Exception {
        server.getProbe(DataProbeImpl.class).fluent()
            .addDomain("example.com")
            .addUser(USER, PASSWORD);

        int port = server.getProbe(JmapGuiceProbe.class).getJmapPort().getValue();
        String credentials = Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        URI session = URI.create("http://127.0.0.1:" + port + "/jmap/session");
        URI socket = URI.create("http://127.0.0.1:" + port + "/jmap/ws");
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(session)
                .header("Authorization", "Basic " + credentials)
                .header("Accept", "application/json; jmapVersion=rfc-8621")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("apiUrl");

            for (URI endpoint : new URI[] {socket, URI.create(socket + "?ticket=invalid-test-ticket")}) {
                for (String method : new String[] {"GET", "OPTIONS"}) {
                    for (boolean authenticated : new boolean[] {false, true}) {
                        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                            .method(method, HttpRequest.BodyPublishers.noBody());
                        if (authenticated) {
                            request.header("Authorization", "Basic " + credentials);
                        }
                        assertThat(client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode())
                            .as("%s %s with authenticated=%s", method, endpoint.getPath(), authenticated)
                            .isEqualTo(404);
                    }
                }
            }

            CompletionException failure = assertThrows(CompletionException.class, () ->
                client.newWebSocketBuilder()
                    .header("Authorization", "Basic " + credentials)
                    .subprotocols("jmap")
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/jmap/ws"), new WebSocket.Listener() { })
                    .join());
            assertThat(failure.getCause()).isInstanceOf(WebSocketHandshakeException.class);
            assertThat(((WebSocketHandshakeException) failure.getCause()).getResponse().statusCode()).isEqualTo(404);
        }
    }
}
