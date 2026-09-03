package com.novaforge.metadata.api;

import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The component catalog's server-side half (PHASE-2 §4/§6): the v1 catalog
 * manifest — component ids, pinned prop-schema versions, and each component's
 * draft-2020-12 props schema — as a classpath resource, plus the focused schema
 * validator the page save/publish gate runs. The manifest is canonical; the TS
 * twin (<code>frontend/shared/src/catalog/schemas.ts</code>) pins its own copy
 * against this file with a lockstep suite, exactly like the expr/v1 conformance
 * corpus.
 *
 * <p>Why a JVM copy at all: §4 pins props validation and the version pin "at
 * save and publish time" — the definition APIs' gates, not only the builder's
 * client-side check. The bind rules joined the server gate first (their own
 * review pass) because a stored mismatch renders one field's configuration over
 * another's binding; an unknown component, a stale pin, or contract-violating
 * props is the same defect class — a page no catalog component can render per
 * contract, stored and published whichever client authored it, degrading the
 * rendered app to fallback UI. The server now checks what the document itself
 * carries: id, version, props.</p>
 */
public final class ComponentCatalog {

    /** One issue from {@link #validateProps} — the TS twin's {@code SchemaIssue}. */
    public record SchemaIssue(String path, String message) {
    }

    /** One catalog entry: id, pinned version, optional lifecycle, props schema. */
    public record Entry(String id, String version, String status,
                        Map<String, Object> deprecation, Map<String, Object> schema) {
    }

    private static final Map<String, Entry> ENTRIES = load();

    private ComponentCatalog() {
    }

