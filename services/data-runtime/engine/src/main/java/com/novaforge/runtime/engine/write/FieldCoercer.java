package com.novaforge.runtime.engine.write;

import com.novaforge.common.error.ProblemErrors;
import com.novaforge.metadata.EntityDefinition;
import com.novaforge.metadata.FieldDefinition;
import com.novaforge.metadata.FieldType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Field validation v1 (PHASE-1 §5): required, type, length, precision/scale (BigDecimal,
 * never doubles — ARCHITECTURE.md §4 money rule), enum membership, uuid/ISO-date shapes,
 * writes to readonly fields rejected. Canonicalizes to storage forms: dates as ISO text
 * (lexicographic = chronological under the ADR-001 text-promotion rule), datetimes as
 * UTC ISO-8601, numerics as BigDecimal.
 */
public final class FieldCoercer {

    /** UTC ISO-8601 with fixed millisecond width — the storage-canonical datetime. */
    public static final java.time.format.DateTimeFormatter CANONICAL_DATETIME =
            java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .withZone(java.time.ZoneOffset.UTC);

    /** Lookup-target existence + uniqueness checks need storage; supplied by the engine. */
    public interface ExternalChecks {
        boolean targetExists(String targetEntityApiName, UUID targetId);

        boolean valueIsUnique(EntityDefinition entity, FieldDefinition field, String canonicalText,
                              UUID excludeRecordId);
    }

    private FieldCoercer() {
    }

