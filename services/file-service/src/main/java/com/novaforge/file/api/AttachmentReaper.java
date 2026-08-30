package com.novaforge.file.api;

import com.novaforge.security.TracePropagation;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The abandoned-upload reaper: a client that walks away between the upload grant and
 * completion leaves a {@code pending} row plus an orphaned object forever — nothing
 * else ever touched them (remove ran only on checksum mismatch/oversize), so busy
 * tenants grew both the table and the bucket without bound. A pending attachment
 * whose grant window is long expired (creation + the presign window + slack — by
 * then every grant on it is dead) has its object deleted and its row dropped, with
 * a {@code file.upload.expired} event on the outbox so the cleanup is auditable.
 * Completed attachments are never touched; neither are pending rows still inside
 * their window. The write-only grant ledger prunes with the same pass (expired
 * beyond the retention window — its server-side record outlives its purpose).
 */
@Component
public class AttachmentReaper {

    private static final Logger LOG = LoggerFactory.getLogger(AttachmentReaper.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final com.novaforge.file.storage.StoragePort storage;
    private final int reapAfterMinutes;
    private final int grantRetentionDays;
    private final io.micrometer.tracing.Tracer tracer;

    public AttachmentReaper(JdbcTemplate jdbc,
                            com.novaforge.file.storage.StoragePort storage,
                            @Value("${novaforge.file.reap-after-minutes:60}") int reapAfterMinutes,
                            @Value("${novaforge.events.retention-days:7}") int grantRetentionDays,
                            Tracer tracer) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.reapAfterMinutes = reapAfterMinutes;
        this.grantRetentionDays = grantRetentionDays;
        this.tracer = tracer;
    }

    @Scheduled(fixedDelayString = "${novaforge.file.reap-interval-ms:3600000}")
    public void reap() {
        var abandoned = jdbc.query("""
                        SELECT id, tenant_id, object_key, file_name FROM fl_attachments
                         WHERE virus_scan = 'pending'
                           AND created_at < now() - (? * interval '1 minute')""",
                (rs, i) -> Map.entry(rs.getObject("id", UUID.class),
                        new Object[] {rs.getObject("tenant_id", UUID.class),
                                rs.getString("object_key"), rs.getString("file_name")}),
                reapAfterMinutes);
        for (var entry : abandoned) {
            UUID id = entry.getKey();
            Object[] row = entry.getValue();
            try {
                storage.remove((String) row[1]);   // best-effort: absent objects reap too
            } catch (Exception e) {
                LOG.warn("reaper could not remove object for abandoned upload {}: {}",
                        id, e.getMessage());
            }
            jdbc.update("DELETE FROM fl_attachments WHERE id = ?", id);
            jdbc.update("DELETE FROM fl_grants WHERE attachment = ?", id);
            outbox((UUID) row[0], "file.upload.expired", Map.of(
                    "attachmentId", id.toString(),
                    "fileName", String.valueOf(row[2])));
        }
        if (!abandoned.isEmpty()) {
            LOG.info("reaped {} abandoned upload(s) past their {}-minute window",
                    abandoned.size(), reapAfterMinutes);
        }
        int grants = jdbc.update("""
                DELETE FROM fl_grants WHERE expires_at < now() - (? * interval '1 day')""",
                grantRetentionDays);
        if (grants > 0) {
            LOG.info("grant ledger pruned {} row(s) past {} day(s)", grants, grantRetentionDays);
        }
    }

    private void outbox(UUID tenantId, String eventType, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>(payload);
        envelope.put("event", eventType);
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("tenantId", tenantId.toString());
        envelope.put("occurredAt", Instant.now().toString());
        String traceparent = TracePropagation.capture(tracer);
        if (traceparent != null) {
            envelope.put("traceparent", traceparent);
        }
        jdbc.update("""
                INSERT INTO fl_event_outbox (id, tenant_id, event_type, payload)
                VALUES (?, ?, ?, ?::jsonb)""",
                UUID.randomUUID(), tenantId, eventType, MAPPER.writeValueAsString(envelope));
    }
}
