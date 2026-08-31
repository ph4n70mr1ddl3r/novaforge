package com.novaforge.notification.notify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recipient resolution (PHASE-4 §8): the task's assignee, or the holders of its
 * role through the runtime's admin read surface (the platform DB is the runtime's
 * to own). Synthetic actors (ADR-010 #3) are recognized by their provisioned
 * username shape — {@code scratch-*}, {@code actor-*}, {@code *-admin-*} — and
 * report no channels.
 */
@org.springframework.stereotype.Component
public class RecipientResolver {

    private static final Logger LOG = LoggerFactory.getLogger(RecipientResolver.class);

    private final RuntimeAdminPort runtime;

    public RecipientResolver(RuntimeAdminPort runtime) {
        this.runtime = runtime;
    }

    /** The users a task event fans out to. */
    public List<UUID> of(UUID tenantId, Map<String, Object> task) {
        List<UUID> users = new ArrayList<>();
        if (task.get("assignee") instanceof String assignee && !assignee.isBlank()) {
            users.add(UUID.fromString(assignee));
        } else if (task.get("role") instanceof String role && !role.isBlank()) {
            users.addAll(runtime.usersOfRole(tenantId, role));
        }
        return users;
    }

    /** Holders of one platform role ({@code app.role}) — the internal send fan-out (§7). */
    public List<UUID> holdersOf(UUID tenantId, String role) {
        return runtime.usersOfRole(tenantId, role);
    }

    /**
     * Whether {@code user} belongs to {@code tenantId} — the internal send's explicit
     * {@code recipients.users} gate (2026-08-31). A caller-named id is untrusted input:
     * the admin user lookup is GLOBAL (tenant-unscoped), so without this check a
     * recipient list naming another tenant's user id delivered the sending tenant's
     * data — inbox row plus emailed export — to a foreign user. Membership is the
     * platform DB's own tenant binding (the user's role rows in the tenant); the
     * lookup failing CLOSED (runtime unreachable, no roles) drops the recipient —
     * a lost send to one id beats a cross-tenant leak. Synthetic actors hold no
     * role rows, but {@link #hasChannels} already skips them by username shape.
     */
    public boolean belongsTo(UUID tenantId, UUID user) {
        try {
            boolean member = !runtime.rolesOfUser(tenantId, user).isEmpty();
            if (!member) {
                LOG.warn("internal send recipient {} dropped — no membership in tenant {} "
                        + "(cross-tenant recipient ids never deliver)", user, tenantId);
            }
            return member;
        } catch (RuntimeException e) {
            LOG.warn("internal send recipient {} dropped — membership lookup failed, "
                    + "failing closed: {}", user, e.getMessage());
            return false;
        }
    }

    /** Synthetic actors have no channels (ADR-010 #3): no inbox, no email. */
    public boolean hasChannels(UUID user) {
        String username = runtime.usernameOf(user);
        return username != null && !username.startsWith("scratch-")
                && !username.startsWith("actor-") && !username.contains("-admin-");
    }

    /** The SMTP address — the provisioned email, or none for synthetic actors. */
    public String addressOf(UUID user) {
        String username = runtime.usernameOf(user);
        return username == null || username.startsWith("scratch-")
                || username.startsWith("actor-") || username.contains("-admin-")
                ? username + "@synthetic.invalid"
                : username + "@localhost";
    }

    /** The runtime's admin read surface (service token), behind a port for tests. */
    public interface RuntimeAdminPort {

        List<UUID> usersOfRole(UUID tenantId, String role);

        String usernameOf(UUID user);

        /** The user's roles in a tenant — empty when the user is not a member of it. */
        List<String> rolesOfUser(UUID tenantId, UUID user);
    }
}
