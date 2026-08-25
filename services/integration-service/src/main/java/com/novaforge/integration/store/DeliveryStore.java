package com.novaforge.integration.store;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivery log + DLQ (PHASE-6 §3/§5/§6): every connector call, outbound dispatch,
 * and inbound application lands in the delivery log (status, attempts, latency,
 * response code — surfaced in the builder); terminal failures park in the DLQ with
 * the payload preserved for replay. Deliveries dedupe on (kind, target, dedupe
 * key) — the provider event id or body hash — so at-least-once callers collapse to
 * exactly-once effects; a repeat returns the recorded outcome, never a second call.
 */
@Repository
public class DeliveryStore {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final io.micrometer.tracing.Tracer tracer;

    public DeliveryStore(JdbcTemplate jdbc, io.micrometer.tracing.Tracer tracer) {
        this.jdbc = jdbc;
        this.tracer = tracer;
    }

    public static final String KIND_CONNECTOR = "connector";
    public static final String KIND_WEBHOOK_OUTBOUND = "webhook_outbound";
    public static final String KIND_WEBHOOK_INBOUND = "webhook_inbound";

    /** One delivery row: the log's shape (builder-facing, §5). */
    public record Delivery(UUID id, UUID tenantId, String kind, String target, String dedupeKey,
                           String status, int attempts, Integer lastStatus, Long latencyMs,
                           String responseSummary, String error, Instant createdAt,
                           Instant updatedAt) {
    }

    /** A previously recorded outcome for a dedupe key, if one exists. */
    public record Settled(String status, String responseSummary, String error) {

        public boolean delivered() {
            return "delivered".equals(status);
        }
    }

    /** Opens (or finds) the delivery row for a dedupe key. */
    public Optional<Settled> settleOrOpen(UUID tenantId, String kind, String target,
                                          String dedupeKey, String requestJson) {
        int inserted = jdbc.update("""
                INSERT INTO it_deliveries (id, tenant_id, kind, target, dedupe_key, status,
                                           attempts, request_payload)
                VALUES (?, ?, ?, ?, ?, 'pending', 0, ?::jsonb)
                ON CONFLICT DO NOTHING""",
                UUID.randomUUID(), tenantId, kind, target, dedupeKey, requestJson);
        if (inserted == 1) {
            return Optional.empty();   // ours — the caller executes the delivery
        }
        return find(tenantId, kind, target, dedupeKey).map(delivery ->
                new Settled(delivery.status(), delivery.responseSummary(), delivery.error()));
    }

    public Optional<Delivery> find(UUID tenantId, String kind, String target, String dedupeKey) {
        return jdbc.query("""
                        SELECT * FROM it_deliveries
                         WHERE tenant_id = ? AND kind = ? AND target = ? AND dedupe_key = ?""",
                (rs, i) -> row(rs), tenantId, kind, target, dedupeKey).stream().findFirst();
    }

    /** Records an attempt and its outcome; a terminal `dlq` move parks the payload. */
    public void record(UUID id, String status, Integer lastStatus, Long latencyMs,
                       String responseSummary, String error) {
        jdbc.update("""
                UPDATE it_deliveries
                   SET status = ?, attempts = attempts + 1, last_status = ?, latency_ms = ?,
                       response_summary = ?, error = ?, updated_at = now()
                 WHERE id = ?""",
                status, lastStatus, latencyMs, responseSummary, error, id);
    }

    /** Delivery log page, newest first — the builder's §5 surface. */
    public List<Delivery> log(UUID tenantId, String kind, int limit) {
        return jdbc.query("""
                        SELECT * FROM it_deliveries
                         WHERE tenant_id = ? AND (? IS NULL OR kind = ?)
                         ORDER BY created_at DESC LIMIT ?""",
                (rs, i) -> row(rs), tenantId, kind, kind, limit);
    }

    // --- DLQ ---

    /** One DLQ entry: the payload preserved verbatim for replay from the builder. */
    public record DlqEntry(UUID id, UUID tenantId, String kind, String target, String payload,
                           String signature, String error, int attempts, Instant createdAt,
                           Instant replayedAt) {
    }

