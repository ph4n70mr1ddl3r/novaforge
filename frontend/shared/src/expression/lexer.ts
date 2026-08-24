import { Decimal } from "./decimal.ts";
import { ExpressionError } from "./values.ts";

/**
 * expr/v1 tokenizer — the TS twin of the JVM Lexer (PHASE-2 Annex A): literals,
 * identifiers, operators, punctuation. `${…}` and bitwise operators are rejected
 * here (template interpolation is host-side; the scalar grammar has no bitwise ops).
 */

export interface Token {
    kind: string;
    value?: string | Decimal;
    position: number;
}

const TWO_CHAR = new Set(["==", "!=", "<=", ">=", "&&", "||"]);
const SINGLE = new Set(["<", ">", "+", "-", "*", "/", "!", "(", ")", ",", "."]);

export function lex(source: string): Token[] {
    const tokens: Token[] = [];
    let index = 0;
    const at = (i: number) => source.charAt(i);
    while (index < source.length) {
        const c = at(index);
        if (/\s/.test(c)) {
            index++;
            continue;
        }
        const start = index;
        if (/[A-Za-z_]/.test(c)) {
            index++;
            while (index < source.length && /[A-Za-z0-9_]/.test(at(index))) index++;
            tokens.push({ kind: "ident", value: source.slice(start, index), position: start });
            continue;
        }
        if (/[0-9]/.test(c)) {
            let decimal = false;
            index++;
            while (index < source.length) {
                const d = at(index);
                if (/[0-9]/.test(d)) {
                    index++;
                } else if (d === "." && !decimal && /[0-9]/.test(at(index + 1))) {
                    decimal = true;
                    index++;
                } else {
                    break;
                }
            }
            // Exact decimal literals — arbitrary precision, never binary float.
            tokens.push({
                kind: "number",
                value: Decimal.parse(source.slice(start, index)),
                position: start,
            });
            continue;
        }
        if (c === "'") {
            index++;
            let value = "";
            while (index < source.length && at(index) !== "'") {
                value += at(index);
                index++;
            }
            if (index >= source.length) {
                throw new ExpressionError(`unterminated string literal at position ${start}`);
            }
            index++;
            tokens.push({ kind: "string", value, position: start });
            continue;
        }
        const two = source.slice(index, index + 2);
        if (TWO_CHAR.has(two)) {
            tokens.push({ kind: two, position: start });
            index += 2;
            continue;
        }
        if (SINGLE.has(c)) {
            tokens.push({ kind: c, position: start });
            index++;
            continue;
        }
        throw new ExpressionError(`unexpected character '${c}' at position ${start}`);
    }
    tokens.push({ kind: "eof", position: index });
    return tokens;
}
