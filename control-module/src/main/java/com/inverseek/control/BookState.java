package com.inverseek.control;

public enum BookState {
    PENDING, DOWNLOADING, DOWNLOADED, INDEXING, INDEXED, NOT_FOUND, FAILED;

    public boolean isActive() {
        return this == PENDING || this == DOWNLOADING || this == DOWNLOADED || this == INDEXING;
    }
}
