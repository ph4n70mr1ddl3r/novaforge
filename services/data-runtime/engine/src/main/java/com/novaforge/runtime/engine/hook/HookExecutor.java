package com.novaforge.runtime.engine.hook;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.EntityDefinition;
import com.novaforge.metadata.FieldDefinition;
import com.novaforge.metadata.FlowStep;
import com.novaforge.metadata.HookRule;
import com.novaforge.metadata.RelationshipDefinition;
import com.novaforge.runtime.engine.metadata.EntityResolver.EntityHandle;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executes compiled flow-IR hook graphs on the write path (PHASE-3 §2, ADR-008).
 * Executable primitives: setField (expression), createRecord/updateRecord (${…}
 * record templates), publishEvent (payload template), branch (guard), iterate
 * (relationship body). Grammar-fixed primitives (requestApproval/transitionState/
 * callConnector) fail loudly — they activate with Phases 4/6.
 *
 * <p>Failure policy (ARCHITECTURE.md §2.5): beforeSave/beforeDelete failure aborts
 * the transaction (the executor throws); afterSave/afterDelete failure is recorded
 * for retry — never lost, never blocks the write.</p>
 */
@Component
public class HookExecutor {

    /** Defensive cap — compiled graphs are DAGs; this guards runaway recursion. */
    public static final int MAX_STEPS = 256;

