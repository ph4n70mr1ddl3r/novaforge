package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A script artifact (ADR-003, PHASE-3 §6): the ADR-008 escape hatch. Scripts are
 * versioned on the same review/promotion path as every other definition — the source
 * rides the app definition's JSON, so each published version snapshots it (the Script
 * Engine keeps no store of its own; executions are stateless).
 *
 * <p>v0 pins the language set to GraalVM JS and bounds the source size; these are the
 * same constants the Metadata Service's publish check and the Script Engine's
 * execution surface enforce.</p>
 *
 * <p>Authoring contract: the source is evaluated as an ECMAScript program and its
 * <em>completion value</em> is the result — the beforeSave write-back form is an
 * expression, e.g. {@code ({ label: 'ENRICHED-' + $record.label })}. A top-level
 * {@code return} is not valid in a program and is rejected at execution.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScriptDefinition(String language, String source) {

    /** v0 ships GraalVM JS only (ADR-003 #1); additions are versioned platform features. */
    public static final java.util.Set<String> LANGUAGES = java.util.Set.of("js");

    /** 64 KiB of source — hooks are reviewed artifacts, not programs. */
    public static final int MAX_SOURCE_CHARS = 64 * 1024;
}
