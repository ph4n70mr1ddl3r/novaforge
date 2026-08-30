package com.novaforge.file.api;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.file.storage.StoragePort;
import com.novaforge.file.virus.VirusScanner;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The attachment lifecycle (PHASE-6 §8): a client requests an upload (the
 * attachment row opens + a presigned PUT, 15-minute expiry); the browser uploads
 * directly to storage; completion verifies the checksum server-side (SHA-256 over
 * the stored bytes — a mismatch rejects and deletes), runs the config-gated
 * ClamAV hook, and quarantines infected files (download blocked, audit event).
 * Download presigns are short-lived, attachment-scoped, and denied for
 * quarantined files. Values of the `file` field type are the attachment ids.
 */
@Service
public class AttachmentService {

    private static final Logger LOG = LoggerFactory.getLogger(AttachmentService.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final StoragePort storage;
    private final Optional<VirusScanner> scanner;
    private final boolean clamavEnabled;
    private final int presignExpirySeconds;
    private final long maxSizeBytes;
    private final Clock clock;
    private final io.micrometer.tracing.Tracer tracer;

    public AttachmentService(JdbcTemplate jdbc, StoragePort storage,
                             Optional<VirusScanner> scanner,
                             @Value("${novaforge.file.clamav.enabled:false}") boolean clamavEnabled,
                             @Value("${novaforge.file.presign-expiry-seconds:900}") int presignExpirySeconds,
                             @Value("${novaforge.file.max-size-bytes:52428800}") long maxSizeBytes,
                             Clock clock,
                             io.micrometer.tracing.Tracer tracer) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.scanner = scanner;
        this.clamavEnabled = clamavEnabled;
        this.presignExpirySeconds = presignExpirySeconds;
        this.maxSizeBytes = maxSizeBytes;
        this.clock = clock;
        this.tracer = tracer;
    }

    public record UploadGrant(UUID id, String uploadUrl, Instant expiresAt) {
    }

