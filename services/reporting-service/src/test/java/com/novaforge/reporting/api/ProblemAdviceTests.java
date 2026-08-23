package com.novaforge.reporting.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The export's {@code params} query value is client-supplied JSON parsed in the
 * controller — a malformed string is a 400 VALIDATION_FAILED problem, never the
 * generic 500 (found at the Phase 5 review: only mapping exceptions were covered,
 * malformed text fell through to the internal handler).
 */
class ProblemAdviceTests {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final ProblemAdvice advice = new ProblemAdvice();

    @Test
    @DisplayName("malformed client JSON renders 400 VALIDATION_FAILED problem+json")
    void malformedJsonIsBadRequest() {
        Exception malformed;
        try {
            MAPPER.readValue("{not json", Map.class);
            throw new AssertionError("readValue should have thrown");
        } catch (tools.jackson.core.JacksonException e) {
            malformed = e;
        }
        var response = advice.badRequest(malformed);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("code")).isEqualTo("4000");
        assertThat(body.get("title")).isEqualTo("VALIDATION_FAILED");
        assertThat(String.valueOf(body.get("detail"))).isNotBlank();
    }
}