    public UUID park(UUID tenantId, String kind, String target, Map<String, Object> envelope,
                     String signature, String error) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO it_dlq (id, tenant_id, kind, target, payload, signature, error, attempts)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, 1)""",
                id, tenantId, kind, target, MAPPER.writeValueAsString(envelope), signature, error);
        return id;
    }

    public List<DlqEntry> dlq(UUID tenantId, String kind, boolean openOnly) {
        return jdbc.query("""
                        SELECT * FROM it_dlq
                         WHERE tenant_id = ? AND (? IS NULL OR kind = ?)
                           AND (? OR replayed_at IS NULL)
                         ORDER BY created_at DESC LIMIT 200""",
                (rs, i) -> new DlqEntry(rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class), rs.getString("kind"),
                        rs.getString("target"), rs.getString("payload"), rs.getString("signature"),
                        rs.getString("error"), rs.getInt("attempts"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("replayed_at") == null ? null
                                : rs.getTimestamp("replayed_at").toInstant()),
                tenantId, kind, kind, !openOnly);
    }

    public Optional<DlqEntry> dlqEntry(UUID tenantId, UUID id) {
        return jdbc.query("SELECT * FROM it_dlq WHERE tenant_id = ? AND id = ?",
                (rs, i) -> {
                    Timestamp replayed = rs.getTimestamp("replayed_at");
                    return new DlqEntry(rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class), rs.getString("kind"),
                            rs.getString("target"), rs.getString("payload"),
                            rs.getString("signature"), rs.getString("error"),
                            rs.getInt("attempts"), rs.getTimestamp("created_at").toInstant(),
                            replayed == null ? null : replayed.toInstant());
                }, tenantId, id).stream().findFirst();
    }

    /** Marks a DLQ entry replayed — replay keeps its own delivery-log row. */
    public void markReplayed(UUID tenantId, UUID id) {
        int updated = jdbc.update(
                "UPDATE it_dlq SET replayed_at = now() WHERE tenant_id = ? AND id = ?",
                tenantId, id);
        if (updated == 0) {
            throw new PlatformException(PlatformErrorCode.NOT_FOUND, "dlq entry " + id);
        }
    }

    // --- inbound replay nonces (§5/§11) ---

    /**
     * Records a signature digest inside the window; a second registration of the
     * same digest is a literal replay and returns false.
     */
    public boolean claimReplayNonce(UUID tenantId, String hookId, String signatureDigest) {
        purgeOldNonces();
        int inserted = jdbc.update("""
                INSERT INTO it_inbound_seen (tenant_id, hook_id, signature_hash)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING""",
                tenantId, hookId, signatureDigest);
        return inserted == 1;
    }

    private void purgeOldNonces() {
        jdbc.update("DELETE FROM it_inbound_seen WHERE seen_at < now() - interval '10 minutes'");
    }

    // --- spine outbox (connector.delivered / webhook.dispatched / import.progress) ---

    public void outbox(UUID tenantId, String eventType, Map<String, Object> payload) {
        Map<String, Object> envelope = new java.util.LinkedHashMap<>(payload);
        envelope.put("event", eventType);
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("tenantId", tenantId.toString());
        envelope.put("occurredAt", Instant.now().toString());
        String traceparent = com.novaforge.security.TracePropagation.capture(tracer);
        if (traceparent != null) {
            envelope.put("traceparent", traceparent);
        }
        jdbc.update("""
                INSERT INTO it_event_outbox (id, tenant_id, event_type, payload)
                VALUES (?, ?, ?, ?::jsonb)""",
                UUID.randomUUID(), tenantId, eventType, MAPPER.writeValueAsString(envelope));
    }

    private static Delivery row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Delivery(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("kind"), rs.getString("target"), rs.getString("dedupe_key"),
                rs.getString("status"), rs.getInt("attempts"),
                rs.getObject("last_status") == null ? null : rs.getInt("last_status"),
                rs.getObject("latency_ms") == null ? null : rs.getLong("latency_ms"),
                rs.getString("response_summary"), rs.getString("error"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
