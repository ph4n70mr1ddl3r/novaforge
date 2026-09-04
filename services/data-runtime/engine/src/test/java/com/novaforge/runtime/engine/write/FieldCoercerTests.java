package com.novaforge.runtime.engine.write;

import static org.assertj.core.api.Assertions.assertThat;

import com.novaforge.common.error.ProblemErrors;
import com.novaforge.metadata.EntityDefinition;
import com.novaforge.metadata.FieldDefinition;
import com.novaforge.metadata.FieldType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FieldCoercer had ZERO direct tests (twenty-ninth pass coverage audit): the
 * journey suites ride its happy paths, so every rejection branch — the shape
 * gates that keep malformed data out of the promoted columns — was unpinned.
 * Cheapest possible unit surface (pure static), highest blast radius: the
 * datetime canonical form is the ADR-001 lexicographic=chronological
 * invariant, the MONEY scale/precision walls are ARCHITECTURE.md §4, and the
 * enum/email/phone/url/uuid branches are the write path's input contract.
 */
class FieldCoercerTests {

    private static final UUID TARGET_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private static EntityDefinition entity(FieldDefinition... fields) {
        return new EntityDefinition("e1", "invoice", "Invoice", null, null, null,
                List.of(fields), null, null, null);
    }

    private static FieldDefinition field(String name, FieldType type) {
        return FieldDefinition.of(name, type);
    }

    private static FieldDefinition field(String name, FieldType type, Integer precision,
                                         Integer scale, List<String> values) {
        return new FieldDefinition(name, null, null, type, null, null, null,
                null, precision, scale, null, null, null, values, null, null, null);
    }

    /** Stub checks: lookups resolve only for TARGET_ID; uniqueness is the flag. */
    private static FieldCoercer.ExternalChecks checks(boolean unique) {
        return new FieldCoercer.ExternalChecks() {
            @Override
            public boolean targetExists(String targetEntityApiName, UUID targetId) {
                return targetId.equals(TARGET_ID);
            }

            @Override
            public boolean valueIsUnique(EntityDefinition e, FieldDefinition f,
                                         String canonicalText, UUID exclude) {
                return unique;
            }
        };
    }

    private static ProblemErrors.FieldError sole(List<ProblemErrors.FieldError> errors) {
        assertThat(errors).hasSize(1);
        return errors.get(0);
    }

