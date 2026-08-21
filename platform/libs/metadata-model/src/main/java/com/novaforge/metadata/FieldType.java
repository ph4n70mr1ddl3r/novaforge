package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The v1 field-type set (ARCHITECTURE.md §3), 21 types. Wire names are the lowercase
 * forms authored in definitions; unknown types are rejected at parse (schema v0 rule:
 * field types restricted to this set).
 */
public enum FieldType {

    @JsonProperty("text")
    TEXT("text", false),
    @JsonProperty("longText")
    LONG_TEXT("longText", false),
    @JsonProperty("richText")
    RICH_TEXT("richText", false),
    @JsonProperty("enum")
    ENUM("enum", false),
    @JsonProperty("boolean")
    BOOLEAN("boolean", false),
    @JsonProperty("int")
    INT("int", false),
    @JsonProperty("long")
    LONG("long", false),
    @JsonProperty("decimal")
    DECIMAL("decimal", false),
    @JsonProperty("date")
    DATE("date", false),
    @JsonProperty("datetime")
    DATETIME("datetime", false),
    @JsonProperty("time")
    TIME("time", false),
    @JsonProperty("uuid")
    UUID("uuid", false),
    @JsonProperty("email")
    EMAIL("email", false),
    @JsonProperty("phone")
    PHONE("phone", false),
    @JsonProperty("url")
    URL("url", false),
    @JsonProperty("json")
    JSON("json", false),
    @JsonProperty("lookup")
    LOOKUP("lookup", true),
    @JsonProperty("child")
    CHILD("child", true),
    @com.fasterxml.jackson.annotation.JsonProperty("m2m")
    M2M("m2m", true),
    @JsonProperty("file")
    FILE("file", false),
    @JsonProperty("money")
    MONEY("money", false);

    private static final Map<String, FieldType> BY_WIRE_NAME = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(FieldType::wireName, Function.identity()));

    private final String wireName;
    private final boolean relationshipLike;

    FieldType(String wireName, boolean relationshipLike) {
        this.wireName = wireName;
        this.relationshipLike = relationshipLike;
    }

    public String wireName() {
        return wireName;
    }

    /** True when the type carries a {@code target} pointing at another entity. */
    public boolean relationshipLike() {
        return relationshipLike;
    }

    public static Optional<FieldType> fromWireName(String wireName) {
        return Optional.ofNullable(BY_WIRE_NAME.get(wireName));
    }

    /** Textual family used by query lowering (contains operator is text-only, §5). */
    public boolean textual() {
        return this == TEXT || this == LONG_TEXT || this == RICH_TEXT || this == EMAIL
                || this == PHONE || this == URL;
    }

    /** Numeric family for aggregate lowering. */
    public boolean numeric() {
        return this == INT || this == LONG || this == DECIMAL || this == MONEY;
    }
}
