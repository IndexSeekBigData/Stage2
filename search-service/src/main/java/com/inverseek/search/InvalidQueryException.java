package com.inverseek.search;

public final class InvalidQueryException extends RuntimeException {
    private final String code;

    public InvalidQueryException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
