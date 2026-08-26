package com.novaforge.workflow.api;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.security.ServiceClientGate;
import com.novaforge.workflow.sla.SlaScanner;
import com.novaforge.workflow.tenants.TenantLookup;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The internal as-of SLA scan surface (PHASE-4 §12's clock-advanced leg): the
 * builder test harness drives warn/breach/escalation deterministically — the
 * governing instant is the caller's, so suite assertions need no sleeps. Gated
 * twice: the platform service client only (like every internal surface), and the
 * scratch tenant check — the harness's fresh {@code scratch-*} tenants answer, real
 * tenants never do, so time cannot be advanced against production data. A scan
 * path, not a write path: ADR-010 #3's no-test-mode rule is untouched.
 */
@RestController
@RequestMapping("/api/v1/workflow/internal")
public class InternalSlaController {

    private final SlaScanner scanner;
    private final TenantLookup tenants;

    public InternalSlaController(SlaScanner scanner, TenantLookup tenants) {
        this.scanner = scanner;
        this.tenants = tenants;
    }

    public record ScanRequest(UUID tenantId, String advance, String asOf) {
    }

    @PostMapping("/sla/scan")
    public Map<String, Object> scan(@RequestBody ScanRequest request) {
        ServiceClientGate.require("sla-scan");
        if (request.tenantId() == null) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "sla scan requires tenantId");
        }
        if (!tenants.isScratch(request.tenantId())) {
            throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                    "the sla-scan surface answers scratch tenants only (§12)");
        }
        boolean advanced = request.advance() != null && !request.advance().isBlank();
        boolean absolute = request.asOf() != null && !request.asOf().isBlank();
        if (advanced == absolute) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "sla scan requires exactly one of advance (ISO-8601 duration) or asOf (instant)");
        }
        Instant asOf;
        try {
            asOf = advanced ? Instant.now().plus(Duration.parse(request.advance()))
                    : Instant.parse(request.asOf());
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "sla scan could not parse its governing instant: " + e.getMessage(), null, e);
        }
        SlaScanner.ScanCounts counts = scanner.scanOnce(asOf, request.tenantId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scanned", true);
        result.put("asOf", asOf.toString());
        result.put("warned", counts.warned());
        result.put("breached", counts.breached());
        return result;
    }
}
