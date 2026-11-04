package com.inverseek.ingestion;

/** Source of raw Project Gutenberg books. Separated from the service so tests can replace it. */
public interface BookSource {

    /**
     * Returns the full plain-text file of a book.
     *
     * @throws BookNotFoundException if the source has no plain-text file for this id
     * @throws SourceUnavailableException if the source failed in a way worth retrying
     */
    String fetch(int bookId);
}
