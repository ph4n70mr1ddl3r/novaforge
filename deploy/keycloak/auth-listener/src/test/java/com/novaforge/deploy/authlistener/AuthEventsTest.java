package com.novaforge.deploy.authlistener;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.events.Event;
import org.keycloak.events.EventType;

/**
 * The auth-event mapping (PHASE-3 §4/§5): the closed v1 event set, the spine
 * envelope shape, and the family key — the audit consumer's contract, pinned
 * without a Keycloak runtime.
 */
class AuthEventsTest {

    @Test
    @DisplayName("the v1 audited set is closed: login/logout (+errors); nothing else")
    void auditableSetIsClosed() {
        assertThat(AuthEvents.auditable(EventType.LOGIN)).isTrue();
        assertThat(AuthEvents.auditable(EventType.LOGIN_ERROR)).isTrue();
        assertThat(AuthEvents.auditable(EventType.LOGOUT)).isTrue();
        assertThat(AuthEvents.auditable(EventType.LOGOUT_ERROR)).isTrue();
        assertThat(AuthEvents.auditable(EventType.REFRESH_TOKEN)).isFalse();
        assertThat(AuthEvents.auditable(EventType.UPDATE_PASSWORD)).isFalse();
        assertThat(AuthEvents.auditable(null)).isFalse();
    }

    @Test
    @DisplayName("envelope: the family contract — eventId/event/tenantId/actorId/occurredAt + context")
    void envelopeCarriesTheSpineContract() {
        Event event = new Event();
        event.setType(EventType.LOGIN_ERROR);
        event.setUserId("33333333-3333-4333-8333-333333333333");
        event.setDetails(Map.of("username", "demo"));
        event.setClientId("novaforge-api");
        event.setIpAddress("10.0.0.9");
        event.setError("invalid_user_credentials");
        event.setTime(1787313600000L);

        Map<String, Object> envelope = AuthEvents.envelope(event,
                "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222");

        assertThat(envelope.get("event")).isEqualTo("auth.login.error");
        assertThat(envelope.get("eventId")).isEqualTo("22222222-2222-4222-8222-222222222222");
        assertThat(envelope.get("tenantId")).isEqualTo("11111111-1111-4111-8111-111111111111");
        assertThat(envelope.get("actorId")).isEqualTo("33333333-3333-4333-8333-333333333333");
        assertThat(envelope.get("username")).isEqualTo("demo");
        assertThat(envelope.get("clientId")).isEqualTo("novaforge-api");
        assertThat(envelope.get("ipAddress")).isEqualTo("10.0.0.9");
        assertThat(envelope.get("error")).isEqualTo("invalid_user_credentials");
        assertThat(String.valueOf(envelope.get("occurredAt"))).isEqualTo("2026-08-21T12:00:00Z");
        assertThat(AuthEvents.key("11111111-1111-4111-8111-111111111111",
                "33333333-3333-4333-8333-333333333333"))
                .isEqualTo("11111111-1111-4111-8111-111111111111:33333333-3333-4333-8333-333333333333");
    }

    @Test
    @DisplayName("error-free events carry no error key; blanks stay blanks, never nulls")
    void cleanEventShape() {
        Event event = new Event();
        event.setType(EventType.LOGOUT);
        event.setUserId("33333333-3333-4333-8333-333333333333");

        Map<String, Object> envelope = AuthEvents.envelope(event,
                "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222");

        assertThat(envelope).doesNotContainKey("error");
        assertThat(envelope.get("username")).isEqualTo("");
        assertThat(envelope.get("event")).isEqualTo("auth.logout");
    }
}