    /**
     * Validates the incoming field values against the entity and returns the canonical
     * data map (existing data for untouched fields is merged by the caller). Errors
     * accumulate into {@code errors} — callers reject once, with everything reported.
     */
    public static Map<String, Object> canonicalize(EntityDefinition entity,
                                                   Map<String, Object> incoming,
                                                   ExternalChecks checks,
                                                   UUID excludeRecordId,
                                                   List<ProblemErrors.FieldError> errors) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : incoming.entrySet()) {
            String name = entry.getKey();
            var fieldOpt = entity.field(name);
            if (fieldOpt.isEmpty()) {
                errors.add(error(name, "unknown field on " + entity.apiName(), name));
                continue;
            }
            FieldDefinition field = fieldOpt.get();
            Object value = entry.getValue();
            if (value == null) {
                if (field.requiredOn()) {
                    errors.add(error(name, field.apiName() + " is required", null));
                } else {
                    canonical.put(name, null);
                }
                continue;
            }
            Object coerced = coerce(field, value, errors);
            if (coerced != null) {
                canonical.put(name, coerced);
            }
        }
        // Uniqueness (friendly shape; the partial index is enforcement — §6).
        for (Map.Entry<String, Object> entry : canonical.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            var fieldOpt = entity.field(entry.getKey());
            if (fieldOpt.isEmpty() || !fieldOpt.get().uniqueOn()) {
                continue;
            }
            String text = toCanonicalText(fieldOpt.get(), entry.getValue());
            if (!checks.valueIsUnique(entity, fieldOpt.get(), text, excludeRecordId)) {
                errors.add(error(entry.getKey(),
                        entry.getKey() + " must be unique (tenant-scoped, live rows)", text));
            }
        }
        // Lookup targets exist (resolved within the same published app).
        for (Map.Entry<String, Object> entry : canonical.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            var fieldOpt = entity.field(entry.getKey());
            if (fieldOpt.isPresent() && fieldOpt.get().type() == FieldType.LOOKUP) {
                UUID targetId = UUID.fromString(String.valueOf(entry.getValue()));
                if (!checks.targetExists(fieldOpt.get().target(), targetId)) {
                    errors.add(error(entry.getKey(),
                            "lookup target " + fieldOpt.get().target() + "/" + targetId + " does not exist",
                            entry.getValue()));
                }
            }
        }
        return canonical;
    }

    /** Required-ness after defaults applied (create) or partial patch (update). */
    public static void checkRequired(EntityDefinition entity, Map<String, Object> data,
                                     List<ProblemErrors.FieldError> errors) {
        for (FieldDefinition field : entity.fields()) {
            if (field.requiredOn() && data.get(field.apiName()) == null) {
                errors.add(error(field.apiName(), field.apiName() + " is required", null));
            }
        }
    }

    private static Object coerce(FieldDefinition field, Object value,
                                 List<ProblemErrors.FieldError> errors) {
        String name = field.apiName();
        try {
            return switch (field.type()) {
                case TEXT, LONG_TEXT, RICH_TEXT -> text(field, value, name, errors);
                case EMAIL -> {
                    String text = text(field, value, name, errors);
                    yield text == null ? null
                            : text.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") ? text
                            : fail(name, "not a valid email: " + text, value, errors);
                }
                case PHONE -> {
                    String text = text(field, value, name, errors);
                    yield text == null ? null
                            : text.matches("^[+0-9()\\-\\.\\s]{3,25}$") ? text
                            : fail(name, "not a valid phone: " + text, value, errors);
                }
                case URL -> {
                    String text = text(field, value, name, errors);
                    yield text == null ? null
                            : (text.startsWith("http://") || text.startsWith("https://")) ? text
                            : fail(name, "not a valid http(s) URL: " + text, value, errors);
                }
                case ENUM -> {
                    if (!(value instanceof String option)) {
                        yield fail(name, "expected a string", value, errors);
                    }
                    yield field.values().contains(option) ? option
                            : fail(name, "not one of " + field.values(), option, errors);
                }
                case BOOLEAN -> value instanceof Boolean b ? b
                        : value instanceof String s && (s.equals("true") || s.equals("false"))
                                ? Boolean.parseBoolean(s)
                        : fail(name, "expected a boolean", value, errors);
                case INT, LONG -> {
                    BigDecimal decimal = decimal(name, value, errors);
                    if (decimal == null) {
                        yield null;
                    }
                    if (decimal.stripTrailingZeros().scale() > 0) {
                        yield fail(name, "expected an integer", value, errors);
                    }
                    try {
                        yield field.type() == FieldType.INT
                                ? (Object) decimal.intValueExact()
                                : (Object) decimal.longValueExact();
                    } catch (ArithmeticException outOfRange) {
                        // Integral but beyond the type's range: the same authoring-
                        // feedback class as the expression engine's round(x, 1.5) —
                        // the raw ArithmeticException slipped past this method's
                        // DateTimeParseException|IllegalArgumentException net and
                        // 500'd the write instead of joining the errors list.
                        yield fail(name, "value out of range for "
                                + field.type().name().toLowerCase() + ": " + decimal.toPlainString(),
                                value, errors);
                    }
                }
                case DECIMAL, MONEY -> {
                    BigDecimal decimal = decimal(name, value, errors);
                    if (decimal == null) {
                        yield null;
                    }
                    int scale = field.scale() == null
                            ? (field.type() == FieldType.MONEY ? 4 : 6) : field.scale();
                    int precision = field.precision() == null
                            ? (field.type() == FieldType.MONEY ? 18 : 38) : field.precision();
                    if (decimal.scale() > scale) {
                        yield fail(name, "scale " + decimal.scale() + " exceeds " + scale, value, errors);
                    }
                    if (decimal.precision() - decimal.scale() > precision - scale) {
                        yield fail(name, "precision exceeds " + precision + "/" + scale, value, errors);
                    }
                    yield decimal;
                }
                case DATE -> {
                    if (!(value instanceof String dateText)) {
                        yield fail(name, "expected an ISO date string", value, errors);
                    }
                    yield LocalDate.parse(dateText).toString();   // strict ISO, canonical
                }
                case DATETIME -> {
                    if (!(value instanceof String stampText)) {
                        yield fail(name, "expected an ISO-8601 datetime string", value, errors);
                    }
                    // an explicit offset (Z, +hh:mm, AND -hh:mm) parses as-is; only a
                    // naive stamp falls back to UTC. The old sniff looked for "+"
                    // alone, so a negative offset got "Z" appended to an already
                    // offset text and every write from a UTC-negative client
                    // rejected as "invalid value" (flushed by the twenty-ninth
                    // pass's FieldCoercerTests — the class had zero direct tests).
                    OffsetDateTime parsed;
                    try {
                        parsed = OffsetDateTime.parse(stampText);
                    } catch (DateTimeParseException noOffset) {
                        parsed = OffsetDateTime.parse(stampText + "Z");
                    }
                    // Fixed-width canonical form: lexicographic order == chronological
                    // order under the ADR-001 text-promotion rule.
                    yield CANONICAL_DATETIME.format(parsed.toInstant());
                }
                case TIME -> {
                    if (!(value instanceof String timeText)) {
                        yield fail(name, "expected an HH:mm:ss string", value, errors);
                    }
                    yield LocalTime.parse(timeText).toString();
                }
                case UUID -> {
                    if (!(value instanceof String uuidText)) {
                        yield fail(name, "expected a uuid string", value, errors);
                    }
                    yield UUID.fromString(uuidText).toString();
                }
                case JSON -> value;   // opaque JSON rides through as parsed
                case LOOKUP -> {
                    if (!(value instanceof String targetText)) {
                        yield fail(name, "expected a uuid reference", value, errors);
                    }
                    yield UUID.fromString(targetText).toString();
                }
                case FILE -> value instanceof String token ? token
                        : fail(name, "expected a file token (upload path lands with the File Service, Phase 6)",
                                value, errors);
                case CHILD, M2M -> fail(name,
                        "collections are addressed through the parent relationship, not as fields",
                        value, errors);
            };
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return fail(name, "invalid value: " + e.getMessage(), value, errors);
        }
    }

    private static String text(FieldDefinition field, Object value, String name,
                               List<ProblemErrors.FieldError> errors) {
        if (!(value instanceof String string)) {
            return (String) fail(name, "expected a string", value, errors);
        }
        if (field.length() != null && string.length() > field.length()) {
            return (String) fail(name, "length " + string.length() + " exceeds " + field.length(),
                    value, errors);
        }
        return string;
    }

    private static BigDecimal decimal(String name, Object value,
                                      List<ProblemErrors.FieldError> errors) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String string) {
            try {
                return new BigDecimal(string);
            } catch (NumberFormatException e) {
                return (BigDecimal) fail(name, "not a number", value, errors);
            }
        }
        return (BigDecimal) fail(name, "not a number", value, errors);
    }

    /** Canonical text form used by uniqueness checks and text-compare lowering. */
    public static String toCanonicalText(FieldDefinition field, Object value) {
        if (field.type().numeric()) {
            return value instanceof BigDecimal decimal ? decimal.toPlainString()
                    : String.valueOf(value);
        }
        return String.valueOf(value);
    }

    private static Object fail(String name, String message, Object value,
                               List<ProblemErrors.FieldError> errors) {
        errors.add(error(name, message, value));
        return null;
    }

    private static ProblemErrors.FieldError error(String field, String message, Object rejected) {
        return new ProblemErrors.FieldError(field, message, rejected);
    }
}