    /** Nested engine calls (createRecord/updateRecord) get their own budget. */
    public static final int MAX_DEPTH = 4;

    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{([^}]+)}");

    private static final Logger LOG = LoggerFactory.getLogger(HookExecutor.class);

    /** The runtime's integration point: executes one hook trigger for a record.
     *  The sink arrives per call — the runtime supplies itself, avoiding a bean cycle. */
    public interface HookSink {

        /** Create/update a record as the flow's system principal (nested engine call). */
        Map<String, Object> writeRecord(String entityApiName, Map<String, Object> body,
                                        String recordId, UUID systemPrincipal, int depth);

        /** Publish an app event to the spine (outbox). */
        void publishAppEvent(String name, Map<String, Object> payload, UUID tenantId,
                             String entityKey, UUID recordId, UUID systemPrincipal);

        /** The children of a record for iterate (query path, live rows). */
        List<Map<String, Object>> children(UUID tenantId, String appApiName,
                                           String parentEntityApiName, String relationship,
                                           UUID parentRecordId);
    }

    /** Result of a trigger run: aborted (before-hooks) or recorded retries (after). */
    public record Outcome(boolean aborted, List<String> retryQueue) {

        static final Outcome CLEAN = new Outcome(false, List.of());
    }

    public Outcome runTrigger(AppDefinition app, EntityHandle handle, UUID tenantId,
                              UUID recordId, Map<String, Object> data, String trigger,
                              UUID systemPrincipal, HookSink sink) {
        List<String> retries = new java.util.ArrayList<>();
        for (HookRule hook : handle.entity().hooks()) {
            if (!trigger.equals(hook.trigger())) {
                continue;
            }
            try {
                executeFlow(app, handle, tenantId, recordId, data, hook.flow(),
                        systemPrincipal, 0, sink);
            } catch (PlatformException e) {
                if (trigger.startsWith("before")) {
                    throw e;   // abort the transaction (§2.5)
                }
                LOG.warn("after-hook {} failed on {}: {} (recorded for retry)",
                        hook.name(), handle.entityKey(), e.getMessage());
                retries.add(hook.name());
            }
        }
        return retries.isEmpty() ? Outcome.CLEAN : new Outcome(false, retries);
    }

    private void executeFlow(AppDefinition app, EntityHandle handle, UUID tenantId,
                             UUID recordId, Map<String, Object> data, FlowStep entry,
                             UUID systemPrincipal, int depth, HookSink sink) {
        Context context = new Context(app, handle, tenantId, recordId, data,
                systemPrincipal, depth, sink);
        context.index(entry);
        FlowStep step = entry;
        int executed = 0;
        while (step != null) {
            if (executed++ > MAX_STEPS) {
                throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "hook exceeded " + MAX_STEPS + " steps — compiled flows are bounded DAGs");
            }
            step = executeStep(step, context);
        }
    }

    private FlowStep executeStep(FlowStep step, Context context) {
        if (step.op() == null) {
            return null;
        }
        switch (step.op()) {
            case "setField" -> {
                String field = step.param("field");
                Object value = context.evaluate(step.param("expression"));
                context.data.put(field, value);
                return byName(context, step.next());
            }
            case "branch" -> {
                boolean guard = Boolean.TRUE.equals(context.evaluate(step.param("guard")));
                return byName(context, guard ? step.onTrue() : step.onFalse());
            }
            case "createRecord" -> {
                context.sink.writeRecord(step.param("entity"),
                        resolveTemplate(step.params().get("template"), context),
                        null, context.systemPrincipal, context.depth + 1);
                return byName(context, step.next());
            }
            case "updateRecord" -> {
                context.sink.writeRecord(step.param("entity"),
                        resolveTemplate(step.params().get("template"), context),
                        resolveTemplateText(step.param("recordId"), context),
                        context.systemPrincipal, context.depth + 1);
                return byName(context, step.next());
            }
            case "publishEvent" -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                Object template = step.params().get("payload");
                if (template instanceof Map<?, ?> map) {
                    map.forEach((key, value) -> payload.put(String.valueOf(key),
                            value instanceof String text ? resolveTemplateText(text, context) : value));
                }
                context.sink.publishAppEvent(step.param("name"), payload, context.tenantId,
                        context.handle.entityKey(), context.recordId, context.systemPrincipal);
                return byName(context, step.next());
            }
            case "iterate" -> {
                for (Map<String, Object> child : context.sink.children(context.tenantId,
                        context.handle.appApiName(), context.handle.entity().apiName(),
                        step.param("path"), context.recordId)) {
                    FlowStep body = step.body();
                    Context childContext = context.forChild(child);
                    int executed = 0;
                    while (body != null && executed++ < MAX_STEPS) {
                        body = executeStep(body, childContext);
                    }
                }
                return byName(context, step.next());
            }
            case "requestApproval", "transitionState" -> throw new PlatformException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    step.op() + " is grammar-fixed and activates with Phase 4 (PHASE-3 §2)");
            case "callConnector" -> throw new PlatformException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "callConnector is grammar-fixed and activates with Phase 6 (PHASE-3 §2)");
            default -> throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "unknown op at runtime: " + step.op());
        }
    }

    // --- template resolution (${…} — ADR-008 record templates, host-resolved) ---

    private Map<String, Object> resolveTemplate(Object template, Context context) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        if (template instanceof Map<?, ?> map) {
            map.forEach((key, value) -> resolved.put(String.valueOf(key),
                    value instanceof String text ? resolveTemplateText(text, context) : value));
        }
        return resolved;
    }

    private String resolveTemplateText(String text, Context context) {
        if (text == null) {
            return null;
        }
        Matcher matcher = TEMPLATE.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement(String.valueOf(context.resolveTemplateValue(matcher.group(1)))));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private FlowStep byName(Context context, String id) {
        return id == null ? null : context.step(id);
    }

    /** Execution context: the record's data + the compiled graph's steps. */
    private final class Context {

        final AppDefinition app;
        final EntityHandle handle;
        final UUID tenantId;
        final UUID recordId;
        final Map<String, Object> data;
        final UUID systemPrincipal;
        final HookSink sink;
        final int depth;
        private final Map<String, Object> overlay = new HashMap<>();
        private final Map<String, FlowStep> steps = new HashMap<>();

        Context(AppDefinition app, EntityHandle handle, UUID tenantId, UUID recordId,
                Map<String, Object> data, UUID systemPrincipal, int depth, HookSink sink) {
            this.app = app;
            this.handle = handle;
            this.tenantId = tenantId;
            this.recordId = recordId;
            this.data = data;
            this.systemPrincipal = systemPrincipal;
            this.depth = depth;
            this.sink = sink;
        }

        FlowStep step(String id) {
            FlowStep found = steps.get(id);
            if (found == null) {
                throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                        "hook step not found: " + id + " — the publish compiler rejected such graphs");
            }
            return found;
        }

        void index(FlowStep entry) {
            indexDeep(entry);
        }

        Object evaluate(String expression) {
            if (expression == null) {
                return null;
            }
            Map<String, Object> bindings = new LinkedHashMap<>(data);
            bindings.putAll(overlay);
            bindings.put("id", recordId == null ? null : recordId.toString());
            return com.novaforge.expression.Expression.parse(expression)
                    .evaluate(com.novaforge.expression.Expression.Bindings.of(bindings),
                            java.time.Clock.systemUTC());
        }

        Object resolveTemplateValue(String path) {
            Object direct = data.get(path);
            if (direct != null) {
                return direct;
            }
            Object overlayValue = overlay.get(path);
            if (overlayValue != null) {
                return overlayValue;
            }
            return path.equals("id") && recordId != null ? recordId.toString() : null;
        }

        Context forChild(Map<String, Object> child) {
            Context childContext = new Context(app, handle, tenantId, recordId, data,
                    systemPrincipal, depth, sink);
            childContext.overlay.putAll(child);
            return childContext;
        }

        private void indexDeep(FlowStep step) {
            if (step == null) {
                return;
            }
            steps.put(step.id(), step);
            indexDeep(step.body());
        }
    }

    /** Numeric coercion helper for templates that write numeric fields. */
    static BigDecimal numeric(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text) {
            return new BigDecimal(text);
        }
        throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                "expected a numeric template value");
    }
}
