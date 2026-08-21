package com.novaforge.expression;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** expr/v1 tokenizer (PHASE-2 Annex A): literals, identifiers, operators, punctuation. */
final class Lexer {

    record Token(String kind, Object value, int position) {

        static Token of(String kind, int position) {
            return new Token(kind, null, position);
        }

        static Token of(String kind, Object value, int position) {
            return new Token(kind, value, position);
        }
    }

    private final String source;
    private final List<Token> tokens = new ArrayList<>();
    private int index;

    Lexer(String source) {
        this.source = source == null ? "" : source;
    }

    List<Token> lex() {
        while (index < source.length()) {
            char c = source.charAt(index);
            if (Character.isWhitespace(c)) {
                index++;
                continue;
            }
            int start = index;
            if (Character.isLetter(c) || c == '_') {
                lexIdentifier(start);
            } else if (Character.isDigit(c)) {
                lexNumber(start);
            } else if (c == '\'') {
                lexString(start);
            } else {
                lexOperator(start);
            }
        }
        tokens.add(Token.of("eof", index));
        return tokens;
    }

    private void lexIdentifier(int start) {
        while (index < source.length()
                && (Character.isLetterOrDigit(source.charAt(index)) || source.charAt(index) == '_')) {
            index++;
        }
        tokens.add(Token.of("ident", source.substring(start, index), start));
    }

    private void lexNumber(int start) {
        boolean decimal = false;
        while (index < source.length()) {
            char c = source.charAt(index);
            if (Character.isDigit(c)) {
                index++;
            } else if (c == '.' && index + 1 < source.length()
                    && Character.isDigit(source.charAt(index + 1)) && !decimal) {
                decimal = true;
                index++;
            } else {
                break;
            }
        }
        // Exact decimal literals — arbitrary precision, never binary float (Annex A).
        tokens.add(Token.of("number", new BigDecimal(source.substring(start, index)), start));
    }

    private void lexString(int start) {
        index++;   // opening quote
        StringBuilder value = new StringBuilder();
        while (index < source.length() && source.charAt(index) != '\'') {
            value.append(source.charAt(index));
            index++;
        }
        if (index >= source.length()) {
            throw new ExpressionException("unterminated string literal at position " + start);
        }
        index++;   // closing quote
        tokens.add(Token.of("string", value.toString(), start));
    }

    private void lexOperator(int start) {
        char c = source.charAt(index);
        String two = index + 1 < source.length()
                ? source.substring(index, index + 2) : null;
        if (two != null && (two.equals("==") || two.equals("!=") || two.equals("<=")
                || two.equals(">=") || two.equals("&&") || two.equals("||"))) {
            tokens.add(Token.of(two, start));
            index += 2;
            return;
        }
        switch (c) {
            case '<', '>', '+', '-', '*', '/', '!', '(', ')', ',', '.' -> {
                tokens.add(Token.of(String.valueOf(c), start));
                index++;
            }
            default -> throw new ExpressionException(
                    "unexpected character '" + c + "' at position " + start);
        }
    }
}
