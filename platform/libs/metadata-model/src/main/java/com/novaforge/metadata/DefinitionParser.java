package com.novaforge.metadata;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parse/serialize helper for definition JSON using Jackson 3 (the platform's default
 * codec). Unknown properties are ignored by default in Jackson 3, keeping drafts
 * forward-compatible across schema versions.
 */
public final class DefinitionParser {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private DefinitionParser() {
    }

    public static AppDefinition parseApp(String json) {
        return MAPPER.readValue(json, AppDefinition.class);
    }

    public static String writeApp(AppDefinition app) {
        return MAPPER.writeValueAsString(app);
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static <T> T parse(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
