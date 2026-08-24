import { lex, type Token } from "./lexer.ts";
import { ExpressionError } from "./values.ts";

/**
 * expr/v1 recursive-descent parser — the TS twin of the JVM Parser. Precedence
 * (low → high): `||`, `&&`, comparisons, `in`, additive, multiplicative, unary,
 * postfix method calls, primary. The function set is closed (Annex A); unknown
 * functions and keywords-as-references reject at parse.
 */

export type Node =
    | { readonly kind: "literal"; readonly value: unknown }
    | { readonly kind: "reference"; readonly path: string }
    | { readonly kind: "unary"; readonly op: "!" | "-"; readonly operand: Node }
    | { readonly kind: "binary"; readonly op: string; readonly left: Node; readonly right: Node }
    | { readonly kind: "call"; readonly name: string; readonly args: readonly Node[] }
    | { readonly kind: "list"; readonly items: readonly Node[] }
    | { readonly kind: "method"; readonly target: Node; readonly name: string; readonly args: readonly Node[] };

const FUNCTIONS = new Set([
    "today", "now", "date", "datetime", "size", "abs", "round", "min", "max",
    "upper", "lower", "trim", "length", "contains", "startsWith",
]);

const KEYWORDS = new Set(["true", "false", "null", "in"]);

export function parse(source: string): Node {
    const tokens = lex(source);
    let index = 0;

    const peek = (): Token => tokens[index] as Token;
    const next = (): Token => tokens[index++] as Token;
    const expect = (kind: string): void => {
        if (peek().kind !== kind) {
            throw new ExpressionError(
                `expected '${kind}' but found '${peek().kind}' at position ${peek().position}`,
            );
        }
        next();
    };
    const expectIdent = (): Token => {
        if (peek().kind !== "ident") {
            throw new ExpressionError(
                `expected ident but found '${peek().kind}' at position ${peek().position}`,
            );
        }
        return next();
    };

    function parseExpression(): Node {
        return parseOr();
    }

    function parseOr(): Node {
        let left = parseAnd();
        while (peek().kind === "||") {
            next();
            left = { kind: "binary", op: "||", left, right: parseAnd() };
        }
        return left;
    }

    function parseAnd(): Node {
        let left = parseComparison();
        while (peek().kind === "&&") {
            next();
            left = { kind: "binary", op: "&&", left, right: parseComparison() };
        }
        return left;
    }

    function parseComparison(): Node {
        let left = parseMembership();
        const comparisons = new Set(["==", "!=", "<", "<=", ">", ">="]);
        while (comparisons.has(peek().kind)) {
            const op = next().kind;
            left = { kind: "binary", op, left, right: parseMembership() };
        }
        return left;
    }

    function parseMembership(): Node {
        const left = parseAdditive();
        const head = peek();
        if (head.kind === "ident" && head.value === "in") {
            next();
            expect("(");
            const items: Node[] = [parseExpression()];
            while (peek().kind === ",") {
                next();
                items.push(parseExpression());
            }
            expect(")");
            return { kind: "binary", op: "in", left, right: { kind: "list", items } };
        }
        return left;
    }

    function parseAdditive(): Node {
        let left = parseMultiplicative();
        while (peek().kind === "+" || peek().kind === "-") {
            const op = next().kind;
            left = { kind: "binary", op, left, right: parseMultiplicative() };
        }
        return left;
    }

    function parseMultiplicative(): Node {
        let left = parseUnary();
        while (peek().kind === "*" || peek().kind === "/") {
            const op = next().kind;
            left = { kind: "binary", op, left, right: parseUnary() };
        }
        return left;
    }

    function parseUnary(): Node {
        if (peek().kind === "!") {
            next();
            return { kind: "unary", op: "!", operand: parseUnary() };
        }
        if (peek().kind === "-") {
            next();
            return { kind: "unary", op: "-", operand: parseUnary() };
        }
        return parsePostfix();
    }

    function parsePostfix(): Node {
        let node = parsePrimary();
        while (peek().kind === ".") {
            next();
            const name = expectIdent();
            if (peek().kind === "(") {
                next();
                const args: Node[] = [];
                if (peek().kind !== ")") {
                    args.push(parseExpression());
                    while (peek().kind === ",") {
                        next();
                        args.push(parseExpression());
                    }
                }
                expect(")");
                node = { kind: "method", target: node, name: String(name.value), args };
            } else if (node.kind === "reference") {
                node = { kind: "reference", path: `${node.path}.${String(name.value)}` };
            } else {
                throw new ExpressionError(
                    `path continuation is only valid on references (position ${name.position})`,
                );
            }
        }
        return node;
    }

    function parsePrimary(): Node {
        const token = peek();
        switch (token.kind) {
            case "number":
            case "string":
                next();
                return { kind: "literal", value: token.value };
            case "(": {
                next();
                const inner = parseExpression();
                expect(")");
                return inner;
            }
            case "ident": {
                next();
                const name = String(token.value);
                if (name === "true") return { kind: "literal", value: true };
                if (name === "false") return { kind: "literal", value: false };
                if (name === "null") return { kind: "literal", value: null };
                if (KEYWORDS.has(name)) {
                    throw new ExpressionError(
                        `keyword '${name}' cannot be used as a reference (position ${token.position})`,
                    );
                }
                if (peek().kind === "(") {
                    if (!FUNCTIONS.has(name)) {
                        throw new ExpressionError(
                            `unknown function '${name}' (position ${token.position})`,
                        );
                    }
                    next();
                    const args: Node[] = [];
                    if (peek().kind !== ")") {
                        args.push(parseExpression());
                        while (peek().kind === ",") {
                            next();
                            args.push(parseExpression());
                        }
                    }
                    expect(")");
                    return { kind: "call", name, args };
                }
                return { kind: "reference", path: name };
            }
            default:
                throw new ExpressionError(
                    `unexpected token '${token.kind}' at position ${token.position}`,
                );
        }
    }

    const root = parseExpression();
    if (peek().kind !== "eof") {
        throw new ExpressionError(`unexpected trailing input at position ${peek().position}`);
    }
    return root;
}
