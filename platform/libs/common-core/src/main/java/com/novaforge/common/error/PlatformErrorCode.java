package com.novaforge.common.error;

/**
 * Platform error-code registry. Codes are stable numeric strings registered exactly once
 * corpus-wide. Registered here: 4000/4001/4003/4004 + 4090 + 5000 (the PHASE-0 §5.2
 * seed) plus each phase's additions as they land (Phase 4: 4010/4011, Phase 6:
 * 4012 — SIGNATURE_INVALID, the webhook HMAC failure both directions render,
 * Phase 7: 4013/4014 — the two §3 harvests).
 */
public enum PlatformErrorCode implements ErrorCode {

    VALIDATION_FAILED("4000", 400),
    TENANT_MISSING("4001", 400),
    FORBIDDEN("4003", 403),
    NOT_FOUND("4004", 404),
    STATE_TRANSITION("4010", 400),
    SOD_VIOLATION("4011", 400),
    SIGNATURE_INVALID("4012", 401),
    RECORD_FROZEN("4013", 400),
    PERIOD_LOCKED("4014", 400),
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
