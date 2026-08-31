package com.novaforge.file.api;

import com.novaforge.common.context.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public file surface (PHASE-6 §8/§9, user+ at the route): upload grants
 * (presigned PUT, 15-minute expiry), completion with server-side checksum
 * verification and the config-gated ClamAV hook, metadata reads, and presigned
 * downloads — attachment-scoped, denied for quarantined files, and governed by
 * the owning record's authorization when the attachment is bound (§9).
 */
@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final AttachmentService attachments;
    private final RecordReadGate recordGate;

    public FileController(AttachmentService attachments, RecordReadGate recordGate) {
        this.attachments = attachments;
        this.recordGate = recordGate;
    }

    public record UploadRequest(String fileName, String contentType, Long size,
                                String entity, UUID recordId) {
    }

    /** Opens the attachment + presigned PUT (the `file` field's upload path, §8). */
    @PostMapping("/uploads")
    public Map<String, Object> beginUpload(@RequestBody UploadRequest request) {
        // a caller-supplied target must already be readable by THIS caller: the
        // stored tag makes the attachment record-governed from the first moment
        // (§9's access rule reads it back), and an ungated tag let any same-tenant
        // user plant attachment metadata on records they cannot read
        requireRecordReadable(request.entity(), request.recordId());
        var grant = attachments.beginUpload(RecordReadGate.tenant(), actor(), request.fileName(),
                request.contentType(), request.size(), request.entity(), request.recordId());
        return Map.of(
                "id", grant.id(),
                "uploadUrl", grant.uploadUrl(),
                "expiresAt", grant.expiresAt().toString(),
                "method", "PUT");
    }

    public record CompleteRequest(String checksum, String entity, UUID recordId) {
    }

    /** Verifies the checksum server-side, scans (config-gated), quarantines (§8). */
    @PostMapping("/{id}/complete")
    public Map<String, Object> complete(@PathVariable UUID id,
                                        @RequestBody(required = false) CompleteRequest request) {
        // the bind gate runs BEFORE completion — a doomed bind fires no external
        // side effects (the ClamAV scan is one), and paying it first strands a
        // completed attachment that can never accept its bind
        if (request != null && request.entity() != null && request.recordId() != null) {
            requireRecordReadable(request.entity(), request.recordId());
        }
        var completion = attachments.complete(RecordReadGate.tenant(), actor(), id,
                request == null ? null : request.checksum());
        if (request != null && request.entity() != null && request.recordId() != null) {
            attachments.bind(RecordReadGate.tenant(), id, request.entity(), request.recordId());
        }
        return Map.of(
                "id", completion.id(),
                "virusScan", completion.virusScan(),
                "checksum", completion.checksum(),
                "size", completion.size());
    }

    @GetMapping("/{id}")
    public Map<String, Object> metadata(@PathVariable UUID id) {
        return requireAccess(id);
    }

    /** Presigned GET — owning-record authorization decides for bound attachments (§9). */
    @PostMapping("/{id}/download")
    public Map<String, Object> download(@PathVariable UUID id) {
        requireAccess(id);
        var grant = attachments.presignDownload(RecordReadGate.tenant(), id);
        return Map.of(
                "id", grant.id(),
                "downloadUrl", grant.uploadUrl(),
                "expiresAt", grant.expiresAt().toString(),
                "method", "GET");
    }

    /**
     * The §9 record gate for the BINDING doors: a target record this caller cannot
     * read never accepts their attachment. Fail-closed on an unreachable runtime
     * (canRead throws) — an outage must not open the planting window.
     */
    private void requireRecordReadable(String entity, UUID recordId) {
        if (entity == null || recordId == null) {
            return;
        }
        if (!recordGate.canRead(entity, recordId)) {
            throw new com.novaforge.common.error.PlatformException(
                    com.novaforge.common.error.PlatformErrorCode.FORBIDDEN,
                    "binding rides the owning record's authorization (§9) — "
                            + entity + "/" + recordId + " is not readable by this caller");
        }
    }

    /**
     * The §9 access rule, uniform across the metadata read and the download: a bound
     * attachment rides the owning record's authorization; an unbound one is the
     * uploader's (or the service client's) alone. The metadata read used to skip this
     * — any same-tenant user holding an id learned which record carries which file.
     */
    private Map<String, Object> requireAccess(UUID id) {
        var metadata = attachments.metadata(RecordReadGate.tenant(), id).orElseThrow(() ->
                new com.novaforge.common.error.PlatformException(
                        com.novaforge.common.error.PlatformErrorCode.NOT_FOUND,
                        "attachment " + id));
        String entity = (String) metadata.get("entity");
        Object recordId = metadata.get("recordId");
        if (entity != null && recordId != null) {
            if (!recordGate.canRead(entity, UUID.fromString(String.valueOf(recordId)))) {
                throw new com.novaforge.common.error.PlatformException(
                        com.novaforge.common.error.PlatformErrorCode.FORBIDDEN,
                        "attachment access rides the owning record's authorization (§9)");
            }
            return metadata;
        }
        if (!actor().equals(metadata.get("uploadedBy"))) {
            throw new com.novaforge.common.error.PlatformException(
                    com.novaforge.common.error.PlatformErrorCode.FORBIDDEN,
                    "an unbound attachment is its uploader's alone until it is bound (§9)");
        }
        return metadata;
    }

    private static UUID actor() {
        return UUID.fromString(TenantContext.require().actorId());
    }
}
