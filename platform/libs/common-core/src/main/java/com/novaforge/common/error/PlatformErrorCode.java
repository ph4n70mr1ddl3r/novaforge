package com.novaforge.common.error;

/**
 * Platform error-code registry. Codes are stable numeric strings registered exactly once
 * corpus-wide (README/docs registry: 4000/4001/4003/4004 + 4010–4014 + 4090 + 5000).
 *
 * <p>Seed set per PHASE-0 §5.2; later phases append their codes here as they land.
 */
public enum PlatformErrorCode implements ErrorCode {

    VALIDATION_FAILED("4000", 400),
    TENANT_MISSING("4001", 400),
    FORBIDDEN("4003", 403),
    NOT_FOUND("4004", 404),
    STATE_TRANSITION("4010", 400),
    SOD_VIOLATION("4011", 400),
    CONFLICT_VERSION("4090", 409),
    INTERNAL("5000", 500);

    private final String code;
    private final int httpStatus;

    PlatformErrorCode(String code, int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
