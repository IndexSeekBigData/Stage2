package com.inverseek.search;

public final class IndexUnavailableException extends RuntimeException {
    private final String code;

    public IndexUnavailableException(String code, String message) {
        this(code, message, null);
    }

    public IndexUnavailableException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
