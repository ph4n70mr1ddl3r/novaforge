package com.novaforge.runtime.api;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.common.error.ProblemErrors;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 7807 problem+json with the common-core codes (PHASE-1 §7) — the same shape the
 * other services render.
 */
@RestControllerAdvice
public class ProblemAdvice {

    public static final URI PROBLEM_TYPE = URI.create("https://novaforge.dev/problems/");

    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<Map<String, Object>> platform(PlatformException exception) {
        return problem(HttpStatus.valueOf(exception.errorCode().httpStatus()),
                exception.errorCode().code(), exception.errorCode().name(), exception.getMessage(),
                exception.detail().orElse(null));
    }

    @ExceptionHandler({IllegalArgumentException.class, tools.jackson.databind.exc.MismatchedInputException.class,
            com.novaforge.expression.ExpressionException.class})
    public ResponseEntity<Map<String, Object>> badRequest(Exception exception) {
        // Authored expressions that fail at evaluation (a slot the static
        // compile-check could not type, PHASE-3 §2) are authoring feedback — a
        // metadata defect rendered as 400 VALIDATION_FAILED naming the expression,
        // never a bare 500 "unexpected error" on the first matching record.
        return problem(HttpStatus.BAD_REQUEST, PlatformErrorCode.VALIDATION_FAILED.code(),
                PlatformErrorCode.VALIDATION_FAILED.name(), exception.getMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> internal(Exception exception) {
        LOG.error("unhandled error rendering as problem+json", exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, PlatformErrorCode.INTERNAL.code(),
                PlatformErrorCode.INTERNAL.name(), "unexpected error", null);
    }

    private ResponseEntity<Map<String, Object>> problem(HttpStatus status, String code, String title,
                                                        String detail, ProblemErrors errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", PROBLEM_TYPE + code);
        body.put("title", title);
        body.put("status", status.value());
        body.put("code", code);
        if (detail != null) {
            body.put("detail", detail);
        }
        if (errors != null && !errors.isEmpty()) {
            body.put("errors", errors.errors());
            body.put("globalErrors", errors.globalErrors());
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(ProblemAdvice.class);
}
