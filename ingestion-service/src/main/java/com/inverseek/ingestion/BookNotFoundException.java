package com.inverseek.ingestion;

/** The book does not exist, or it has no usable plain text. Retrying will not help. */
public class BookNotFoundException extends RuntimeException {
    public BookNotFoundException(String message) {
        super(message);
    }
}