    private static Map<String, Entry> load() {
        try (var stream = ComponentCatalog.class.getResourceAsStream("/catalog/component-catalog.json")) {
            if (stream == null) {
                throw new IllegalStateException(
                        "the component catalog manifest is missing from the classpath");
            }
            List<Map<String, Object>> raw = JsonMapper.builder().build()
                    .readValue(stream, List.class);
            Map<String, Entry> entries = new LinkedHashMap<>();
            for (Map<String, Object> item : raw) {
                String id = String.valueOf(item.get("id"));
                entries.put(id, new Entry(
                        id,
                        String.valueOf(item.get("version")),
                        item.get("status") == null ? null : String.valueOf(item.get("status")),
                        castMap(item.get("deprecation")),
                        castMap(item.get("schema"))));
            }
            return Map.copyOf(entries);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "the component catalog manifest failed to parse: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value == null ? null : (Map<String, Object>) value;
    }

    /** The entry for a component id, or {@code null} when the id is unknown. */
    public static Entry find(String componentId) {
        return ENTRIES.get(componentId);
    }

    /**
     * The focused draft-2020-12 subset the catalog actually uses — the exact
     * mirror of the TS twin's {@code validateSchema}, message for message:
     * type (incl. union), enum, string length/pattern, number bounds, array
     * minItems/items, object properties/required/additionalProperties. Neither
     * engine implements more (drift is the lockstep suites' business).
     */
    public static List<SchemaIssue> validateProps(Object value, Map<String, Object> schema,
                                                  String path) {
        List<SchemaIssue> issues = new ArrayList<>();
        collect(value, schema, path, issues);
        return issues;
    }

    @SuppressWarnings("unchecked")
    private static void collect(Object value, Map<String, Object> schema, String path,
                                List<SchemaIssue> issues) {
        Object typeClaim = schema.get("type");
        if (typeClaim != null) {
            List<String> allowed = typeClaim instanceof List<?> union
                    ? union.stream().map(String::valueOf).toList()
                    : List.of(String.valueOf(typeClaim));
            String actual = typeOf(value);
            if (allowed.stream().noneMatch(t -> typeMatches(value, t))) {
                issues.add(new SchemaIssue(path,
                        "expected " + String.join("|", allowed) + ", got " + actual));
                return;
            }
        }
        if (schema.get("enum") instanceof List<?> options) {
            String wire = wireOf(value);
            boolean hit = false;
            for (Object option : options) {
                if (wireOf(option).equals(wire)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                issues.add(new SchemaIssue(path,
                        "must be one of " + wireOf(options)));
            }
        }
        if (value instanceof String s) {
            if (schema.get("minLength") instanceof Number minLength && s.length() < minLength.intValue()) {
                issues.add(new SchemaIssue(path, "shorter than minLength " + minLength));
            }
            if (schema.get("maxLength") instanceof Number maxLength && s.length() > maxLength.intValue()) {
                issues.add(new SchemaIssue(path, "longer than maxLength " + maxLength));
            }
            if (schema.get("pattern") instanceof String pattern && !s.matches(pattern)) {
                issues.add(new SchemaIssue(path, "does not match pattern " + pattern));
            }
        }
        if (value instanceof Number n) {
            if (schema.get("minimum") instanceof Number minimum && n.doubleValue() < minimum.doubleValue()) {
                issues.add(new SchemaIssue(path, "below minimum " + minimum));
            }
            if (schema.get("maximum") instanceof Number maximum && n.doubleValue() > maximum.doubleValue()) {
                issues.add(new SchemaIssue(path, "above maximum " + maximum));
            }
        }
        if (value instanceof List<?> items) {
            if (schema.get("minItems") instanceof Number minItems && items.size() < minItems.intValue()) {
                issues.add(new SchemaIssue(path, "fewer than minItems " + minItems));
            }
            if (schema.get("items") instanceof Map<?, ?> itemSchema) {
                for (int i = 0; i < items.size(); i++) {
                    collect(items.get(i), (Map<String, Object>) itemSchema, path + "[" + i + "]", issues);
                }
            }
        }
        if (value != null && !(value instanceof List<?>) && value instanceof Map<?, ?>) {
            Map<String, Object> asMap = (Map<String, Object>) value;
            Map<String, Object> properties =
                    schema.get("properties") instanceof Map<?, ?> p ? (Map<String, Object>) p : null;
            if (properties != null) {
                for (var name : properties.keySet()) {
                    if (asMap.containsKey(name)) {
                        collect(asMap.get(name), (Map<String, Object>) properties.get(name),
                                path + "." + name, issues);
                    }
                }
            }
            Object additional = schema.get("additionalProperties");
            if (Boolean.FALSE.equals(additional) && properties != null) {
                for (String name : asMap.keySet()) {
                    if (!properties.containsKey(name)) {
                        issues.add(new SchemaIssue(path + "." + name,
                                "unknown property (additionalProperties false)"));
                    }
                }
            } else if (additional instanceof Map<?, ?> additionalSchema && properties != null) {
                for (String name : asMap.keySet()) {
                    if (!properties.containsKey(name)) {
                        collect(asMap.get(name), (Map<String, Object>) additionalSchema,
                                path + "." + name, issues);
                    }
                }
            }
            if (schema.get("required") instanceof List<?> required) {
                for (Object name : required) {
                    // the twin's test is `=== undefined`: present-but-null satisfies
                    // required (the property's own schema judges the null)
                    if (!asMap.containsKey(name)) {
                        issues.add(new SchemaIssue(path,
                                "missing required property '" + name + "'"));
                    }
                }
            }
        }
    }

    /** The TS twin's {@code typeOf}: the wire type name, integers split from numbers. */
    private static String typeOf(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof List<?>) {
            return "array";
        }
        if (value instanceof Number) {
            return isIntegral(value) ? "integer" : "number";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        return "undefined";
    }

    /** {@code type} matching with the JSON Schema number/integer overlap. */
    private static boolean typeMatches(Object value, String type) {
        String actual = typeOf(value);
        if ("number".equals(type)) {
            return "number".equals(actual) || "integer".equals(actual);
        }
        return type.equals(actual);
    }

    /** Whether a JSON number carries a fractional part (the JS {@code Number.isInteger} sense). */
    private static boolean isIntegral(Object value) {
        if (value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte) {
            return true;
        }
        if (value instanceof java.math.BigDecimal d) {
            return d.stripTrailingZeros().scale() <= 0;
        }
        if (value instanceof Double d) {
            return !d.isInfinite() && !d.isNaN() && d == Math.floor(d);
        }
        if (value instanceof Float f) {
            return !f.isInfinite() && !f.isNaN() && f == Math.floor(f);
        }
        return false;
    }

    /** Canonical wire form for enum membership (deep for options that are arrays/objects). */
    private static String wireOf(Object value) {
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(wireOf(list.get(i)));
            }
            return out.append(']').toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (var entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(wireOf(String.valueOf(entry.getKey()))).append(':')
                        .append(wireOf(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return "\"" + s + "\"";
        }
        return String.valueOf(value);
    }

    /** The set of catalog ids (test surfacing). */
    static Set<String> ids() {
        return ENTRIES.keySet();
    }
}
