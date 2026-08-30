package com.novaforge.integration.api;

import com.novaforge.integration.webhook.InboundProcessor;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one anonymous API surface (PHASE-6 §2/§6): the gateway lifts its default JWT
 * requirement for exactly this prefix, and this service authenticates by the same
 * HMAC-SHA256 scheme that signs outbound — wrong secret, stale timestamp, or a
 * replayed signature all render {@code SIGNATURE_INVALID} problem+json. The
 * mapped payload applies through the Data Runtime write path as the integration
 * principal; a webhook is just another writer.
 */
@RestController
@RequestMapping("/api/v1/webhooks/inbound")
public class InboundWebhookController {

    private final InboundProcessor inbound;

    public InboundWebhookController(InboundProcessor inbound) {
        this.inbound = inbound;
    }

    @PostMapping(value = "/{tenant}/{entity}/{hookId}", consumes = MediaType.ALL_VALUE)
    public Map<String, Object> receive(@PathVariable String tenant, @PathVariable String entity,
                                       @PathVariable String hookId,
                                       @RequestHeader(value = "X-NovaForge-Timestamp",
                                               required = false) String timestamp,
                                       @RequestHeader(value = "X-NovaForge-Signature",
                                               required = false) String signature,
                                       @RequestBody(required = false) byte[] body) {
        return inbound.receive(tenant, entity, hookId,
                body == null ? new byte[0] : body, timestamp, signature);
    }
}
