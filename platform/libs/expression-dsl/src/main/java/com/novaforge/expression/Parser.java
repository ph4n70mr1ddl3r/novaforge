package com.novaforge.expression;

import com.novaforge.expression.Expression.Node;
import com.novaforge.expression.Lexer.Token;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Recursive-descent parser for expr/v1. Precedence (low → high): {@code ||},
 * {@code &&}, comparisons, {@code in}, additive, multiplicative, unary, postfix
 * method calls, primary.
 */
final class Parser {

    private static final Set<String> FUNCTIONS = Set.of(
            "today", "now", "date", "datetime", "size", "abs", "round", "min", "max",
            "upper", "lower", "trim", "length", "contains", "startsWith");

    private static final Set<String> KEYWORDS = Set.of("true", "false", "null", "in");

    private final List<Token> tokens;
    private int index;

    Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    Node parseExpression() {
        return parseOr();
    }

    boolean atEnd() {
        return peek().kind().equals("eof");
    }

    int position() {
        return peek().position();
    }

    private Node parseOr() {
        Node left = parseAnd();
        while (peek().kind().equals("||")) {
            next();
            left = new Node.Binary("||", left, parseAnd());
        }
        return left;
    }

    private Node parseAnd() {
        Node left = parseComparison();
        while (peek().kind().equals("&&")) {
            next();
            left = new Node.Binary("&&", left, parseComparison());
        }
        return left;
    }

    private Node parseComparison() {
        Node left = parseMembership();
        String kind = peek().kind();
        while (kind.equals("==") || kind.equals("!=") || kind.equals("<")
                || kind.equals("<=") || kind.equals(">") || kind.equals(">=")) {
            next();
            left = new Node.Binary(kind, left, parseMembership());
            kind = peek().kind();
        }
        return left;
    }

    private Node parseMembership() {
        Node left = parseAdditive();
        if (peek().kind().equals("ident") && "in".equals(peek().value())) {
            next();
            expect("(");
            List<Node> items = new ArrayList<>();
            items.add(parseExpression());
            while (peek().kind().equals(",")) {
                next();
                items.add(parseExpression());
            }
            expect(")");
            return new Node.Binary("in", left, new Node.ListLiteral(items));
        }
        return left;
    }

    private Node parseAdditive() {
        Node left = parseMultiplicative();
        while (peek().kind().equals("+") || peek().kind().equals("-")) {
            String op = next().kind();
            left = new Node.Binary(op, left, parseMultiplicative());
        }
        return left;
    }

    private Node parseMultiplicative() {
        Node left = parseUnary();
        while (peek().kind().equals("*") || peek().kind().equals("/")) {
            String op = next().kind();
            left = new Node.Binary(op, left, parseUnary());
        }
        return left;
    }

    private Node parseUnary() {
        if (peek().kind().equals("!")) {
            next();
            return new Node.Unary("!", parseUnary());
        }
        if (peek().kind().equals("-")) {
            next();
            return new Node.Unary("-", parseUnary());
        }
        return parsePostfix();
    }

    private Node parsePostfix() {
        Node node = parsePrimary();
        while (peek().kind().equals(".")) {
            next();
            Token name = expectKind("ident");
            // `.name(...)` is a method call; `.name` continues a relationship path.
            if (peek().kind().equals("(")) {
                next();
                List<Node> args = new ArrayList<>();
                if (!peek().kind().equals(")")) {
                    args.add(parseExpression());
                    while (peek().kind().equals(",")) {
                        next();
                        args.add(parseExpression());
                    }
                }
                expect(")");
                node = new Node.Method(node, String.valueOf(name.value()), args);
            } else if (node instanceof Node.Reference reference) {
                node = new Node.Reference(reference.path() + "." + name.value());
            } else {
                throw new ExpressionException(
                        "path continuation is only valid on references (position " + name.position() + ")");
            }
        }
        return node;
    }

    private Node parsePrimary() {
        Token token = peek();
        return switch (token.kind()) {
            case "number", "string" -> {
                next();
                yield new Node.Literal(token.value());
            }
            case "(" -> {
                next();
                Node inner = parseExpression();
                expect(")");
                yield inner;
            }
            case "ident" -> {
                next();
                String name = String.valueOf(token.value());
                if (name.equals("true")) {
                    yield new Node.Literal(Boolean.TRUE);
                }
                if (name.equals("false")) {
                    yield new Node.Literal(Boolean.FALSE);
                }
                if (name.equals("null")) {
                    yield new Node.Literal(null);
                }
                if (KEYWORDS.contains(name)) {
                    throw new ExpressionException(
                            "keyword '" + name + "' cannot be used as a reference (position " + token.position() + ")");
                }
                if (peek().kind().equals("(")) {
                    if (!FUNCTIONS.contains(name)) {
                        throw new ExpressionException(
                                "unknown function '" + name + "' (position " + token.position() + ")");
                    }
                    next();
                    List<Node> args = new ArrayList<>();
                    if (!peek().kind().equals(")")) {
                        args.add(parseExpression());
                        while (peek().kind().equals(",")) {
                            next();
                            args.add(parseExpression());
                        }
                    }
                    expect(")");
                    yield new Node.Call(name, args);
                }
                // Bare identifiers are host-bound values; parsePostfix extends dot-chains.
                yield new Node.Reference(name);
            }
            default -> throw new ExpressionException(
                    "unexpected token '" + token.kind() + "' at position " + token.position());
        };
    }

    private Token peek() {
        return tokens.get(index);
    }

    private Token next() {
        return tokens.get(index++);
    }

    private void expect(String kind) {
        if (!peek().kind().equals(kind)) {
            throw new ExpressionException(
                    "expected '" + kind + "' but found '" + peek().kind() + "' at position " + peek().position());
        }
        next();
    }

    private Token expectKind(String kind) {
        if (!peek().kind().equals(kind)) {
            throw new ExpressionException(
                    "expected " + kind + " but found '" + peek().kind() + "' at position " + peek().position());
        }
        return next();
    }
}
