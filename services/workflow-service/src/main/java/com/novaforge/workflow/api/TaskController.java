package com.novaforge.workflow.api;

import com.novaforge.common.context.TenantContext;
import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.workflow.task.TaskService;
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
 * The inbox API (PHASE-4 §5) behind the gateway route {@code /api/v1/workflow/**}
 * (user+): my tasks with server-side paging, approve/reject with comment, claim,
 * delegate, reassign. Access is enforced server-side in the service (§13).
 */
@RestController
@RequestMapping("/api/v1/workflow")
public class TaskController {

    private final TaskService tasks;

    public TaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    /** My tasks — assigned to me or to my roles; open by default (§5), sortable/paged per the Phase 1 conventions. */
    @GetMapping("/tasks")
    public Map<String, Object> myTasks(@RequestParam(required = false) String status,
                                       @RequestParam(required = false) String sort,
                                       @RequestParam(required = false) String dir,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "25") int size) {
        var ctx = requireContext();
        // The Phase 1 paging convention (§5/§12 Q2 — the convention §5 binds this inbox
        // to): page size is 1..200 and over-limit requests reject, never silently clamp.
        if (size < 1 || size > 200) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "page size must be 1..200 (reject, never clamp)");
        }
        if (page < 0) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "page offset must be >= 0");
        }
        var result = tasks.myTasks(UUID.fromString(ctx.tenantId()), UUID.fromString(ctx.actorId()),
                status, sort, dir, page, size);
        return Map.of("rows", result.rows().stream().map(t -> t.toJson()).toList(),
                "total", result.total());
    }

    @GetMapping("/tasks/{id}")
    public Map<String, Object> task(@PathVariable UUID id) {
        var ctx = requireContext();
        return tasks.require(UUID.fromString(ctx.tenantId()), id).toJson();
    }

    @PostMapping("/tasks/{id}/approve")
    public Map<String, Object> approve(@PathVariable UUID id,
                                       @RequestBody(required = false) Comment body) {
        var ctx = requireContext();
        return tasks.resolve(UUID.fromString(ctx.tenantId()), UUID.fromString(ctx.actorId()),
                id, true, body == null ? null : body.comment()).toJson();
    }

    @PostMapping("/tasks/{id}/reject")
    public Map<String, Object> reject(@PathVariable UUID id,
                                      @RequestBody(required = false) Comment body) {
        var ctx = requireContext();
        return tasks.resolve(UUID.fromString(ctx.tenantId()), UUID.fromString(ctx.actorId()),
                id, false, body == null ? null : body.comment()).toJson();
    }

    @PostMapping("/tasks/{id}/claim")
    public Map<String, Object> claim(@PathVariable UUID id) {
        var ctx = requireContext();
        return tasks.claim(UUID.fromString(ctx.tenantId()), UUID.fromString(ctx.actorId()), id)
                .toJson();
    }

    @PostMapping("/tasks/{id}/delegate")
    public Map<String, Object> delegate(@PathVariable UUID id, @RequestBody Target body) {
        var ctx = requireContext();
        return tasks.delegate(UUID.fromString(ctx.tenantId()), UUID.fromString(ctx.actorId()),
                id, UUID.fromString(body.toUser())).toJson();
    }

    /** Admin/builder-only, audited (§5). */
    @PostMapping("/tasks/{id}/reassign")
    public Map<String, Object> reassign(@PathVariable UUID id, @RequestBody Target body) {
        var ctx = requireContext();
        return tasks.reassign(UUID.fromString(ctx.tenantId()), UUID.fromString(ctx.actorId()),
                id, body.toUser() == null ? null : UUID.fromString(body.toUser()),
                body.toRole()).toJson();
    }

    public record Comment(String comment) {
    }

    public record Target(String toUser, String toRole) {
    }

    private static TenantContext.Context requireContext() {
        return TenantContext.current().orElseThrow(() ->
                new com.novaforge.common.error.PlatformException(
                        com.novaforge.common.error.PlatformErrorCode.TENANT_MISSING,
                        "no tenant context bound"));
    }
}
