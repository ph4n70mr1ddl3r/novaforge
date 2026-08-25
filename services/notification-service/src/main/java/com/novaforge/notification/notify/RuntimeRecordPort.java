package com.novaforge.notification.notify;

import java.util.Map;
import java.util.UUID;

/**
 * The Data Runtime's internal record read, as the notification fan-out sees it
 * (PHASE-4 §8): template tokens may reference {@code ${record.field}}, and the
 * record-fetching surface the §8 pin waited on landed with the event-start read
 * (PHASE-4 §9) — this port is its client. A read, never a mutation; the binding
 * exists so built-in and future app-authored templates can render record context.
 */
public interface RuntimeRecordPort {

    /**
     * The record's raw stored fields, or an empty map when the record cannot be
     * fetched (gone, or the runtime is unreachable) — token resolution degrades to
     * empty strings rather than blocking delivery.
     *
     * @param tenantId  the owning tenant
     * @param entityKey the app-qualified entity key ({@code App.Entity})
     * @param recordId  the record
     */
    Map<String, Object> recordOf(UUID tenantId, String entityKey, UUID recordId);
}
