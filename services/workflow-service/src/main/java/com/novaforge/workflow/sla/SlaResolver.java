package com.novaforge.workflow.sla;

import com.novaforge.expression.Expression;
import com.novaforge.metadata.SlaDefinition;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SLA resolution (PHASE-4 §6): the app's {@link SlaDefinition}s match a task-creation
 * scope — a matching definition takes precedence over a {@code requestApproval}
 * step's own {@code timeout}/{@code escalateTo} (the governed overlay beats the
 * inline default); with neither present the task carries no {@code dueAt} and no
 * timer fires. {@code warnAt} defaults to 0.8 on both paths; {@code warnAt: null} on
 * a matching definition disables the warn timer outright.
 */
@org.springframework.stereotype.Component
public class SlaResolver {

    /** The resolved timer set for a task: when to warn, when it breaches. */
    public record Timers(Instant warnAt, Instant dueAt, SlaDefinition matched) {
    }

    private final com.novaforge.workflow.sla.PublishedSlaSource source;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(List<SlaDefinition> slas, long fetchedAt) {
    }

    public SlaResolver(PublishedSlaSource source) {
        this.source = source;
    }

    /**
     * Resolves the timers for a task about to be created. {@code stepTimeout} is the
     * requestApproval step's own ISO-8601 duration (may be null); {@code transition}
     * is the triggering write's state-machine edge ({@code PRIOR->NEW}, null when no
     * state changed) — the match binding of PHASE-4 §6's example / PHASE-2 Annex A.
     */
    public Timers resolve(UUID tenantId, String appApiName, String entityKey,
                          String taskType, String stepTimeout, String transition,
                          Instant createdAt) {
        SlaDefinition matched = match(tenantId, appApiName, entityKey, taskType, transition);
        if (matched != null) {
            Duration target = Duration.parse(matched.target());
            // warnAt is presence-normalized at parse: absent authored the 0.8 default,
            // an explicit null disables the warn timer outright (§6)
            return new Timers(
                    matched.warnAt() == null ? null : createdAt.plusMillis(
                            (long) (target.toMillis() * matched.warnAt())),
                    createdAt.plus(target), matched);
        }
        if (stepTimeout == null || stepTimeout.isBlank()) {
            return new Timers(null, null, null);   // no timer, no escalation (§6)
        }
        Duration target = Duration.parse(stepTimeout);
        return new Timers(createdAt.plusMillis(
                        (long) (target.toMillis() * SlaDefinition.DEFAULT_WARN_AT)),
                createdAt.plus(target), null);
    }

    private SlaDefinition match(UUID tenantId, String appApiName, String entityKey,
                                String taskType, String transition) {
        for (SlaDefinition sla : slasOf(tenantId, appApiName)) {
            if (sla.scope() == null || sla.target() == null) {
                continue;
            }
            if (sla.scope().taskType() != null && !sla.scope().taskType().equals(taskType)) {
                continue;
            }
            if (sla.scope().match() != null) {
                Object outcome = Expression.parse(sla.scope().match())
                        .evaluate(Expression.Bindings.of(
                                SlaDefinition.bindings(entityKey, taskType, transition)),
                                java.time.Clock.systemUTC());
                if (!(outcome instanceof Boolean matches) || !matches) {
                    continue;
                }
            }
            return sla;
        }
        return null;
    }

    private List<SlaDefinition> slasOf(UUID tenantId, String appApiName) {
        // tenant-scoped key: same-named apps across tenants must never serve each
        // other's SLAs out of a shared 30 s entry
        String key = tenantId + ":" + appApiName;
        CacheEntry entry = cache.get(key);
        if (entry != null && System.currentTimeMillis() - entry.fetchedAt() < 30_000) {
            return entry.slas();
        }
        List<SlaDefinition> slas = source.slasOf(tenantId, appApiName);
        cache.put(key, new CacheEntry(slas, System.currentTimeMillis()));
        return slas;
    }
}
