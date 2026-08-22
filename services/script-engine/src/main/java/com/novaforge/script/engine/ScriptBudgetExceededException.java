package com.novaforge.script.engine;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;

/**
 * A script died at one of the ADR-003 caps (CPU, wall-clock, heap, statements) — the
 * author's fault, so it renders VALIDATION_FAILED like any other script error, but the
 * distinct type lets telemetry count {@code capped} separately from {@code error}.
 */
public final class ScriptBudgetExceededException extends PlatformException {

    public ScriptBudgetExceededException(String message) {
        super(PlatformErrorCode.VALIDATION_FAILED, message);
    }
}
