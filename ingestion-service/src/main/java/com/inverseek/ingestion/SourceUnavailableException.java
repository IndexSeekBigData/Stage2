package com.inverseek.ingestion;

/** Temporary failure talking to Gutenberg (network error, timeout, 5xx). The caller may retry. */
public class SourceUnavailableException extends RuntimeException {
    private final boolean timeout;

    public SourceUnavailableException(String message, boolean timeout, Throwable cause) {
        super(message, cause);
        this.timeout = timeout;
    }

    public boolean isTimeout() {
        return timeout;
    }
}
