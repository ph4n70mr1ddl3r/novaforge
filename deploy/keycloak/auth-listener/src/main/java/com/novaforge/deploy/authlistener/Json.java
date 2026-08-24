package com.novaforge.deploy.authlistener;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/** Minimal JSON writer — Keycloak ships Jackson; the provider reuses it. */
final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    static String write(Map<String, Object> payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("auth event envelope not serializable", e);
        }
    }
}
