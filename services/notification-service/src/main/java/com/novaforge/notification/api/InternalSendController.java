package com.novaforge.notification.api;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.notification.notify.Notifier;
import com.novaforge.notification.notify.RecipientResolver;
import com.novaforge.security.ServiceClientGate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The internal send surface (PHASE-5 §7): the Reporting Service's scheduled
 * delivery leg — explicit recipients (platform role names and/or user ids) instead
 * of a spine event's task payload, with the rendered export riding inline. The same
 * preference filtering, inbox row, email attachment leg, and {@code
 * notification.delivered} audit per channel as the event fan-out; synthetic actors
 * skip entirely (ADR-010 #3). Service-client gated like every internal surface —
 * never user traffic through the gateway.
 */
@RestController
@RequestMapping("/api/v1/notifications/internal")
public class InternalSendController {

    private final Notifier notifier;
    private final RecipientResolver recipients;

    public InternalSendController(Notifier notifier, RecipientResolver recipients) {
        this.notifier = notifier;
        this.recipients = recipients;
    }

    public record SendRequest(String tenantId, String category, String title, String body,
                              Map<String, Object> recipients, AttachmentEnvelope attachment,
                              String deliveryId) {
    }

    /** The inline export — base64 over the wire, bytes at the SMTP leg. */
    public record AttachmentEnvelope(String filename, String contentType, String contentBase64) {
    }

    @PostMapping("/send")
    public Map<String, Object> send(@RequestBody SendRequest request) {
        ServiceClientGate.require("notification-send");
        if (request.tenantId() == null || request.category() == null
                || request.category().isBlank()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "internal sends require tenantId and category");
        }
        UUID tenantId;
        try {
            tenantId = UUID.fromString(request.tenantId());
        } catch (IllegalArgumentException e) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "tenantId must be a uuid: " + request.tenantId());
        }
        // the caller's idempotency key — a replayed keyed send collapses on the inbox
        // dedupe instead of duplicating rows and emails for every recipient
        if (request.deliveryId() != null && !request.deliveryId().isBlank()
                && (request.deliveryId().length() > 128
                    || !request.deliveryId().matches("[\\w.:@+-]+"))) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "deliveryId must be a short word-shaped key: " + request.deliveryId());
        }
        // platform role names resolve to holders (the runtime's tenant-scoped read);
        // explicit user ids are CALLER-NAMED, so each one must prove membership in
        // the sending tenant before it may receive its data — a foreign tenant's id
        // drops with a warn (the cross-tenant leak case, 2026-08-31), never delivers.
        // One deduped recipient list, order-stable for deterministic delivery.
        Set<UUID> users = new LinkedHashSet<>();
        for (String role : rolesOf(request.recipients())) {
            users.addAll(recipients.holdersOf(tenantId, role));
        }
        for (UUID named : usersOf(request.recipients())) {
            if (recipients.belongsTo(tenantId, named)) {
                users.add(named);
            }
        }
        if (users.isEmpty()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "internal sends require at least one resolved recipient "
                            + "(roles: " + rolesOf(request.recipients()) + ", users: "
                            + usersOf(request.recipients()) + ")");
        }
        Notifier.Attachment attachment = null;
        if (request.attachment() != null) {
            if (request.attachment().filename() == null
                    || request.attachment().contentBase64() == null) {
                throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "attachments require filename and contentBase64");
            }
            try {
                attachment = new Notifier.Attachment(request.attachment().filename(),
                        request.attachment().contentType() == null ? "application/octet-stream"
                                : request.attachment().contentType(),
                        Base64.getDecoder().decode(request.attachment().contentBase64()));
            } catch (IllegalArgumentException e) {
                throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "attachment contentBase64 does not decode: " + e.getMessage(), null, e);
            }
        }
        int delivered = notifier.deliverDirect(tenantId, request.category(),
                request.title() == null ? "" : request.title(),
                request.body() == null ? "" : request.body(),
                new ArrayList<>(users), attachment, request.deliveryId());
        return Map.of("delivered", delivered, "recipients", users.size());
    }

    private static List<String> rolesOf(Map<String, Object> recipients) {
        if (recipients == null || !(recipients.get("roles") instanceof List<?> roles)) {
            return List.of();
        }
        return roles.stream().map(String::valueOf).toList();
    }

    private static List<UUID> usersOf(Map<String, Object> recipients) {
        if (recipients == null || !(recipients.get("users") instanceof List<?> users)) {
            return List.of();
        }
        try {
            return users.stream().map(u -> UUID.fromString(String.valueOf(u))).toList();
        } catch (IllegalArgumentException e) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "recipient users must be uuids: " + e.getMessage(), null, e);
        }
    }
}
