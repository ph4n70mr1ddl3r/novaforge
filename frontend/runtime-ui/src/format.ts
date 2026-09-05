/**
 * Server timestamps arrive as ISO-8601 instant strings; rendering them raw put
 * machine noise in every inbox/notifications row. The localized form rides a
 * <time dateTime> so the exact instant stays machine-readable, and unparseable
 * values fall through verbatim rather than rendering "Invalid Date".
 */
export function formatWhen(value: unknown): string {
    const raw = value == null ? "" : String(value);
    const when = new Date(raw);
    if (raw === "" || Number.isNaN(when.getTime())) {
        return raw;
    }
    return when.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}
