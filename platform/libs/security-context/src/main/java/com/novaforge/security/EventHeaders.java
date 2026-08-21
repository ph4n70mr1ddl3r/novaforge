package com.novaforge.security;

/**
 * Header constants for the domain-event spine (producer/consumer bindings arrive with
 * the Phase 3 Kafka spine — PHASE-1 §3; the same keys ride the interim Redis pub/sub
 * envelope so consumers change only their client in Phase 3).
 */
public final class EventHeaders {

    /** Tenant that owns the event; mirrors the JWT claim. */
    public static final String TENANT_ID = "X-Tenant-Id";

    /** Actor that caused the event (the JWT {@code sub}). */
    public static final String ACTOR_ID = "X-Actor-Id";

    /** Event type, e.g. {@code record.created}. */
    public static final String EVENT_TYPE = "X-Event-Type";

    /** Unique event id for consumer dedup on (event_id, consumer) — ARCHITECTURE.md §6. */
    public static final String EVENT_ID = "X-Event-Id";

    /** W3C trace context propagation — ARCHITECTURE.md §6. */
    public static final String TRACEPARENT = "traceparent";

    private EventHeaders() {
    }
}
