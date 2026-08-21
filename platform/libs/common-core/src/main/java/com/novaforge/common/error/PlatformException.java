package com.novaforge.common.error;

import java.util.Optional;

/**
 * The single runtime carrier for platform error codes. Per-service
 * {@code @RestControllerAdvice}s (Phase 1+) render it as RFC 7807 problem+json with the
 * {@link ProblemErrors} detail attached (PHASE-1 §7).
 */
public class PlatformException extends RuntimeException {

    private final ErrorCode errorCode;
    private final ProblemErrors detail;

    public PlatformException(ErrorCode errorCode, String message) {
        this(errorCode, message, null, null);
    }

    public PlatformException(ErrorCode errorCode, String message, ProblemErrors detail) {
        this(errorCode, message, detail, null);
    }

    public PlatformException(ErrorCode errorCode, String message, ProblemErrors detail, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.detail = detail;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Optional<ProblemErrors> detail() {
        return Optional.ofNullable(detail);
    }
}
