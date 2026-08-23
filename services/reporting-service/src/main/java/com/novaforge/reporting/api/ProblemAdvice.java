package com.novaforge.reporting.api;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.common.error.ProblemErrors;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 7807 problem+json with the common-core codes (§2 — no new codes; report errors
 * reuse VALIDATION_FAILED / NOT_FOUND / FORBIDDEN), the same shape every service
 * renders so problem payloads stay first-class across the spine.
 */
@RestControllerAdvice
public class ProblemAdvice {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemAdvice.class);

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
}
