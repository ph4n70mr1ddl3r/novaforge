package com.novaforge.notification.notify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Recipient resolution (PHASE-4 §8): the task's assignee, or the holders of its
 * role through the runtime's admin read surface (the platform DB is the runtime's
 * to own). Synthetic actors (ADR-010 #3) are recognized by their provisioned
 * username shape — {@code scratch-*}, {@code actor-*}, {@code *-admin-*} — and
 * report no channels.
 */
@org.springframework.stereotype.Component
public class RecipientResolver {

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
    }
}
