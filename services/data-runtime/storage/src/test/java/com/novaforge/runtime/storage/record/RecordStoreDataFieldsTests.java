package com.novaforge.runtime.storage.record;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The data-jsonb decode behind every read (point finds, list pages, the update
 * merge, child walks) — the row shape must carry json-typed field values through,
 * never crash on them. Against the bug: a nested object/array fell through to
 * Jackson's asString(), which throws for non-scalar nodes on Jackson 3 (and
 * silently erased them to "" on Jackson 2) — one record with a json field made
 * every read of it, and every list page it sat on, fail. Pinned here so the decode
 * stays total and structure-preserving.
 */
class RecordStoreDataFieldsTests {

    private static final tools.jackson.databind.json.JsonMapper MAPPER =
            JsonMapper.builder().build();

    @Test
    @DisplayName("a json-typed object field decodes as a map — the read never throws")
    void jsonObjectFieldDecodesAsMap() {
        Map<String, Object> fields = RecordStore.dataFields(
                "{\"label\":\"widget\",\"settings\":{\"theme\":\"dark\",\"retries\":3}}");

        assertThat(fields.get("label")).isEqualTo("widget");
        assertThat(fields.get("settings")).isInstanceOf(Map.class)
                .isEqualTo(Map.of("theme", "dark", "retries", 3));
    }

    @Test
    @DisplayName("a json-typed array field decodes as a list — empty containers included")
    void jsonArrayFieldDecodesAsList() {
        Map<String, Object> fields = RecordStore.dataFields(
                "{\"tags\":[\"a\",\"b\"],\"emptyList\":[],\"emptyMap\":{}}");

        assertThat(fields.get("tags")).isEqualTo(List.of("a", "b"));
        assertThat(fields.get("emptyList")).isEqualTo(List.of());
        assertThat(fields.get("emptyMap")).isEqualTo(Map.of());
    }

    @Test
    @DisplayName("nested structure round-trips deep — the opaque value keeps its shape")
    void deepNestingRoundTrips() {
        Map<String, Object> fields = RecordStore.dataFields(
                "{\"doc\":{\"meta\":{\"authors\":[{\"id\":7,\"active\":true}]}}}");

        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) fields.get("doc");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) doc.get("meta");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> authors = (List<Map<String, Object>>) meta.get("authors");
        assertThat(authors).singleElement().satisfies(author -> {
            assertThat(author.get("id")).isEqualTo(7);
            assertThat(author.get("active")).isEqualTo(true);
        });
    }

    @Test
    @DisplayName("scalars keep their exact decode: numbers as BigDecimal, booleans, strings")
    void scalarDecodesStayExact() {
        Map<String, Object> fields = RecordStore.dataFields(
                "{\"pennies\":0.1,\"big\":12345678901234567890,\"flag\":false,\"name\":\"a%b\"}");

        assertThat(fields.get("pennies")).isEqualTo(new BigDecimal("0.1"));
        assertThat(fields.get("big")).isEqualTo(new BigDecimal("12345678901234567890"));
        assertThat(fields.get("flag")).isEqualTo(false);
        assertThat(fields.get("name")).isEqualTo("a%b");
    }

    @Test
    @DisplayName("an explicit JSON null decodes as null — never the empty string")
    void explicitNullDecodesAsNull() {
        Map<String, Object> fields = RecordStore.dataFields("{\"cleared\":null,\"kept\":\"x\"}");

        assertThat(fields).containsEntry("cleared", null).containsEntry("kept", "x");
    }

    @Test
    @DisplayName("the decode is idempotent under the write-path re-encode (update merge shape)")
    void decodeReencodesToTheSameFields() {
        String stored = "{\"n\":1.5,\"s\":\"t\",\"b\":true,\"o\":{\"k\":[1,2]},\"z\":null}";
        Map<String, Object> first = RecordStore.dataFields(stored);
        Map<String, Object> second = RecordStore.dataFields(
                MAPPER.writeValueAsString(first));

        assertThat(second).isEqualTo(first);
    }
}
