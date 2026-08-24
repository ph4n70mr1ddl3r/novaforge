package com.novaforge.deploy.authlistener;

import java.util.Map;
import java.util.Set;
import org.keycloak.events.Event;
import org.keycloak.events.EventType;

/**
 * The auth-event envelope (PHASE-3 §4/§5): {@code auth.login},
 * {@code auth.login.error}, {@code auth.logout}, {@code auth.logout.error} — the
 * closed v1 set of Keycloak user events the audit trail consumes. Pure mapping,
 * unit-tested without a Keycloak runtime.
 */
public final class AuthEvents {

    /** The spine's auth family topic (PHASE-3 §4 topology). */
    public static final String TOPIC = "novaforge.auth";

    private static final Set<EventType> AUTH_EVENTS = Set.of(
            EventType.LOGIN, EventType.LOGIN_ERROR, EventType.LOGOUT, EventType.LOGOUT_ERROR);

    private AuthEvents() {
    }

    /** Whether the event type belongs to the audited v1 set. */
    public static boolean auditable(EventType type) {
        return type != null && AUTH_EVENTS.contains(type);
    }

    /**
     * The spine envelope: {@code eventId}/{@code event}/{@code tenantId}/
     * {@code actorId}/{@code occurredAt} plus the login context (username from the
     * event's details map — Keycloak 26 carries it there, client, address, error) —
     * the same contract every family rides. {@code occurredAt} derives from the
     * event's own timestamp, not the publisher's clock.
     */
    public static Map<String, Object> envelope(Event event, String tenantId, String eventId) {
        String type = event.getType() == null ? "" : event.getType().name();
        Map<String, String> details = event.getDetails() == null
                ? Map.of() : event.getDetails();
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("event", "auth." + type.toLowerCase().replace('_', '.'));
        payload.put("eventId", eventId);
        payload.put("tenantId", tenantId);
        payload.put("userId", event.getUserId());
        payload.put("actorId", event.getUserId());
        payload.put("username", details.getOrDefault("username", ""));
        payload.put("clientId", nullSafe(event.getClientId()));
        payload.put("ipAddress", nullSafe(event.getIpAddress()));
        if (event.getError() != null) {
            payload.put("error", event.getError());
        }
        payload.put("occurredAt", (event.getTime() > 0
                ? java.time.Instant.ofEpochMilli(event.getTime())
                : java.time.Instant.now()).toString());
        return payload;
    }

    /** The partition key: {@code tenantId:userId} — per-user ordering (§4). */
    public static String key(String tenantId, String userId) {
        return tenantId + ":" + userId;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
