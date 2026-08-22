package com.novaforge.runtime.engine.hook;

import com.novaforge.metadata.ScriptDefinition;
import java.util.List;
import java.util.Map;

/**
 * The script-engine port (ADR-003, PHASE-3 §6): the api module binds it to the
 * internal HTTP surface, relaying the <em>calling</em> user's token — scripts run
 * caller-context (§13 Q1), unlike declarative flows' system principal, so a script can
 * never exceed its authorizing user's grants.
 */
public interface ScriptClient {

    /** What a script hook gets back: the return value + bounded capture. */
    record ScriptOutcome(Object value, List<String> logs) {
    }

    /**
     * Executes one script hook for a record.
     *
     * @param appApiName the owning app (telemetry — the script-ratio KPI is per app version)
     * @param appVersion the published app version the artifact rides
     * @param hookName   the hook rule's name
     * @param trigger    the firing trigger (beforeSave/afterSave/…)
     * @param script     the versioned script artifact carried by the published app
     * @param record     the record view ({@code id} included)
     */
    ScriptOutcome execute(String appApiName, int appVersion, String hookName, String trigger,
                          ScriptDefinition script, Map<String, Object> record);
}
