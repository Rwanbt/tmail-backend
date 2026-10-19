package com.linagora.tmail.james.jmap

import java.util.stream.Stream

import jakarta.inject.{Inject, Named}
import org.apache.commons.configuration2.Configuration
import org.apache.james.events.EventBus
import org.apache.james.jmap.{InjectionKeys => JMAPInjectionKeys, JMAPRoute}
import org.apache.james.jmap.api.change.{EmailChangeRepository, MailboxChangeRepository, TypeStateFactory}
import org.apache.james.jmap.core.JmapRfc8621Configuration
import org.apache.james.jmap.http.{Authenticator, UserProvisioning}
import org.apache.james.jmap.http.rfc8621.InjectionKeys
import org.apache.james.jmap.json.PushSerializer
import org.apache.james.jmap.routes.{JMAPApi, WebSocketRoutes}
import org.apache.james.metrics.api.MetricFactory
import org.apache.james.user.api.DelegationStore

/**
 * Controls the JMAP WebSocket transport independently from WebPush subscriptions.
 * Disabled transports do not register GET or OPTIONS /jmap/ws with the JMAP server.
 */
class ConfigurableWebSocketRoutes @Inject() (
    @Named(InjectionKeys.RFC_8621) authenticator: Authenticator,
    configuration: JmapRfc8621Configuration,
    userProvisioner: UserProvisioning,
    @Named(JMAPInjectionKeys.JMAP) eventBus: EventBus,
    jmapApi: JMAPApi,
    mailboxChangeRepository: MailboxChangeRepository,
    emailChangeRepository: EmailChangeRepository,
    pushSerializer: PushSerializer,
    typeStateFactory: TypeStateFactory,
    delegationStore: DelegationStore,
    metricFactory: MetricFactory,
    @Named("jmap") jmapProperties: Configuration)
  extends WebSocketRoutes(authenticator, configuration, userProvisioner, eventBus,
    jmapApi, mailboxChangeRepository, emailChangeRepository, pushSerializer,
    typeStateFactory, delegationStore, metricFactory) {

  override def routes(): Stream[JMAPRoute] =
    if (jmapProperties.getBoolean("websocket.enabled", true)) super.routes()
    else Stream.empty[JMAPRoute]()
}
