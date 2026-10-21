package com.linagora.tmail.james;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import jakarta.inject.Singleton;

import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.junit.jupiter.api.extension.ExtensionContext;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.util.Modules;
import com.linagora.tmail.james.app.RabbitMQExtension;

/**
 * Creates a real Testcontainers RabbitMQ and routes the James AMQP connection
 * through an AMQP-aware TCP relay. Management and server are never mocked.
 */
final class RabbitMQConfirmLossExtension extends RabbitMQExtension {
    private AmqpPublisherConfirmLossProxy proxy;

    @Override
    public void beforeAll(ExtensionContext context) {
        super.beforeAll(context);
        try {
            URI broker = dockerRabbitMQ().amqpUri();
            proxy = new AmqpPublisherConfirmLossProxy(broker.getHost(), broker.getPort());
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException("Unable to initialize real RabbitMQ confirm-loss proxy", e);
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        try {
            if (proxy != null) {
                proxy.close();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to close RabbitMQ fault-injection proxy", e);
        } finally {
            super.afterAll(context);
        }
    }

    void arm() {
        proxy.arm();
    }

    int spoolPublishes() {
        return proxy.spoolPublishes();
    }

    int droppedConfirms() {
        return proxy.droppedConfirms();
    }

    byte[] publishedReference() {
        return proxy.publishedReference();
    }

    @Override
    public Module getModule() {
        return Modules.override(super.getModule()).with(new AbstractModule() {
            @Provides
            @Singleton
            RabbitMQConfiguration rabbitMQWithConfirmLossTransport() throws URISyntaxException {
                URI broker = dockerRabbitMQ().amqpUri();
                URI relay = new URI(broker.getScheme(), broker.getUserInfo(), "127.0.0.1",
                    proxy.localPort(), broker.getPath(), broker.getQuery(), broker.getFragment());
                return dockerRabbitMQ().getConfigurationBuilder().amqpUri(relay).build();
            }
        });
    }
}
