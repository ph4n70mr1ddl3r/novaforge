/**
 * Exact decimal arithmetic (PHASE-2 §7 Annex A): the PLAN.md §1 money rule demands
 * arbitrary-precision decimals, never binary floats — the TS twin mirrors the JVM
 * engine's BigDecimal semantics with a 34-significant-digit HALF_EVEN context
 * (ARCHITECTURE.md §4). Represented as sign × unscaled BigInt × 10^−scale.
 */

export class Decimal {
    constructor(
        /** Absolute unscaled digits (BigInt ≥ 0). */
        readonly digits: bigint,
        /** Negative powers of ten. */
        readonly scale: number,
        /** −1 | 1 (zero carries no sign ambiguity in comparisons). */
        readonly sign: 1 | -1 = 1,
    ) {}

    static readonly ZERO = new Decimal(0n, 0);
    static readonly ONE = new Decimal(1n, 0);

    /** Total parse for input validation: intermediate typing states ("12.", ".",
     * "-") return undefined instead of throwing — a widget must never crash the tree
     * on a half-typed number. */
    static tryParse(source: string): Decimal | undefined {
        try {
            return Decimal.parse(source);
        } catch {
            return undefined;
        }
    }

    /** Parses an exact decimal string (`-12.3400` keeps its trailing zeros). */
    static parse(source: string): Decimal {
        const m = /^([+-]?)(\d+)(?:\.(\d+))?$/.exec(source.trim());
        if (!m) throw new Error(`invalid decimal literal: ${source}`);
        const sign = m[1] === "-" ? -1 : 1;
        const digits = BigInt(m[2]! + (m[3] ?? ""));
        const scale = (m[3] ?? "").length;
        return new Decimal(digits, scale, sign).normalized();
    }

    /** From a JSON number via its shortest round-trip decimal string. */
    static fromNumber(value: number): Decimal {
        if (!Number.isFinite(value)) throw new Error(`not a finite number: ${value}`);
        return Decimal.parse(String(value));
    }

    /** The 34-digit banker's rounding context (mirrors Java MathContext(34, HALF_EVEN)). */
    private static readonly PRECISION = 34;

    get isZero(): boolean {
        return this.digits === 0n;
    }

    private normalized(): Decimal {
        return this.digits === 0n ? new Decimal(0n, 0, 1) : this;
    }

    /** Rounds to ≤34 significant digits, HALF_EVEN. */
    private rounded(inexactTail: boolean = false): Decimal {
        const digitsStr = this.digits.toString();
        if (digitsStr.length <= Decimal.PRECISION) return this;
        const keep = Decimal.PRECISION;
        const head = BigInt(digitsStr.slice(0, keep));
        const tail = digitsStr.slice(keep);
        const firstDropped = tail.charCodeAt(0) - 48;
        const lastKept = digitsStr.charCodeAt(keep - 1) - 48;
        const beyondHalf = BigInt(tail.slice(1)) > 0n;
        // HALF_EVEN: ties (exactly half) round to even the kept last digit. A tail
        // the caller knows is cut off non-terminating (divide's remainder ≠ 0) is
        // strictly MORE than half even when the kept digits read as an exact tie —
        // BigDecimal's MathContext sees the full value and rounds up; so must we.
        const roundUp =
            firstDropped > 5
            || (firstDropped === 5 && (beyondHalf || inexactTail || lastKept % 2 === 1));
        const kept = roundUp ? head + 1n : head;
        const scale = this.scale - tail.length;
        return new Decimal(kept, scale, this.sign).stripTrailingZeros().normalized();
    }

    negate(): Decimal {
        return new Decimal(this.digits, this.scale, this.isZero ? 1 : (-this.sign) as 1 | -1);
    }

    abs(): Decimal {
        return new Decimal(this.digits, this.scale, 1);
    }

    private alignedScale(other: Decimal): [bigint, bigint, number] {
        const scale = Math.max(this.scale, other.scale);
        const shift = (n: bigint, from: number) => n * 10n ** BigInt(scale - from);
        return [shift(this.digits, this.scale), shift(other.digits, other.scale), scale];
    }

