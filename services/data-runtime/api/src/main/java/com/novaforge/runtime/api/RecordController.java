package com.novaforge.runtime.api;

import com.novaforge.common.context.TenantContext;
import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.runtime.engine.RecordEngine;
import com.novaforge.runtime.engine.idempotency.IdempotencyRecorder;
import com.novaforge.runtime.storage.query.QueryModel;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The generic record/query surface (ARCHITECTURE.md §2.4, PHASE-1 §5): per-entity CRUD
 * with inline children, the structured query DSL (GET list carries each DSL node as
 * compact percent-encoded JSON — one canonical encoding), aggregates and batch.
 * Idempotency-Key honored on create and batch.
 */
@RestController
@RequestMapping("/api/v1/runtime")
public class RecordController {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RecordEngine engine;
    private final IdempotencyRecorder idempotency;
    private final com.novaforge.runtime.authorization.RoleMatrix roleMatrix;

    public RecordController(RecordEngine engine, IdempotencyRecorder idempotency,
                            com.novaforge.runtime.authorization.RoleMatrix roleMatrix) {
        this.engine = engine;
        this.idempotency = idempotency;
        this.roleMatrix = roleMatrix;
    }

    // --- writes ---

    @PostMapping("/{entity}")
    public ResponseEntity<String> create(@PathVariable String entity,
                                         @RequestBody Map<String, Object> body,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        var ctx = requireContext();
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var replay = idempotency.replay(tenant(ctx), actor(ctx), idempotencyKey);
            if (replay.isPresent()) {
                return ResponseEntity.status(replay.get().status())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(replay.get().body());
            }
            String body_ = MAPPER.writeValueAsString(
                    engine.create(tenant(ctx), actor(ctx), entity, body));
            idempotency.record(tenant(ctx), actor(ctx), idempotencyKey, 200, body_);
            return ResponseEntity.ok(body_);
        }
        return ResponseEntity.ok(MAPPER.writeValueAsString(
                engine.create(tenant(ctx), actor(ctx), entity, body)));
    }

    @PatchMapping("/{entity}/{id}")
    public String update(@PathVariable String entity, @PathVariable UUID id,
                         @RequestBody Map<String, Object> body) {
        var ctx = requireContext();
        int expectedVersion = requiredVersion(body);
        return MAPPER.writeValueAsString(
                engine.update(tenant(ctx), actor(ctx), entity, id, expectedVersion, body));
    }

    @DeleteMapping("/{entity}/{id}")
    public ResponseEntity<Void> delete(@PathVariable String entity, @PathVariable UUID id,
                                       @RequestParam("version") int version) {
        var ctx = requireContext();
        engine.delete(tenant(ctx), actor(ctx), entity, id, version);
        return ResponseEntity.noContent().build();
    }

    // --- reads ---

    @GetMapping("/{entity}")
    public QueryModel.QueryResult list(@PathVariable String entity,
                                       @RequestParam(value = "filter", required = false) String filter,
                                       @RequestParam(value = "sort", required = false) String sort,
                                       @RequestParam(value = "page", required = false) String page) {
        var ctx = requireContext();
        String queryJson = encodeQuery(filter, sort, page);
        return engine.list(tenant(ctx), actor(ctx), entity, queryJson);
    }

    @GetMapping("/{entity}/{id}")
    public Map<String, Object> get(@PathVariable String entity, @PathVariable UUID id,
                                   @RequestParam(value = "fields", required = false) String fields,
                                   @RequestParam(value = "includeDeleted", required = false, defaultValue = "false") boolean includeDeleted) {
        var ctx = requireContext();
        if (includeDeleted) {
            roleMatrix.requireAdmin(tenant(ctx), actor(ctx));
        }
        Map<String, Object> shaped = engine.get(tenant(ctx), actor(ctx), entity, id, includeDeleted);
        if (fields != null && !fields.isBlank()) {
            shaped = sparse(shaped, fields);
        }
        return shaped;
    }

    /** Complex queries (aggregations) — anything richer than the GET DSL (§5). */
    @PostMapping("/{entity}/query")
    public Object query(@PathVariable String entity, @RequestBody String body) {
        var ctx = requireContext();
        if (body.contains("\"aggregates\"") || body.contains("\"groupBy\"")) {
            return engine.aggregate(tenant(ctx), actor(ctx), entity, body);
        }
        return engine.list(tenant(ctx), actor(ctx), entity, body);
    }

    /** Bulk ops with per-item outcomes, max 500 (§5). */
    @PostMapping("/batch")
    public ResponseEntity<String> batch(@RequestBody BatchRequest request,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        var ctx = requireContext();
        List<Map<String, Object>> outcomes = List.of();
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var replay = idempotency.replay(tenant(ctx), actor(ctx), idempotencyKey);
            if (replay.isPresent()) {
                return ResponseEntity.status(replay.get().status())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(replay.get().body());
            }
            outcomes = engine.batch(tenant(ctx), actor(ctx), request.items());
            String body_ = MAPPER.writeValueAsString(Map.of("outcomes", outcomes));
            idempotency.record(tenant(ctx), actor(ctx), idempotencyKey, 200, body_);
            return ResponseEntity.ok(body_);
        }
        outcomes = engine.batch(tenant(ctx), actor(ctx), request.items());
        return ResponseEntity.ok(MAPPER.writeValueAsString(Map.of("outcomes", outcomes)));
    }

    public record BatchRequest(List<Map<String, Object>> items) {
    }

    // --- helpers ---

    /**
     * The GET list canonical encoding (§5): each of filter/sort/page holds its DSL node
     * as compact JSON, percent-encoded per RFC 3986 — no bespoke flattening.
     */
    static String encodeQuery(String filter, String sort, String page) {
        StringBuilder json = new StringBuilder("{");
        if (filter != null && !filter.isBlank()) {
            json.append("\"filter\":").append(decode(filter)).append(',');
        }
        if (sort != null && !sort.isBlank()) {
            json.append("\"sort\":").append(decode(sort)).append(',');
        }
        if (page != null && !page.isBlank()) {
            json.append("\"page\":").append(decode(page)).append(',');
        }
        if (json.charAt(json.length() - 1) == ',') {
            json.setLength(json.length() - 1);
        }
        return json.append('}').toString();
    }

    private static String decode(String encoded) {
        return java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> sparse(Map<String, Object> shaped, String fields) {
        List<String> keep = List.of(fields.split(","));
        Map<String, Object> sparse = new LinkedHashMap<>();
        sparse.put("id", shaped.get("id"));
        for (String field : keep) {
            String trimmed = field.trim();
            if (shaped.containsKey(trimmed)) {
                sparse.put(trimmed, shaped.get(trimmed));
            }
        }
        return sparse;
    }

    private static int requiredVersion(Map<String, Object> body) {
        Object version = body.get("version");
        if (!(version instanceof Number number)) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "PATCH requires the expected record version for optimistic locking");
        }
        return number.intValue();
    }

    private static TenantContext.Context requireContext() {
        return TenantContext.current().orElseThrow(() ->
                new PlatformException(PlatformErrorCode.TENANT_MISSING,
                        "no tenant context bound (missing tenant_id claim?)"));
    }

    private static UUID tenant(TenantContext.Context ctx) {
        return UUID.fromString(ctx.tenantId());
    }

    private static UUID actor(TenantContext.Context ctx) {
        return UUID.fromString(ctx.actorId());
    }
}
