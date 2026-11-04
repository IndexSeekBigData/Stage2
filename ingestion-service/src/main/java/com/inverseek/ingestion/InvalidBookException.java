package com.inverseek.ingestion;

/** The downloaded file is not a Gutenberg book with recognisable START/END markers. Not retryable. */
public class InvalidBookException extends RuntimeException {
    public InvalidBookException(String message) {
        super(message);
    }
}
