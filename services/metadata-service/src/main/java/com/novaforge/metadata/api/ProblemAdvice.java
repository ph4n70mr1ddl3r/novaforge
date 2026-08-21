package com.novaforge.metadata.api;

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
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * RFC 7807 problem+json rendering with the common-core error codes — the PHASE-0 §5.2
 * deferral lands here (per-service advice, PHASE-1 §7).
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

    @ExceptionHandler({IllegalArgumentException.class, MismatchedInputException.class})
    public ResponseEntity<Map<String, Object>> badRequest(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, PlatformErrorCode.VALIDATION_FAILED.code(),
                PlatformErrorCode.VALIDATION_FAILED.name(), exception.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException exception) {
        ProblemErrors errors = new ProblemErrors(
                exception.getBindingResult().getFieldErrors().stream()
                        .map(f -> new ProblemErrors.FieldError(f.getField(), f.getDefaultMessage(),
                                f.getRejectedValue()))
                        .toList(),
                List.of());
        return problem(HttpStatus.BAD_REQUEST, PlatformErrorCode.VALIDATION_FAILED.code(),
                PlatformErrorCode.VALIDATION_FAILED.name(), "request validation failed", errors);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> internal(Exception exception) {
        LOG.error("unhandled error rendering as problem+json", exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, PlatformErrorCode.INTERNAL.code(),
                PlatformErrorCode.INTERNAL.name(), "unexpected error", null);
    }

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(ProblemAdvice.class);

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
