package com.novaforge.runtime.api;

import com.novaforge.common.context.TenantContext;
import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.common.error.ProblemErrors;
import com.novaforge.runtime.engine.RecordEngine;
import com.novaforge.runtime.engine.idempotency.IdempotencyRecorder;
import com.novaforge.runtime.engine.idempotency.IdempotencyRecorder.Claim;
import com.novaforge.runtime.engine.query.QueryModel;
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
import tools.jackson.databind.JsonNode;
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

    public RecordController(RecordEngine engine, IdempotencyRecorder idempotency) {
        this.engine = engine;
        this.idempotency = idempotency;
    }

    // --- writes ---

    @PostMapping("/{entity}")
    public ResponseEntity<String> create(@PathVariable String entity,
                                         @RequestBody Map<String, Object> body,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        var ctx = requireContext();
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            // The claim fence: exactly one in-flight request per key executes —
            // duplicates replay the settled outcome or reject 409 while the first
            // still runs (never a double write, never a double sequence draw).
            switch (idempotency.claim(tenant(ctx), actor(ctx), idempotencyKey)) {
                case Claim.Replay replay -> {
                    return ResponseEntity.status(replay.recorded().status())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(replay.recorded().body());
                }
                case Claim.InFlight ignored -> throw new PlatformException(PlatformErrorCode.CONFLICT_VERSION,
                        "an identical request with this Idempotency-Key is already in flight "
                                + "— retry when it settles");
                case Claim.Acquired ignored -> { }
            }
            try {
                String body_ = MAPPER.writeValueAsString(
                        engine.create(tenant(ctx), actor(ctx), entity, body));
                idempotency.record(tenant(ctx), actor(ctx), idempotencyKey, 200, body_);
                return ResponseEntity.ok(body_);
            } catch (RuntimeException e) {
                idempotency.release(tenant(ctx), actor(ctx), idempotencyKey);
                throw e;
            }
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
        Map<String, Object> shaped = engine.get(tenant(ctx), actor(ctx), entity, id, includeDeleted);
        if (fields != null && !fields.isBlank()) {
            shaped = sparse(shaped, fields);
        }
        return shaped;
    }

    /** Complex queries (aggregations) — anything richer than the GET DSL (§5). Routed
     *  on the parsed body's shape, never a raw substring scan (the 2025-08-27 low:
     *  a list whose filter value was the string "aggregates" misrouted into the
     *  aggregate parser). */
    @PostMapping("/{entity}/query")
    public Object query(@PathVariable String entity, @RequestBody String body) {
        var ctx = requireContext();
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (tools.jackson.core.JacksonException e) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "the query body is not valid JSON", ProblemErrors.of(
                            new ProblemErrors.FieldError("query", "must be a JSON object", body)), e);
        }
        if (root != null && (root.hasNonNull("aggregates") || root.hasNonNull("groupBy"))) {
            return engine.aggregate(tenant(ctx), actor(ctx), entity, body);
        }
        return engine.list(tenant(ctx), actor(ctx), entity, body);
    }

    /** Bulk ops with per-item outcomes, max 500 (§5). The claim fence rides the
     *  whole batch exactly as it does a single create. */
    @PostMapping("/batch")
    public ResponseEntity<String> batch(@RequestBody BatchRequest request,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        var ctx = requireContext();
        List<Map<String, Object>> outcomes = List.of();
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            switch (idempotency.claim(tenant(ctx), actor(ctx), idempotencyKey)) {
                case Claim.Replay replay -> {
                    return ResponseEntity.status(replay.recorded().status())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(replay.recorded().body());
                }
                case Claim.InFlight ignored -> throw new PlatformException(PlatformErrorCode.CONFLICT_VERSION,
                        "an identical request with this Idempotency-Key is already in flight "
                                + "— retry when it settles");
                case Claim.Acquired ignored -> { }
            }
            try {
                outcomes = engine.batch(tenant(ctx), actor(ctx), request.items());
                String body_ = MAPPER.writeValueAsString(Map.of("outcomes", outcomes));
                idempotency.record(tenant(ctx), actor(ctx), idempotencyKey, 200, body_);
                return ResponseEntity.ok(body_);
            } catch (RuntimeException e) {
                idempotency.release(tenant(ctx), actor(ctx), idempotencyKey);
                throw e;
            }
        }
        outcomes = engine.batch(tenant(ctx), actor(ctx), request.items());
        return ResponseEntity.ok(MAPPER.writeValueAsString(Map.of("outcomes", outcomes)));
    }

    public record BatchRequest(List<Map<String, Object>> items) {
    }

    /**
     * The page-model {@code runFlow} action's surface (PHASE-2 §4 / PHASE-3 §8): one
     * named flow on demand for a record the caller can read — flow hooks only, the
     * per-app system principal with the initiating actor recorded (the engine leg).
     */
    @PostMapping("/{entity}/{id}/hooks/{hook}")
    public ResponseEntity<String> runHook(@PathVariable String entity, @PathVariable UUID id,
                                          @PathVariable String hook) {
        var ctx = requireContext();
        return ResponseEntity.ok(MAPPER.writeValueAsString(
                engine.runManualHook(tenant(ctx), actor(ctx), entity, id, hook)));
    }

    // --- helpers ---

    /**
     * The GET list door (§5): each of filter/sort/page carries its DSL node as compact
     * JSON — percent-encoded per RFC 3986 on the wire, where the servlet container's
     * parameter decoding is the ONE decode that transport encoding gets (the TS client's
     * URLSearchParams and the harness's URLEncoder each encode exactly once). The bound
     * parameter is therefore the JSON text itself and is parsed verbatim — never decoded
     * a second time. A second {@code URLDecoder.decode} here used to corrupt every value
     * carrying a literal {@code +} (silently rewritten to a space — searching "C++" or a
     * "+02:00" offset answered with a space-mangled term) and rejected every value
     * carrying a literal {@code %} with an uncaught IllegalArgumentException from the
     * decoder itself. A malformed DSL node still rejects as VALIDATION_FAILED at this
     * door rather than as a parse error downstream.
     */
    static String encodeQuery(String filter, String sort, String page) {
        Map<String, JsonNode> query = new LinkedHashMap<>();
        if (filter != null && !filter.isBlank()) {
            query.put("filter", dslNode("filter", filter));
        }
        if (sort != null && !sort.isBlank()) {
            query.put("sort", dslNode("sort", sort));
        }
        if (page != null && !page.isBlank()) {
            query.put("page", dslNode("page", page));
        }
        return MAPPER.writeValueAsString(query);
    }

    private static JsonNode dslNode(String name, String json) {
        try {
            return MAPPER.readTree(json);
        } catch (tools.jackson.core.JacksonException e) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "the " + name + " query parameter is not a valid JSON DSL node",
                    ProblemErrors.of(new ProblemErrors.FieldError(name,
                            "must be a JSON DSL node", json)), e);
        }
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
