package com.novaforge.runtime.engine.query;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.common.error.ProblemErrors;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * The keyset (seek) cursor — PHASE-1 §5's pinned landing: an opaque, self-describing
 * token the list response returns as {@code nextAfter} on any page that came back
 * full, and the next request passes as {@code page.after} to seek past the rows the
 * previous page already served instead of scanning-and-discarding an offset.
 *
 * <p>Shape (base64url of compact JSON — an encoding, never cryptography):
 * {@code {"v":1,"sort":[{"f":field,"d":"asc|desc"}…],"pos":[value…]}} — the version,
 * the <b>effective sort contract</b> the cursor was minted under (the declared sorts,
 * then the engine's {@code id} tiebreaker — a sortless list's effective order is
 * {@code id asc}), and the last served row's position under that contract. A cursor
 * minted for a different sort rejects {@code VALIDATION_FAILED}, as does a garbled
 * one — the contract check is what keeps a deep page's seek conjunct consistent with
 * the ORDER BY it must agree with. Values ride as JSON scalars (numbers exact through
 * a BigDecimal read — the platform's money rule holds inside the cursor too).</p>
 */
public record SeekCursor(List<QueryModel.Sort> sort, List<Object> position) {

    /** The cursor format version — bumped only for a breaking shape change. */
    static final int VERSION = 1;

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    public SeekCursor {
        sort = List.copyOf(sort);
        position = List.copyOf(position);
    }

    /**
     * The effective sort contract: the declared sorts, then the engine's {@code id}
     * tiebreaker (skipped when a declared sort already names {@code id} — the lowered
     * ORDER BY appends the same tiebreaker, and the contract must match it exactly).
     */
    public static List<QueryModel.Sort> effectiveSort(List<QueryModel.Sort> declared) {
        List<QueryModel.Sort> effective = new ArrayList<>(declared);
        if (effective.stream().noneMatch(sort -> sort.field().equals("id"))) {
            effective.add(new QueryModel.Sort("id", QueryModel.SortDir.asc));
        }
        return List.copyOf(effective);
    }

    /** Mints the {@code nextAfter} token for a full page's last row under {@code sort}. */
    public static String encode(List<QueryModel.Sort> sort, List<Object> position) {
        Map<String, Object> cursor = new LinkedHashMap<>();
        cursor.put("v", VERSION);
        List<Map<String, String>> sorts = new ArrayList<>();
        for (QueryModel.Sort key : sort) {
            sorts.add(Map.of("f", key.field(), "d", key.dir().name()));
        }
        cursor.put("sort", sorts);
        cursor.put("pos", position);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MAPPER.writeValueAsString(cursor).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes and validates a request's {@code after} against the effective sort the
     * request declares: a garbled token, a wrong version, a mismatched sort contract,
     * or a position arity that disagrees with its own contract rejects
     * {@code VALIDATION_FAILED} at the door — never downstream as a SQL or cast error.
     */
    public static SeekCursor decode(String token, List<QueryModel.Sort> expectedSort) {
        Map<String, Object> cursor;
        try {
            String json = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            cursor = MAPPER.readValue(json, Map.class);
        } catch (RuntimeException malformed) {
            throw reject("page.after", "garbled seek cursor", token);
        }
        if (cursor == null || !Integer.valueOf(VERSION).equals(asInt(cursor.get("v")))) {
            throw reject("page.after", "unsupported cursor version", token);
        }
        if (!(cursor.get("sort") instanceof List<?> sortNodes) || sortNodes.isEmpty()) {
            throw reject("page.after", "cursor carries no sort contract", token);
        }
        if (!(cursor.get("pos") instanceof List<?> position)) {
            throw reject("page.after", "cursor carries no position", token);
        }
        List<QueryModel.Sort> sort = new ArrayList<>();
        for (Object node : sortNodes) {
            if (!(node instanceof Map<?, ?> entry)) {
                throw reject("page.after", "cursor sort contract is malformed", token);
            }
            String field = String.valueOf(entry.get("f"));
            String dir = String.valueOf(entry.get("d"));
            QueryModel.SortDir direction;
            try {
                direction = QueryModel.SortDir.valueOf(dir);
            } catch (IllegalArgumentException bad) {
                throw reject("page.after", "cursor sort contract is malformed", token);
            }
            sort.add(new QueryModel.Sort(field, direction));
        }
        if (!sort.equals(expectedSort)) {
            throw reject("page.after",
                    "cursor was minted for a different sort — declare the same sort "
                            + SeekCursor.describe(expectedSort), token);
        }
        if (position.size() != sort.size()) {
            throw reject("page.after", "cursor position disagrees with its sort contract", token);
        }
        return new SeekCursor(sort, new ArrayList<>(position));
    }

    /** The wire label of an effective sort contract — the rejection names what the
     *  request must declare for the cursor to apply. */
    static String describe(List<QueryModel.Sort> sort) {
        List<String> keys = new ArrayList<>();
        for (QueryModel.Sort key : sort) {
            keys.add(key.field() + " " + key.dir().name());
        }
        return "[" + String.join(", ", keys) + "]";
    }

    private static Integer asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private static PlatformException reject(String field, String message, Object rejected) {
        throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                field + ": " + message,
                ProblemErrors.of(new ProblemErrors.FieldError(field, message, rejected)));
    }
}
