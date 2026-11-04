package com.inverseek.ingestion;

/**
 * Completion marker of one ingested book, stored as {@code {id}.success.json} next to its files.
 * Paths are relative to the datalake root and always use forward slashes, so any service on any
 * operating system can resolve them.
 */
public record BookRecord(
        int bookId,
        String headerPath,
        String bodyPath,
        long headerBytes,
        long bodyBytes,
        String bodySha256,
        String ingestedAt) {

    /** Partition-relative prefix shared by the three files, e.g. {@code 20261104/14/1342}. */
    public String path() {
        return headerPath.substring(0, headerPath.length() - ".header.txt".length());
    }
}
