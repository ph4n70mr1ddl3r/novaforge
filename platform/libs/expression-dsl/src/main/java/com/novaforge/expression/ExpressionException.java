package com.novaforge.expression;

/** Parse, compile-check, and evaluation failures — all carry position context when known. */
public class ExpressionException extends RuntimeException {

    public ExpressionException(String message) {
        super(message);
    }

    public ExpressionException(String message, Throwable cause) {
        super(message, cause);
    }
}
