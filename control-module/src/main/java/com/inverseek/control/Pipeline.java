package com.inverseek.control;

/** The calls the workers make to the services. Separated so failures map to one small set of outcomes. */
public interface Pipeline {

    enum Kind {
        /** The service completed the work. */
        SUCCESS,
        /** The book does not exist or is not a usable book: final. */
        NOT_FOUND,
        /** The request itself was rejected: final. */
        REJECTED,
        /** The attempt failed: consumes an attempt and may be retried. */
        RETRYABLE,
        /** The service could not be reached: nothing was attempted. */
        UNREACHABLE
    }

    record Outcome(Kind kind, String detail) {}

    /** On success, the detail is the book's path in the datalake. */
    Outcome ingest(int bookId);

    Outcome index(int bookId);
}
