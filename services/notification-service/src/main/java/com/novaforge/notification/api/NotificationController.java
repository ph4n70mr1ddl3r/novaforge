package com.novaforge.notification.api;

import com.novaforge.common.context.TenantContext;
import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The inbox and preference surface (PHASE-4 §8) behind
 * {@code /api/v1/notifications/**} (user+, own data only): my notifications paged,
 * mark-read, and per-category channel toggles.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    /**
     * The page ceiling (the Phase 1 convention's OFFSET twin): page size is 1..200,
     * so a page beyond 1,000,000 asks for an OFFSET past 200 million rows — no
     * inbox has one. The bound exists because {@code page * size} is caller
     * arithmetic: 2,000,000,000 × 200 overflows int to a NEGATIVE OFFSET, which
     * Postgres rejects as a 500. Over-limit requests reject, never silently clamp.
     */
    private static final int MAX_PAGE = 1_000_000;

    private final JdbcTemplate jdbc;

    public NotificationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, Object> inbox(@RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "25") int size) {
        var ctx = requireContext();
        // The Phase 1 paging convention (PHASE-4 §5 binds the inboxes to it): page size
        // is 1..200 and over-limit requests reject, never silently clamp.
        if (size < 1 || size > 200 || page < 0 || page > MAX_PAGE) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    size < 1 || size > 200 ? "page size must be 1..200 (reject, never clamp)"
                            : page < 0 ? "page offset must be >= 0"
                                    : "page must be 0.." + MAX_PAGE
                                            + " (reject, never clamp)");
        }
        UUID tenant = UUID.fromString(ctx.tenantId());
        UUID user = UUID.fromString(ctx.actorId());
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM nf_notifications WHERE tenant_id = ? AND user_id = ?",
                Long.class, tenant, user);
        // long math: the bound keeps page*size well inside int, but the arithmetic
        // stays widening so an OFFSET can never wrap negative again
        long offset = (long) page * size;
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, category, title, body, read_at, created_at
                  FROM nf_notifications WHERE tenant_id = ? AND user_id = ?
                 ORDER BY created_at DESC LIMIT ? OFFSET ?""",
                tenant, user, size, offset);
        return Map.of("rows", rows, "total", total == null ? 0 : total);
    }

    @PostMapping("/{id}/read")
    public Map<String, Object> markRead(@org.springframework.web.bind.annotation.PathVariable UUID id) {
        var ctx = requireContext();
        jdbc.update("""
                UPDATE nf_notifications SET read_at = now()
                 WHERE tenant_id = ? AND user_id = ? AND id = ?""",
                UUID.fromString(ctx.tenantId()), UUID.fromString(ctx.actorId()), id);
        return Map.of("status", "read");
    }

    public record Preference(String category, Boolean inbox, Boolean email) {
    }

    /** Upserts the caller's channel toggles for a category (§8's coarse v1 shape). */
    @PostMapping("/preferences")
    public Map<String, Object> setPreference(@RequestBody Preference preference) {
        var ctx = requireContext();
        jdbc.update("""
                INSERT INTO nf_preferences (tenant_id, user_id, category, inbox, email)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, user_id, category)
                DO UPDATE SET inbox = EXCLUDED.inbox, email = EXCLUDED.email""",
                UUID.fromString(ctx.tenantId()), UUID.fromString(ctx.actorId()),
                preference.category(),
                preference.inbox() == null || preference.inbox(),
                preference.email() == null || preference.email());
        return Map.of("status", "saved");
    }

    @GetMapping("/preferences")
    public List<Map<String, Object>> preferences() {
        var ctx = requireContext();
        return jdbc.queryForList(
                "SELECT category, inbox, email FROM nf_preferences "
                        + "WHERE tenant_id = ? AND user_id = ?",
                UUID.fromString(ctx.tenantId()), UUID.fromString(ctx.actorId()));
    }

    private static TenantContext.Context requireContext() {
        return TenantContext.current().orElseThrow(() ->
                new PlatformException(PlatformErrorCode.TENANT_MISSING,
                        "no tenant context bound"));
    }
}