    add(other: Decimal): Decimal {
        const [a, b, scale] = this.alignedScale(other);
        const sum =
            this.sign === other.sign ? a + b : a >= b ? a - b : b - a;
        const sign: 1 | -1 = this.sign === other.sign ? this.sign : a >= b ? this.sign : other.sign;
        return new Decimal(sum, scale, sign).rounded().normalized();
    }

    subtract(other: Decimal): Decimal {
        return this.add(other.negate());
    }

    multiply(other: Decimal): Decimal {
        const product = this.digits * other.digits;
        const sign: 1 | -1 = this.sign === other.sign || product === 0n ? 1 : -1;
        return new Decimal(product, this.scale + other.scale, sign).rounded().normalized();
    }

    divide(other: Decimal): Decimal {
        if (other.isZero) throw new Error("division by zero");
        const sign: 1 | -1 = this.sign === other.sign ? 1 : -1;
        let quotient = this.digits / other.digits;
        let remainder = this.digits % other.digits;
        let scale = this.scale - other.scale;
        // Produce guard digits beyond the 34-digit context, then round HALF_EVEN.
        while (remainder !== 0n && digitCount(quotient) < Decimal.PRECISION + 2) {
            const shifted = remainder * 10n;
            quotient = quotient * 10n + shifted / other.digits;
            remainder = shifted % other.digits;
            scale += 1;
        }
        return new Decimal(quotient, scale, sign)
            .rounded(remainder !== 0n)
            .stripTrailingZeros().normalized();
    }

    /** Scales to `places` fractional digits, HALF_EVEN. */
    setScale(places: number): Decimal {
        if (places >= this.scale) {
            return new Decimal(this.digits * 10n ** BigInt(places - this.scale), places, this.sign);
        }
        const drop = this.scale - places;
        const divisor = 10n ** BigInt(drop);
        const quotient = this.digits / divisor;
        const remainder = this.digits % divisor;
        const half = divisor / 2n;
        let kept = quotient;
        if (remainder > half || (remainder === half && quotient % 2n === 1n)) {
            kept = quotient + 1n;
        }
        return new Decimal(kept, places, this.sign).normalized();
    }

    stripTrailingZeros(): Decimal {
        if (this.digits === 0n) return new Decimal(0n, 0, 1);
        let digits = this.digits;
        let scale = this.scale;
        while (scale > 0 && digits % 10n === 0n) {
            digits /= 10n;
            scale -= 1;
        }
        return new Decimal(digits, scale, this.sign);
    }

    /** Numeric equality regardless of scale (Java BigDecimal.compareTo == 0). */
    compareTo(other: Decimal): number {
        const [a, b] = this.alignedScale(other);
        const sa = this.isZero ? 0n : a * BigInt(this.sign);
        const sb = other.isZero ? 0n : b * BigInt(other.sign);
        return sa < sb ? -1 : sa > sb ? 1 : 0;
    }

    equals(other: Decimal): boolean {
        return this.compareTo(other) === 0;
    }

    /** Whole-number check after trailing-zero strip (date day counts). */
    isIntegral(): boolean {
        return this.stripTrailingZeros().scale <= 0;
    }

    toNumber(): number {
        return Number(this.toString());
    }

    /** Plain decimal string (trailing zeros preserved, like BigDecimal.toString). */
    toString(): string {
        const neg = this.sign < 0 && !this.isZero;
        if (this.scale === 0) return (neg ? "-" : "") + this.digits.toString();
        if (this.scale < 0) {
            return (neg ? "-" : "") + this.digits.toString() + "0".repeat(-this.scale);
        }
        const digits = this.digits.toString().padStart(this.scale + 1, "0");
        const int = digits.slice(0, digits.length - this.scale);
        const frac = digits.slice(digits.length - this.scale);
        return (neg ? "-" : "") + int + "." + frac;
    }
}

function digitCount(value: bigint): number {
    return value.toString().length;
}
