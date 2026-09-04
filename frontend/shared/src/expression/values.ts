import { Decimal } from "./decimal.ts";

/**
 * expr/v1 value model (PHASE-2 §7 Annex A): the TS twin of the JVM engine's
 * evaluation domain. Dates/instants are tagged so browser evaluation never leaks
 * host time zones — everything stays canonical ISO.
 */

export interface DateValue {
    readonly $date: string; // canonical YYYY-MM-DD (UTC)
}

export interface InstantValue {
    readonly $instant: string; // canonical ISO-8601 UTC instant
}

export function dateValue(iso: string): DateValue {
    return { $date: iso };
}

export function instantValue(iso: string): InstantValue {
    return { $instant: iso };
}

export function isDate(value: unknown): value is DateValue {
    return typeof value === "object" && value !== null && "$date" in value;
}

export function isInstant(value: unknown): value is InstantValue {
    return typeof value === "object" && value !== null && "$instant" in value;
}

export type ExpressionValue =
    | Decimal
    | string
    | boolean
    | null
    | DateValue
    | InstantValue
    | ExpressionValue[];

/** Thrown for every parse/compile/evaluation failure — mirrors ExpressionException. */
export class ExpressionError extends Error {}

const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
const INSTANT_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/;

export function parseDate(iso: string): DateValue {
    if (!DATE_PATTERN.test(iso) || Number.isNaN(Date.parse(iso + "T00:00:00Z"))) {
        throw new ExpressionError(`invalid date literal: ${iso}`);
    }
    return dateValue(iso);
}

export function parseInstant(iso: string): InstantValue {
    if (!INSTANT_PATTERN.test(iso)) {
        throw new ExpressionError(`invalid instant literal: ${iso}`);
    }
    return instantValue(iso);
}

/** Days between two canonical dates (b - a, in whole days). */
export function daysBetween(a: DateValue, b: DateValue): Decimal {
    return new Decimal(BigInt(dayNumber(b.$date) - dayNumber(a.$date)), 0);
}

/**
 * The supported calendar range in day numbers — the JVM LocalDate proleptic range
 * (years −999,999,999 … +999,999,999), the band inside which both engines answer.
 * Beyond it the JVM's plusDays throws and the date arithmetic must reject with it;
 * the old unguarded read fabricated year-billions date strings where the JVM
 * rejects (a day count between the calendar edge and the float-safe bound —
 * entryDate + 100000000000000 — slipped straight past).
 */
const CALENDAR_MIN_DAY = -365243219162;
const CALENDAR_MAX_DAY = 365241780471;

export function addDays(value: DateValue, days: number): DateValue {
    const total = dayNumber(value.$date) + days;
    if (total < CALENDAR_MIN_DAY || total > CALENDAR_MAX_DAY) {
        throw new ExpressionError("date arithmetic is outside the supported calendar range");
    }
    return dateValue(fromDayNumber(total));
}

/** Proleptic-Gregorian day number (UTC-safe arithmetic on canonical dates). */
function dayNumber(iso: string): number {
    const [y, m, d] = iso.split("-").map(Number) as [number, number, number];
    // Howard Hinnant's days_from_civil.
    const yy = m <= 2 ? y - 1 : y;
    const era = Math.floor(yy / 400);
    const yoe = yy - era * 400;
    const doy = Math.floor((153 * (m + (m > 2 ? -3 : 9)) + 2) / 5) + d - 1;
    const doe = yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy;
    return era * 146097 + doe - 719468;
}

function fromDayNumber(day: number): string {
    // civil_from_days (Howard Hinnant), inverse of dayNumber.
    const z = day + 719468;
    const era = Math.floor(z / 146097);
    const doe = z - era * 146097;
    const yoe = Math.floor((doe - Math.floor(doe / 1460) + Math.floor(doe / 36524) - Math.floor(doe / 146096)) / 365);
    const y = yoe + era * 400;
    const doy = doe - (365 * yoe + Math.floor(yoe / 4) - Math.floor(yoe / 100));
    const mp = Math.floor((5 * doy + 2) / 153);
    const d = doy - Math.floor((153 * mp + 2) / 5) + 1;
    const m = mp + (mp < 10 ? 3 : -9);
    const year = m <= 2 ? y + 1 : y;
    return `${String(year).padStart(4, "0")}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}`;
}

/** Whole seconds + sub-second for canonical instants (compare without Date parsing). */
export function instantParts(iso: string): { seconds: number; nanos: number } {
    const match = /^(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z$/.exec(iso);
    if (!match) throw new ExpressionError(`invalid instant: ${iso}`);
    const date = match[1]!;
    const h = match[2]!;
    const m = match[3]!;
    const s = match[4]!;
    const frac = match[5];
    const seconds = dayNumber(date) * 86400 + Number(h) * 3600 + Number(m) * 60 + Number(s);
    return { seconds, nanos: frac ? Number(frac.padEnd(9, "0")) : 0 };
}

/** The UTC calendar date of a canonical instant (clock-governed today()). */
export function utcDateOfInstant(iso: string): DateValue {
    return dateValue(iso.slice(0, 10));
}
