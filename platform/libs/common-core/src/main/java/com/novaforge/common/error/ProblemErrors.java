package com.novaforge.common.error;

import java.util.List;

/**
 * RFC 7807 extension payload for field- and record-level error detail (PHASE-0 §5.2).
 * The {@code @RestControllerAdvice} that renders it lives per-service (Phase 1+), not in
 * this lib — common-core carries no Spring web dependencies.
 *
 * @param errors      field-scoped problems: path + message (+ optional offending value)
 * @param globalErrors record-scoped problems without a field anchor
 */
public record ProblemErrors(List<FieldError> errors, List<GlobalError> globalErrors) {

    public ProblemErrors {
        errors = errors == null ? List.of() : List.copyOf(errors);
        globalErrors = globalErrors == null ? List.of() : List.copyOf(globalErrors);
    }

    public static ProblemErrors of(FieldError... errors) {
        return new ProblemErrors(errors == null ? List.of() : List.of(errors), List.of());
    }

    public static ProblemErrors of(GlobalError... globalErrors) {
        return new ProblemErrors(List.of(), globalErrors == null ? List.of() : List.of(globalErrors));
    }

    public boolean isEmpty() {
        return errors.isEmpty() && globalErrors.isEmpty();
    }

    /** A field-anchored problem, rendered under the problem's {@code errors} array. */
    public record FieldError(String field, String message, Object rejectedValue) {
    }

    /** A record-scoped problem, rendered under the problem's {@code globalErrors} array. */
    public record GlobalError(String entity, String message) {
    }
}
