package com.novaforge.integration.api;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.common.error.ProblemErrors;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * RFC 7807 problem+json with the common-core codes (PHASE-1 §7) — the same shape the
 * other services render, so a capped or failing script surfaces to the Data Runtime's
 * hook path as a first-class problem.
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

    @ExceptionHandler({IllegalArgumentException.class, tools.jackson.databind.exc.MismatchedInputException.class})
    public ResponseEntity<Map<String, Object>> badRequest(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, PlatformErrorCode.VALIDATION_FAILED.code(),
                PlatformErrorCode.VALIDATION_FAILED.name(), exception.getMessage(), null);
    }

    /**
     * Unrouted paths (a wrong-shape webhook URL — one segment where the route pins
     * three) surface the container's {@link NoResourceFoundException}; without this
     * handler the catch-all below answers 500 and pollutes monitoring (the
     * 2026-08-28 pen pass's PF-2 close — noise, no information leak).
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> noRoute(NoResourceFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, PlatformErrorCode.NOT_FOUND.code(),
                PlatformErrorCode.NOT_FOUND.name(), "no such route", null);
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
