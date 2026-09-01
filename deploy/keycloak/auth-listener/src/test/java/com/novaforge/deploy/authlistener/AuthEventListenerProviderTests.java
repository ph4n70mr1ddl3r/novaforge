package com.novaforge.deploy.authlistener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.events.Event;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.mockito.ArgumentCaptor;

/**
 * The SPI provider itself was never pinned (twenty-ninth pass coverage audit) —
 * only its envelope helper was — so the provider's behavioral contract rode on
 * the compose stack's live behavior: tenant resolution from the provisioned
 * {@code tenant_id} attribute (non-tenant logins SKIP — the trail is
 * tenant-scoped by construction), the fire-and-forget contract (a spine hiccup
 * must never fail the login it audits), and the routing to {@code novaforge.auth}
 * with the tenant-scoped partition key. All pinned here against mocked SPI
 * graphs — no Keycloak runtime.
 */
class AuthEventListenerProviderTests {

    private static final String TENANT = "11111111-1111-4111-8111-111111111111";
    private static final String USER = "33333333-3333-4333-8333-333333333333";
    private static final String REALM_ID = "novaforge";

    private KeycloakSession session;
    private RealmModel realm;
    private KafkaProducer<String, String> producer;
    private AuthEventListenerProvider provider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        session = mock(KeycloakSession.class, withSettings().lenient());
        realm = mock(RealmModel.class, withSettings().lenient());
        when(realm.getId()).thenReturn(REALM_ID);
        RealmProvider realms = mock(RealmProvider.class, withSettings().lenient());
        when(realms.getRealm(REALM_ID)).thenReturn(realm);
        when(session.realms()).thenReturn(realms);
        producer = mock(KafkaProducer.class);
        when(producer.send(any())).thenReturn(new CompletableFuture<>());
        provider = new AuthEventListenerProvider(session, producer);
    }

    private Event event(EventType type) {
        Event event = new Event();
        event.setType(type);
        event.setRealmId(REALM_ID);
        event.setUserId(USER);
        event.setClientId("novaforge-api");
        event.setDetails(Map.of("username", "demo"));
        return event;
    }

    private void userWithTenant(String tenantId) {
        UserModel user = mock(UserModel.class);
        when(user.getFirstAttribute(AuthEventListenerProvider.TENANT_ATTRIBUTE))
                .thenReturn(tenantId);
        UserProvider users = mock(UserProvider.class);
        when(users.getUserById(realm, USER)).thenReturn(user);
        when(session.users()).thenReturn(users);
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> capturedRecord() {
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer).send(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a tenant user's LOGIN publishes to novaforge.auth with the tenant key and headers")
    void tenantLoginPublishes() {
        userWithTenant(TENANT);
        provider.onEvent(event(EventType.LOGIN));

        ProducerRecord<String, String> record = capturedRecord();
        assertThat(record.topic()).isEqualTo(AuthEvents.TOPIC);
        assertThat(record.key()).isEqualTo(AuthEvents.key(TENANT, USER));
        assertThat(record.value()).contains("\"event\":\"auth.login\"")
                .contains("\"tenantId\":\"" + TENANT + "\"")
                .contains("\"userId\":\"" + USER + "\"");
        assertThat(new String(record.headers().lastHeader("X-Tenant-Id").value()))
                .isEqualTo(TENANT);
        assertThat(record.headers().lastHeader("X-Event-Type")).isNotNull();
        assertThat(record.headers().lastHeader("X-Event-Id")).isNotNull();
    }

    @Test
    @DisplayName("a login WITHOUT a tenant attribute skips — platform/service logins are not trail-scoped")
    void nonTenantLoginSkips() {
        userWithTenant(null);
        provider.onEvent(event(EventType.LOGIN));
        verify(producer, never()).send(any());
    }

    @Test
    @DisplayName("a non-auditable event type never touches the session or the producer")
    void nonAuditableSkipsEverything() {
        provider.onEvent(event(EventType.REFRESH_TOKEN));
        verify(producer, never()).send(any());
        verify(session, never()).realms();
    }

    @Test
    @DisplayName("a spine hiccup NEVER fails the login: the RuntimeException swallows")
    void spineHiccupDoesNotFailLogin() {
        userWithTenant(TENANT);
        when(producer.send(any())).thenThrow(new RuntimeException("kafka down"));
        assertThatCode(() -> provider.onEvent(event(EventType.LOGIN_ERROR)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an unreadable user graph also swallows — the trail degrades, the login holds")
    void unreadableUserGraphSwallows() {
        UserProvider users = mock(UserProvider.class);
        when(users.getUserById(any(), any())).thenThrow(new RuntimeException("store down"));
        when(session.users()).thenReturn(users);
        assertThatCode(() -> provider.onEvent(event(EventType.LOGOUT)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("admin events stay out of v1: no publish, no session walk")
    void adminEventsIgnored() {
        provider.onEvent(mock(AdminEvent.class), false);
        verify(producer, never()).send(any());
        verify(session, never()).realms();
    }

    @Test
    @DisplayName("a null user id (service-client login) skips before any lookup")
    void nullUserIdSkips() {
        Event login = event(EventType.LOGIN);
        login.setUserId(null);
        userWithTenant(TENANT);
        provider.onEvent(login);
        verify(session, never()).users();
        verify(producer, never()).send(any());
    }
}