    /** Map.of rejects null values; the coercer's null path needs one that allows them. */
    private static Map<String, Object> map(Object... keyValues) {
        var map = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("unknown fields reject by name; null on a non-required field canonicalizes to null")
    void unknownAndNull() {
        var errors = new java.util.ArrayList<ProblemErrors.FieldError>();
        var canonical = FieldCoercer.canonicalize(entity(field("note", FieldType.TEXT)),
                map("nope", "x", "note", null), checks(true), null, errors);
        assertThat(sole(errors).field()).isEqualTo("nope");
        assertThat(canonical).containsEntry("note", (Object) null);
    }

    @Test
    @DisplayName("null on a required field rejects; checkRequired catches omitted required fields")
    void requiredGates() {
        var required = new FieldDefinition("amount", null, null, FieldType.MONEY,
                true, null, null, null, null, null, null, null, null, null, null, null, null);
        var errors = new java.util.ArrayList<ProblemErrors.FieldError>();
        FieldCoercer.canonicalize(entity(required), map("amount", null), checks(true), null, errors);
        assertThat(sole(errors).message()).isEqualTo("amount is required");

        var errors2 = new java.util.ArrayList<ProblemErrors.FieldError>();
        FieldCoercer.checkRequired(entity(required), Map.of(), errors2);
        assertThat(sole(errors2).field()).isEqualTo("amount");
    }

    @Test
    @DisplayName("TEXT length wall")
    void textLength() {
        var capped = new FieldDefinition("code", null, null, FieldType.TEXT, null, null,
                null, 3, null, null, null, null, null, null, null, null, null);
        var errors = new java.util.ArrayList<ProblemErrors.FieldError>();
        var canonical = FieldCoercer.canonicalize(entity(capped), Map.of("code", "abcd"),
                checks(true), null, errors);
        assertThat(canonical).doesNotContainKey("code");
        assertThat(sole(errors).message()).contains("length 4 exceeds 3");
    }

    @Test
    @DisplayName("EMAIL / PHONE / URL shape gates")
    void contactShapes() {
        var errors = new java.util.ArrayList<ProblemErrors.FieldError>();
        var canonical = FieldCoercer.canonicalize(
                entity(field("email", FieldType.EMAIL), field("phone", FieldType.PHONE),
                        field("site", FieldType.URL)),
                Map.of("email", "a@b.co", "phone", "+1 (555) 123-4567", "site", "https://x.dev/y"),
                checks(true), null, errors);
        assertThat(errors).isEmpty();
        assertThat(canonical).containsEntry("email", "a@b.co").containsEntry("site", "https://x.dev/y");

        var bad = new java.util.ArrayList<ProblemErrors.FieldError>();
        FieldCoercer.canonicalize(
                entity(field("email", FieldType.EMAIL), field("phone", FieldType.PHONE),
                        field("site", FieldType.URL)),
                Map.of("email", "not-an-email", "phone", "call me maybe", "site", "ftp://x.dev"),
                checks(true), null, bad);
        assertThat(bad).extracting(ProblemErrors.FieldError::field)
                .containsExactlyInAnyOrder("email", "phone", "site");
    }

    @Test
    @DisplayName("ENUM membership and BOOLEAN parsing")
    void enumAndBoolean() {
        var status = field("status", FieldType.ENUM, null, null, List.of("NEW", "PAID"));
        var errors = new java.util.ArrayList<ProblemErrors.FieldError>();
        var canonical = FieldCoercer.canonicalize(
                entity(status, field("active", FieldType.BOOLEAN)),
                Map.of("status", "PAID", "active", "true"), checks(true), null, errors);
        assertThat(errors).isEmpty();
        assertThat(canonical).containsEntry("status", "PAID").containsEntry("active", Boolean.TRUE);

        var bad = new java.util.ArrayList<ProblemErrors.FieldError>();
        FieldCoercer.canonicalize(entity(status), Map.of("status", "REFUNDED"), checks(true), null, bad);
        assertThat(sole(bad).message()).contains("not one of");
    }

    @Test
    @DisplayName("INT/LONG reject fractions; MONEY scale and precision walls hold")
    void numericWalls() {
        var errors = new java.util.ArrayList<ProblemErrors.FieldError>();
        var canonical = FieldCoercer.canonicalize(
                entity(field("qty", FieldType.INT), field("big", FieldType.LONG)),
                Map.of("qty", 3.0, "big", "97"), checks(true), null, errors);
        assertThat(errors).isEmpty();
        assertThat(canonical).containsEntry("qty", 3).containsEntry("big", 97L);

        var bad = new java.util.ArrayList<ProblemErrors.FieldError>();
        FieldCoercer.canonicalize(entity(field("qty", FieldType.INT)),
                Map.of("qty", "3.5"), checks(true), null, bad);
        assertThat(sole(bad).message()).isEqualTo("expected an integer");

        // Integral but beyond the type's range joins the errors list like every other
        // field defect (a shaped 400 with the field named) — the old code let
        // intValueExact()/longValueExact()'s raw ArithmeticException escape the
        // coerce net and 500 the write (the round(x, 1.5) defect class on the
        // coercion door).
        var intOverflow = new java.util.ArrayList<ProblemErrors.FieldError>();
        FieldCoercer.canonicalize(entity(field("qty", FieldType.INT)),
                Map.of("qty", 3_000_000_000L), checks(true), null, intOverflow);
        assertThat(sole(intOverflow).message())
                .isEqualTo("value out of range for int: 3000000000");

        var intUnderflow = new java.util.ArrayList<ProblemErrors.FieldError>();
        FieldCoercer.canonicalize(entity(field("qty", FieldType.INT)),
                Map.of("qty", -3_000_000_000L), checks(true), null, intUnderflow);
        assertThat(sole(intUnderflow).message())
                .isEqualTo("value out of range for int: -3000000000");

        var longOverflow = new java.util.ArrayList<ProblemErrors.FieldError>();
        var canonicalOverflow = FieldCoercer.canonicalize(entity(field("big", FieldType.LONG)),
                Map.of("big", new BigDecimal("1e30")), checks(true), null, longOverflow);
        assertThat(sole(longOverflow).message()).startsWith("value out of range for long:");
        assertThat(canonicalOverflow).doesNotContainKey("big");

        // in-range values still canonicalize through the same branch
        var inRange = new java.util.ArrayList<ProblemErrors.FieldError>();
        var canonicalInRange = FieldCoercer.canonicalize(
                entity(field("qty", FieldType.INT), field("big", FieldType.LONG)),
                map("qty", 2_000_000_000L, "big", -9_000_000_000L), checks(true), null, inRange);
        assertThat(inRange).isEmpty();
        assertThat(canonicalInRange).containsEntry("qty", 2_000_000_000)
                .containsEntry("big", -9_000_000_000L);

        // MONEY defaults: precision 18, scale 4 (ARCHITECTURE.md §4 money rule)
        var moneyErrors = new java.util.ArrayList<ProblemErrors.FieldError>();
        FieldCoercer.canonicalize(entity(field("amount", FieldType.MONEY)),
                Map.of("amount", new BigDecimal("1.23456")), checks(true), null, moneyErrors);
        assertThat(sole(moneyErrors).message()).contains("scale 5 exceeds 4");

        var precisionErrors = new java.util.ArrayList<ProblemErrors.FieldError>();
        FieldCoercer.canonicalize(entity(field("amount", FieldType.MONEY)),
                Map.of("amount", new BigDecimal("123456789012345678.9")), checks(true), null,
                precisionErrors);
        assertThat(sole(precisionErrors).message()).contains("precision exceeds");

        // an explicitly narrower custom scale/precision pin honors its own walls
        var narrow = field("pct", FieldType.DECIMAL, 5, 2, null);
        var narrowErrors = new java.util.ArrayList<ProblemErrors.FieldError>();
        FieldCoercer.canonicalize(entity(narrow), Map.of("pct", "1234.56"), checks(true), null,
                narrowErrors);
        assertThat(sole(narrowErrors).message()).contains("precision exceeds 5/2");
    }

    @Test
    @DisplayName("DATETIME canonicalizes to fixed-width UTC — lexicographic == chronological (ADR-001)")
    void datetimeCanonical() {
        var errors = new java.util.ArrayList<ProblemErrors.FieldError>();
        var canonical = FieldCoercer.canonicalize(entity(field("at", FieldType.DATETIME)),
                Map.of("at", "2026-09-02T18:00:00+08:00"), checks(true), null, errors);
        assertThat(errors).isEmpty();
        // 18:00+08 == 10:00Z — the canonical text form, fixed .SSS width
        assertThat(canonical).containsEntry("at", "2026-09-02T10:00:00.000Z");

        // a naive stamp is read as UTC, not silently shifted
        var naiveErrors = new java.util.ArrayList<ProblemErrors.FieldError>();
        var naive = FieldCoercer.canonicalize(entity(field("at", FieldType.DATETIME)),
                Map.of("at", "2026-09-02T10:00:00"), checks(true), null, naiveErrors);
        assertThat(naiveErrors).isEmpty();
        assertThat(naive).containsEntry("at", "2026-09-02T10:00:00.000Z");

        // the ordering invariant the storage layer's text compares rely on:
        // a chronologically later instant sorts lexicographically later — and a
        // NEGATIVE offset parses (the pre-fix code appended Z to an already
        // offset text and rejected every UTC-negative client's writes)
        var negativeOffsetErrors = new java.util.ArrayList<ProblemErrors.FieldError>();
        var negativeOffset = FieldCoercer.canonicalize(entity(field("at", FieldType.DATETIME)),
                Map.of("at", "2026-09-02T01:00:00-08:00"), checks(true), null,
                negativeOffsetErrors).get("at");
        assertThat(negativeOffsetErrors).isEmpty();
        assertThat((String) canonical.get("at")).isGreaterThan((String) negativeOffset);

        var bad = new java.util.ArrayList<ProblemErrors.FieldError>();
        FieldCoercer.canonicalize(entity(field("at", FieldType.DATETIME)),
                Map.of("at", "yesterday"), checks(true), null, bad);
        assertThat(sole(bad).message()).startsWith("invalid value:");
    }

    @Test
    @DisplayName("DATE, TIME, UUID canonicalize; LOOKUP canonicalizes and existence-checks")
    void dateUuidLookup() {
        var errors = new java.util.ArrayList<ProblemErrors.FieldError>();
        var lookup = new FieldDefinition("customer", null, null, FieldType.LOOKUP, null, null,
                null, null, null, null, null, null, "customer", null, null, null, null);
        var canonical = FieldCoercer.canonicalize(
                entity(field("on", FieldType.DATE), field("at", FieldType.TIME),
                        field("ref", FieldType.UUID), lookup),
                Map.of("on", "2026-09-02", "at", "10:15:30",
                        "ref", TARGET_ID.toString(), "customer", TARGET_ID.toString()),
                checks(true), null, errors);
        assertThat(errors).isEmpty();
        assertThat(canonical).containsEntry("on", "2026-09-02")
                .containsEntry("at", "10:15:30")
                .containsEntry("ref", TARGET_ID.toString());

        var bad = new java.util.ArrayList<ProblemErrors.FieldError>();
        FieldCoercer.canonicalize(entity(lookup),
                Map.of("customer", "not-a-uuid"), checks(true), null, bad);
        assertThat(sole(bad).message()).startsWith("invalid value:");

        var missing = new java.util.ArrayList<ProblemErrors.FieldError>();
        var other = UUID.fromString("44444444-4444-4444-8444-444444444444");
        FieldCoercer.canonicalize(entity(lookup),
                Map.of("customer", other.toString()), checks(true), null, missing);
        assertThat(sole(missing).message()).contains("does not exist");
    }

    @Test
    @DisplayName("unique fields check through canonical text; violations reject by field")
    void uniqueness() {
        var unique = new FieldDefinition("code", null, null, FieldType.TEXT, null, true,
                null, null, null, null, null, null, null, null, null, null, null);
        var errors = new java.util.ArrayList<ProblemErrors.FieldError>();
        FieldCoercer.canonicalize(entity(unique), Map.of("code", "DUP"), checks(false), null, errors);
        assertThat(sole(errors).message()).contains("must be unique");

        var errorsOk = new java.util.ArrayList<ProblemErrors.FieldError>();
        FieldCoercer.canonicalize(entity(unique), Map.of("code", "OK1"), checks(true), null, errorsOk);
        assertThat(errorsOk).isEmpty();
    }

    @Test
    @DisplayName("JSON rides through; CHILD/M2M reject; FILE wants a token; non-string scalars reject")
    void opaqueAndForbidden() {
        var payload = Map.of("k", 1);
        var errors = new java.util.ArrayList<ProblemErrors.FieldError>();
        var canonical = FieldCoercer.canonicalize(
                entity(field("meta", FieldType.JSON), field("file", FieldType.FILE)),
                Map.of("meta", payload, "file", "tok-1"), checks(true), null, errors);
        assertThat(errors).isEmpty();
        assertThat(canonical).containsEntry("meta", payload).containsEntry("file", "tok-1");

        var child = new java.util.ArrayList<ProblemErrors.FieldError>();
        FieldCoercer.canonicalize(entity(field("lines", FieldType.CHILD)),
                Map.of("lines", List.of()), checks(true), null, child);
        assertThat(sole(child).message()).contains("collections are addressed");

        var notString = new java.util.ArrayList<ProblemErrors.FieldError>();
        FieldCoercer.canonicalize(entity(field("file", FieldType.FILE)),
                Map.of("file", 42), checks(true), null, notString);
        assertThat(sole(notString).message()).contains("file token");
    }

    @Test
    @DisplayName("toCanonicalText: numerics render as plain strings — no scientific notation in compares")
    void canonicalText() {
        var money = field("amount", FieldType.MONEY);
        assertThat(FieldCoercer.toCanonicalText(money, new BigDecimal("1.2E+3")))
                .isEqualTo("1200");
        assertThat(FieldCoercer.toCanonicalText(field("note", FieldType.TEXT), "x")).isEqualTo("x");
    }
}
