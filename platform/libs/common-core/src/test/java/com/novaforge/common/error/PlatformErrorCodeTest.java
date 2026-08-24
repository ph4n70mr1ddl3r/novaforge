package com.novaforge.common.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** PHASE-0 §5.3: ErrorCode uniqueness of {@code code}. */
class PlatformErrorCodeTest {

    @Test
    @DisplayName("codes are unique across the registry")
    void codesUnique() {
        Set<String> seen = new HashSet<>();
        for (PlatformErrorCode code : PlatformErrorCode.values()) {
            assertThat(seen.add(code.code()))
                    .as("code %s registered more than once", code.code())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("registry = the PHASE-0 §5.2 seed plus the codes later phases append")
    void seedSet() {
        // The seed is a floor, not a ceiling (PHASE-4 §2 appends 4010/4011; PHASE-6 §2
        // appends 4012 — SIGNATURE_INVALID).
        assertThat(Arrays.stream(PlatformErrorCode.values()).map(PlatformErrorCode::name))
                .containsExactlyInAnyOrder(
                        "VALIDATION_FAILED", "TENANT_MISSING", "FORBIDDEN",
                        "NOT_FOUND", "STATE_TRANSITION", "SOD_VIOLATION",
                        "SIGNATURE_INVALID", "CONFLICT_VERSION", "INTERNAL");
    }

    @Test
    @DisplayName("codes are 4-digit numeric strings paired with their HTTP status")
    void codeShape() {
        for (PlatformErrorCode code : PlatformErrorCode.values()) {
            assertThat(code.code()).matches("\\d{4}");
            assertThat(code.httpStatus()).isBetween(400, 599);
        }
        assertThat(PlatformErrorCode.VALIDATION_FAILED.code()).isEqualTo("4000");
        assertThat(PlatformErrorCode.VALIDATION_FAILED.httpStatus()).isEqualTo(400);
        assertThat(PlatformErrorCode.NOT_FOUND.code()).isEqualTo("4004");
        assertThat(PlatformErrorCode.NOT_FOUND.httpStatus()).isEqualTo(404);
        assertThat(PlatformErrorCode.CONFLICT_VERSION.code()).isEqualTo("4090");
        assertThat(PlatformErrorCode.CONFLICT_VERSION.httpStatus()).isEqualTo(409);
        assertThat(PlatformErrorCode.FORBIDDEN.code()).isEqualTo("4003");
        assertThat(PlatformErrorCode.TENANT_MISSING.code()).isEqualTo("4001");
        assertThat(PlatformErrorCode.INTERNAL.code()).isEqualTo("5000");
        assertThat(PlatformErrorCode.SIGNATURE_INVALID.code()).isEqualTo("4012");
        assertThat(PlatformErrorCode.SIGNATURE_INVALID.httpStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("ProblemErrors is immutable and null-safe")
    void problemErrorsImmutable() {
        ProblemErrors errors = ProblemErrors.of(new ProblemErrors.FieldError("name", "required", null));
        assertThat(errors.errors()).hasSize(1);
        assertThat(errors.globalErrors()).isEmpty();
        assertThat(errors.isEmpty()).isFalse();
        assertThat(errors.errors().get(0).rejectedValue()).isNull();
        assertThat(ProblemErrors.of((ProblemErrors.FieldError[]) null).errors()).isEmpty();
        assertThat(new ProblemErrors(null, null).isEmpty()).isTrue();
    }
}