    /** Opens an attachment and returns the presigned PUT (the upload path, §8). */
    @Transactional
    public UploadGrant beginUpload(UUID tenantId, UUID actor, String fileName,
                                   String contentType, Long size, String entity, UUID recordId) {
        if (fileName == null || fileName.isBlank()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "uploads require a fileName");
        }
        if (size != null && size > maxSizeBytes) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "uploads are capped at " + maxSizeBytes + " bytes");
        }
        UUID id = UUID.randomUUID();
        String objectKey = tenantId + "/" + id;
        jdbc.update("""
                INSERT INTO fl_attachments (id, tenant_id, entity, record_id, object_key,
                                            file_name, content_type, size, virus_scan, uploaded_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?)""",
                id, tenantId, entity, recordId, objectKey, fileName,
                contentType == null || contentType.isBlank()
                        ? "application/octet-stream" : contentType, size, actor);
        grant(tenantId, id, "upload");
        String url = storage.presign(objectKey, StoragePort.Mode.UPLOAD, presignExpirySeconds);
        return new UploadGrant(id, url, Instant.now(clock).plusSeconds(presignExpirySeconds));
    }

    public record Completion(UUID id, String virusScan, String checksum, long size) {
    }

    /**
     * Upload completion (§8): the checksum is verified server-side over the stored
     * bytes — a client-supplied checksum that disagrees rejects and deletes; then
     * the config-gated ClamAV hook runs, quarantining detections. The verified bytes
     * are finalized under a checksum-derived key the upload URL can never address:
     * the presigned PUT outlives this call, and a replayed PUT against the staging
     * key must not be able to swap the content behind a recorded clean verdict.
     */
    @Transactional
    public Completion complete(UUID tenantId, UUID actor, UUID id, String clientChecksum) {
        Attachment attachment = require(tenantId, id);
        if (!attachment.uploadedBy().equals(actor)) {
            throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                    "only the uploader may complete an attachment");
        }
        // Stat before get: the presigned PUT carries no length constraint, so the cap
        // must reject on the stored size before the object is ever materialized.
        long storedSize = storage.size(attachment.objectKey());
        if (storedSize > maxSizeBytes) {
            storage.remove(attachment.objectKey());
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "uploaded object exceeds the size cap (" + storedSize + " bytes)");
        }
        byte[] content = storage.get(attachment.objectKey());
        if (content.length > maxSizeBytes) {
            storage.remove(attachment.objectKey());
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "uploaded object exceeds the size cap (" + content.length + " bytes)");
        }
        String checksum = sha256(content);
        if (clientChecksum != null && !clientChecksum.isBlank()
                && !checksum.equalsIgnoreCase(clientChecksum.trim())) {
            storage.remove(attachment.objectKey());
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "checksum mismatch — the stored object does not hash to the "
                            + "client-supplied SHA-256 (the object was deleted)",
                    com.novaforge.common.error.ProblemErrors.of(
                            new com.novaforge.common.error.ProblemErrors.FieldError(
                                    "checksum", "SHA-256 mismatch", clientChecksum)));
        }
        String scan = "skipped";
        if (clamavEnabled) {
            if (scanner.isEmpty()) {
                throw new PlatformException(PlatformErrorCode.INTERNAL,
                        "clamav.enabled but no scanner is wired");
            }
            scan = scanner.get().infected(content) ? "infected" : "clean";
        }
        jdbc.update("""
                UPDATE fl_attachments SET checksum = ?, size = ?, virus_scan = ?,
                                         updated_at = now()
                 WHERE tenant_id = ? AND id = ?""",
                checksum, content.length, scan, tenantId, id);
        // Finalize behind the verdict: the checksum key is content-addressed, so a
        // replayed PUT to the still-valid staging URL cannot alter what downloads serve.
        if (!"infected".equals(scan)) {
            storage.copy(attachment.objectKey(), finalizedKey(attachment.objectKey(), checksum));
        }
        if ("infected".equals(scan)) {
            // quarantine: download blocked + the audit event (§8)
            outbox(tenantId, "file.quarantined", Map.of(
                    "attachmentId", id.toString(),
                    "fileName", attachment.fileName(),
                    "checksum", checksum,
                    "uploadedBy", attachment.uploadedBy().toString(),
                    "size", content.length));
            LOG.error("attachment {} quarantined (virus scan: FOUND)", id);
        } else {
            outbox(tenantId, "file.completed", Map.of(
                    "attachmentId", id.toString(),
                    "fileName", attachment.fileName(),
                    "checksum", checksum,
                    "size", content.length,
                    "virusScan", scan));
        }
        return new Completion(id, scan, checksum, content.length);
    }

    /** Binds an attachment to its owning record (the file field's save path). */
    @Transactional
    public void bind(UUID tenantId, UUID id, String entity, UUID recordId) {
        Attachment existing = require(tenantId, id);
        if (existing.entity() != null || existing.recordId() != null) {
            // A rebind would move a confidential record's attachment onto one the
            // rebinder can read — the binding is write-once; correct a mistake by
            // uploading again.
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "attachment " + id + " is already bound to a record — bindings do not "
                            + "move (upload a new attachment instead)");
        }
        int updated = jdbc.update("""
                UPDATE fl_attachments SET entity = ?, record_id = ?, updated_at = now()
                 WHERE tenant_id = ? AND id = ? AND entity IS NULL AND record_id IS NULL""",
                entity, recordId, tenantId, id);
        if (updated == 0) {
            throw new PlatformException(PlatformErrorCode.NOT_FOUND, "attachment " + id);
        }
    }

    /** A short-lived, attachment-scoped download URL; quarantined files deny (§8). */
    @Transactional
    public UploadGrant presignDownload(UUID tenantId, UUID id) {
        Attachment attachment = require(tenantId, id);
        if ("infected".equals(attachment.virusScan())) {
            throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                    "attachment " + id + " is quarantined — download is blocked (§8)");
        }
        if (!"clean".equals(attachment.virusScan()) && !"skipped".equals(attachment.virusScan())) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "attachment " + id + " has not completed (checksum/scanning pending)");
        }
        String key = verifiedKey(tenantId, attachment);
        grant(tenantId, id, "download");
        String url = storage.presign(key, StoragePort.Mode.DOWNLOAD, presignExpirySeconds);
        return new UploadGrant(id, url, Instant.now(clock).plusSeconds(presignExpirySeconds));
    }

    public Optional<Map<String, Object>> metadata(UUID tenantId, UUID id) {
        return jdbc.query("""
                        SELECT id, entity, record_id, file_name, content_type, size, checksum,
                               virus_scan, uploaded_by, created_at
                          FROM fl_attachments WHERE tenant_id = ? AND id = ?""",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("entity", rs.getString("entity"));
                    row.put("recordId", rs.getObject("record_id", UUID.class));
                    row.put("fileName", rs.getString("file_name"));
                    row.put("contentType", rs.getString("content_type"));
                    row.put("size", rs.getObject("size") == null ? null : rs.getLong("size"));
                    row.put("checksum", rs.getString("checksum"));
                    row.put("virusScan", rs.getString("virus_scan"));
                    row.put("uploadedBy", rs.getObject("uploaded_by", UUID.class));
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    return row;
                }, tenantId, id).stream().findFirst();
    }

    /** The internal download leg (import sources, job outputs verified elsewhere). */
    public byte[] content(UUID tenantId, UUID id) {
        Attachment attachment = require(tenantId, id);
        if ("infected".equals(attachment.virusScan())) {
            // quarantined bytes never reach the import pipeline's parsers either
            throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                    "attachment " + id + " is quarantined — download is blocked (§8)");
        }
        if (attachment.checksum() == null) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "attachment " + id + " has not completed (checksum/scanning pending)");
        }
        return storage.get(verifiedKey(tenantId, attachment));
    }

    /**
     * The server-side upload leg (PHASE-6 §7): job outputs store as service-owned
     * attachments — open, put, complete in one call (checksum verified over the
     * stored bytes like every completion).
     */
    @Transactional
    public Completion storeServiceUpload(UUID tenantId, UUID actor, String fileName,
                                         String contentType, byte[] content) {
        UploadGrant grant = beginUpload(tenantId, actor, fileName, contentType,
                (long) content.length, null, null);
        storage.put(tenantId + "/" + grant.id(), content,
                contentType == null ? "application/octet-stream" : contentType);
        return complete(tenantId, actor, grant.id(), null);
    }

    // --- helpers ---

    record Attachment(UUID id, String objectKey, String fileName, String virusScan,
                      UUID uploadedBy, String checksum, String entity, UUID recordId) {
    }

    private Attachment require(UUID tenantId, UUID id) {
        return jdbc.query("""
                        SELECT id, object_key, file_name, virus_scan, uploaded_by, checksum,
                               entity, record_id
                          FROM fl_attachments WHERE tenant_id = ? AND id = ?""",
                (rs, i) -> new Attachment(rs.getObject("id", UUID.class),
                        rs.getString("object_key"), rs.getString("file_name"),
                        rs.getString("virus_scan"), rs.getObject("uploaded_by", UUID.class),
                        rs.getString("checksum"), rs.getString("entity"),
                        rs.getObject("record_id", UUID.class)),
                tenantId, id).stream().findFirst().orElseThrow(() ->
                new PlatformException(PlatformErrorCode.NOT_FOUND, "attachment " + id));
    }

    /**
     * The content-addressed finalization key: under the recorded checksum, so the
     * still-valid upload URL (which addresses the staging key) can never swap what a
     * completed verdict vouches for.
     */
    private static String finalizedKey(String objectKey, String checksum) {
        return objectKey + "/v/" + checksum;
    }

    /**
     * The key downloads serve, with lazy healing: a completed row finalizes its bytes
     * at completion, but rows completed before that existed (or whose finalized copy
     * was reclaimed) are healed from the staging object — but only if the staging
     * bytes still hash to the recorded checksum. Drift means the staging object was
     * tampered with after the verdict; it is rejected audibly, never served.
     */
    private String verifiedKey(UUID tenantId, Attachment attachment) {
        String finalized = finalizedKey(attachment.objectKey(), attachment.checksum());
        try {
            storage.size(finalized);
            return finalized;
        } catch (PlatformException notFinalized) {
            byte[] content = storage.get(attachment.objectKey());
            String actual = sha256(content);
            if (!actual.equalsIgnoreCase(attachment.checksum())) {
                outbox(tenantId, "file.tampered", Map.of(
                        "attachmentId", attachment.id().toString(),
                        "fileName", attachment.fileName(),
                        "recordedChecksum", String.valueOf(attachment.checksum()),
                        "actualChecksum", actual,
                        "uploadedBy", attachment.uploadedBy().toString()));
                LOG.error("attachment {} fails its recorded checksum — staging object "
                        + "tampered after completion; download denied", attachment.id());
                throw new PlatformException(PlatformErrorCode.INTERNAL,
                        "attachment " + attachment.id() + " no longer hashes to its "
                                + "recorded checksum — the object may have been tampered "
                                + "with; download denied");
            }
            storage.copy(attachment.objectKey(), finalized);
            return finalized;
        }
    }

    private void grant(UUID tenantId, UUID attachmentId, String mode) {
        jdbc.update("""
                INSERT INTO fl_grants (id, tenant_id, attachment, mode, expires_at)
                VALUES (?, ?, ?, ?, ?)""",
                UUID.randomUUID(), tenantId, attachmentId, mode,
                java.sql.Timestamp.from(Instant.now(clock).plusSeconds(presignExpirySeconds)));
    }

    private void outbox(UUID tenantId, String eventType, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>(payload);
        envelope.put("event", eventType);
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("tenantId", tenantId.toString());
        envelope.put("occurredAt", Instant.now(clock).toString());
        String traceparent = com.novaforge.security.TracePropagation.capture(tracer);
        if (traceparent != null) {
            envelope.put("traceparent", traceparent);
        }
        jdbc.update("""
                INSERT INTO fl_event_outbox (id, tenant_id, event_type, payload)
                VALUES (?, ?, ?, ?::jsonb)""",
                UUID.randomUUID(), tenantId, eventType, MAPPER.writeValueAsString(envelope));
    }

    public static String sha256(byte[] content) {
        try {
            return Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL, "digest failed");
        }
    }
}
