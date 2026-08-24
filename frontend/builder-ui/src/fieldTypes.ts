import type { FieldType } from "@novaforge/shared";

/** The v1 field-type set (ARCHITECTURE.md §3) — the type picker's vocabulary. */
export const FIELD_TYPES: readonly FieldType[] = [
  "text", "longText", "richText", "enum", "boolean", "int", "long", "decimal",
  "date", "datetime", "time", "uuid", "email", "phone", "url", "json",
  "lookup", "child", "m2m", "file", "money",
] as const;
