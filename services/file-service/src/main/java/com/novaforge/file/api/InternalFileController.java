package com.novaforge.file.api;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.security.ServiceClientGate;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The internal file surface (PHASE-6 §7/§8): the Integration Service's job
 * outputs upload here (server-side, not the browser's presigned channel) and
 * import sources download by attachment id — the byte legs behind async exports
 * and checkpointed imports. Service-client gated — never user traffic.
 */
@RestController
@RequestMapping("/api/v1/files/internal")
public class InternalFileController {

    private final AttachmentService attachments;

    public InternalFileController(AttachmentService attachments) {
        this.attachments = attachments;
    }

    public record InternalUpload(String tenantId, String fileName, String contentType,
                                 String contentBase64, String uploadedBy, String entity,
                                 String recordId) {
    }

    /** Stores a complete job output as a service-owned attachment (§7). */
    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestBody InternalUpload request) {
        ServiceClientGate.require("file-upload");
        if (request.tenantId() == null || request.fileName() == null
                || request.contentBase64() == null) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "internal uploads require tenantId, fileName, and contentBase64");
        }
        byte[] content;
        try {
            content = Base64.getDecoder().decode(request.contentBase64());
        } catch (IllegalArgumentException e) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "contentBase64 does not decode: " + e.getMessage(), null, e);
        }
        UUID tenantId = UUID.fromString(request.tenantId());
        var completion = attachments.storeServiceUpload(tenantId,
                UUID.fromString(request.uploadedBy() == null
                        ? "00000000-0000-0000-0000-000000000000" : request.uploadedBy()),
                request.fileName(), request.contentType(), content);
        return Map.of("id", completion.id(), "checksum", completion.checksum(),
                "size", completion.size());
    }

    /** The import source leg: bytes by attachment id (§7). */
    @GetMapping("/{id}")
    public Map<String, Object> download(@PathVariable UUID id,
                                        @RequestParam String tenantId) {
        ServiceClientGate.require("file-download");
        byte[] content = attachments.content(UUID.fromString(tenantId), id);
        return Map.of("contentBase64", Base64.getEncoder().encodeToString(content));
    }
}
