package com.novaforge.common.error;

/**
 * A platform error code: a stable numeric string plus the HTTP status it renders under
 * (RFC 7807 problem+json, ARCHITECTURE.md §6). Implementations must keep codes unique —
 * enforced by {@code PlatformErrorCodeTest}.
 */
public sealed interface ErrorCode permits PlatformErrorCode {

    /** Stable numeric string, e.g. {@code "4000"}. Never reused, never renumbered. */
    String code();

    /** HTTP status the error renders under. */
    int httpStatus();

    /** Symbolic name used in logs and problem payloads, e.g. {@code VALIDATION_FAILED}. */
    default String name() {
        return ((Enum<?>) this).name();
    }
}
