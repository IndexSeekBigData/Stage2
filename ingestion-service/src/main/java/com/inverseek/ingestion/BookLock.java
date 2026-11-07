package com.inverseek.ingestion;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Host-local OS lock. Files remain on disk: deleting them would allow locking different inodes. */
final class BookLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private BookLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static BookLock acquire(Path datalake, int bookId) throws IOException {
        Path directory = datalake.resolve(".locks");
        Files.createDirectories(directory);
        FileChannel channel = FileChannel.open(directory.resolve(bookId + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Book lock interrupted");
                try {
                    FileLock lock = channel.tryLock();
                    if (lock != null) return new BookLock(channel, lock);
                } catch (OverlappingFileLockException ignored) {
                    // Another IngestionService in this JVM owns the same OS lock.
                }
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Book lock interrupted");
                }
            }
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
