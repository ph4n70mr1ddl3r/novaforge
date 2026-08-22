package com.novaforge.workflow.process;

import java.util.Map;
import java.util.UUID;

/**
 * The record read event-start evaluation needs (PHASE-4 §9): spine events carry
 * the envelope only, so subscription filters evaluate against the record's
 * current state — fetched as the per-app system principal through the Data
 * Runtime's internal surface. A read, never a mutation (ADR-004 #2).
 */
public interface RecordFieldsSource {

    /**
     * The record's field map, or null when the record is gone — a created/updated
     * event for a since-deleted record starts nothing; evaluation skips.
     */
    Map<String, Object> fields(UUID tenantId, String app, String entity, UUID recordId);

    /** Hermetic-test binding: an empty record — blank-filter subscriptions match. */
    class None implements RecordFieldsSource {

        @Override
        public Map<String, Object> fields(UUID tenantId, String app, String entity,
                                          UUID recordId) {
            return Map.of();
        }
    }
}
