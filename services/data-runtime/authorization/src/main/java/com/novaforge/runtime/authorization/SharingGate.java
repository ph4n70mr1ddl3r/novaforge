package com.novaforge.runtime.authorization;

import com.novaforge.expression.Expression;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.EntityDefinition;
import com.novaforge.metadata.PermissionSet;
import com.novaforge.metadata.SharingRuleDefinition;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Record-level sharing (PHASE-4 §10, ARCHITECTURE.md §5 item 2): the PermissionSet's
 * sharing rules evaluate into the visibility that governs reads, writes, and deletes
 * alike. With no rules for an entity, Phase 2's default holds — full visibility
 * under the object CRUD matrix, no silent tightening. Platform admins and builders
 * are unrestricted.
 *
 * <p>Rule semantics (§10): {@code owner} — the record's creator (or explicit owner
 * field) plus the named roles see everything; {@code roleHierarchy} — a user sees
 * records owned by holders of less senior roles (numeric {@code level}, lower is
 * more senior); {@code criteria} — records matching the compiled expression are
 * visible to their owner and the named roles.</p>
 */
@Component
public class SharingGate {

    private final PlatformStore platform;

    public SharingGate(PlatformStore platform) {
        this.platform = platform;
    }

    /**
     * The actor's restriction on an entity's records — null means unrestricted.
     * {@code visibleOwners} lowers to list row filters ({@code created_by IN …});
     * {@code recordVisible} decides single records exactly (owner set + criteria).
     */
    public record Restriction(Set<UUID> visibleOwners,
                              java.util.function.Predicate<Map<String, Object>> recordVisible) {
    }

    public Restriction forActor(UUID tenantId, UUID actor, EntityDefinition entity,
                                AppDefinition app) {
        var rules = app.permissionSet().sharingRulesFor(entity.apiName());
        if (rules.isEmpty()) {
            return null;   // no rules — the Phase 2 default (§10)
        }
        var held = platform.roles(tenantId, actor);
        if (held.contains("admin") || held.contains("builder")) {
            return null;
        }
        // assignments carry the app-scoped form ("Desk.manager"); rules and role
        // definitions use the short name
        String prefix = app.apiName() + ".";
        java.util.List<String> roles = held.stream()
                .map(role -> role.startsWith(prefix) ? role.substring(prefix.length()) : role)
                .toList();
        Set<UUID> owners = new HashSet<>();
        owners.add(actor);   // everyone sees their own
        boolean seesAll = false;
        Set<String> criteriaSources = new HashSet<>();
        Integer actorLevel = seniority(app.permissionSet(), roles);
        for (SharingRuleDefinition rule : rules) {
            boolean named = rule.roles().stream().anyMatch(roles::contains);
            switch (rule.type() == null ? "" : rule.type()) {
                case SharingRuleDefinition.OWNER -> {
                    if (named) {
                        seesAll = true;   // the named roles see the whole entity
                    }
                }
                case SharingRuleDefinition.ROLE_HIERARCHY -> {
                    // records owned by holders of strictly less senior roles — an
                    // unleveled role carries no seniority and never widens anyone's
                    // view; neither does a missing level on the actor's own roles
                    // (assignments carry the app-scoped form)
                    if (actorLevel != null) {
                        for (PermissionSet.RoleDefinition role : app.permissionSet().roles()) {
                            if (role.level() != null && role.level() > actorLevel) {
                                owners.addAll(platform.usersOfRole(tenantId,
                                        app.apiName() + "." + role.name()));
                            }
                        }
                    }
                }
                case SharingRuleDefinition.CRITERIA -> {
                    if (named) {
                        criteriaSources.add(rule.criteria());
                    }
                }
                default -> { /* unknown type — the save validator rejects it */ }
            }
        }
        if (seesAll) {
            return null;
        }
        return new Restriction(Set.copyOf(owners), record ->
                owners.contains(recordOwner(record))
                        || criteriaSources.stream().anyMatch(source -> {
                    try {
                        return Expression.parse(source)
                                .evaluate(Expression.Bindings.of(record),
                                        java.time.Clock.systemUTC())
                                instanceof Boolean allowed && allowed;
                    } catch (RuntimeException e) {
                        return false;
                    }
                }));
    }

    /** The record's owner: the explicit owner field when authored, else createdBy. */
    private static UUID recordOwner(Map<String, Object> row) {
        Object owner = row.get("__owner__");   // the engine injects it for gate checks
        if (owner instanceof UUID id) {
            return id;
        }
        Object createdBy = row.get("createdBy");
        return createdBy == null ? null : UUID.fromString(String.valueOf(createdBy));
    }

    private static Integer seniority(PermissionSet permissions, java.util.List<String> roles) {
        Integer best = null;
        for (String role : roles) {
            var definition = permissions.role(role);
            if (definition.isPresent() && definition.get().level() != null
                    && (best == null || definition.get().level() < best)) {
                best = definition.get().level();
            }
        }
        return best;
    }
}
