package com.inverseek.indexing;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DatalakeReader {
    private static final Logger LOG = LoggerFactory.getLogger(DatalakeReader.class);
    private static final Gson GSON = new Gson();
    private DatalakeReader() {}

    public record BookFiles(String bookId, Path headerPath, Path bodyPath, Completion marker) {}
    public record Completion(Integer bookId, String headerPath, String bodyPath,
                             Long headerBytes, Long bodyBytes, String bodySha256, String ingestedAt) {}

    public static List<BookFiles> findBooks(Path datalakePath) throws IOException {
        Path root = datalakePath.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IOException("Datalake directory not found: " + root);
        Map<String, BookFiles> selected = new HashMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(root) && dir.getFileName().toString().equals(".tmp")
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || !path.getFileName().toString().endsWith(".success.json"))
                    return FileVisitResult.CONTINUE;
                BookFiles book;
                try {
                    book = readMarker(root, path);
                } catch (IllegalArgumentException | JsonParseException | java.time.DateTimeException e) {
                    // Never silently publish a partial rebuild when a committed marker is corrupt.
                    throw new IOException("Invalid completion marker: " + path, e);
                }
                selected.merge(book.bookId(), book, (a, b) -> {
                    int order = Instant.parse(a.marker().ingestedAt()).compareTo(Instant.parse(b.marker().ingestedAt()));
                    if (order == 0) order = a.headerPath().toString().compareTo(b.headerPath().toString());
                    LOG.warn("Duplicate book {}: selecting oldest completed download", a.bookId());
                    return order <= 0 ? a : b;
                });
                return FileVisitResult.CONTINUE;
            }
        });
        return selected.values().stream()
                .sorted(Comparator.comparingInt(b -> Integer.parseInt(b.bookId()))).toList();
    }

    private static BookFiles readMarker(Path root, Path path) throws IOException {
        Completion m = GSON.fromJson(Files.readString(path), Completion.class);
        if (m == null || m.bookId() == null || m.bookId() < 1 || m.bookId() > 10_000_000
                || m.headerPath() == null || m.bodyPath() == null || m.headerBytes() == null
                || m.bodyBytes() == null || m.headerBytes() < 0 || m.bodyBytes() <= 0
                || m.bodySha256() == null || !m.bodySha256().matches("[0-9a-f]{64}")
                || m.ingestedAt() == null)
            throw new IllegalArgumentException("Missing or invalid completion fields");
        Instant.parse(m.ingestedAt());
        String id = m.bookId().toString();
        if (!path.getFileName().toString().equals(id + ".success.json"))
            throw new IllegalArgumentException("Marker id does not match filename");
        Path header = resolve(root, m.headerPath());
        Path body = resolve(root, m.bodyPath());
        if (!header.equals(path.resolveSibling(id + ".header.txt"))
                || !body.equals(path.resolveSibling(id + ".body.txt")))
            throw new IllegalArgumentException("Marker paths do not match its partition");
        if (!Files.isRegularFile(header, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(body, LinkOption.NOFOLLOW_LINKS)
                || Files.size(header) != m.headerBytes() || Files.size(body) != m.bodyBytes())
            throw new IOException("Incomplete or changed published book: " + id);
        return new BookFiles(id, header, body, m);
    }

    private static Path resolve(Path root, String relative) {
        Path path = Path.of(relative);
        Path resolved = root.resolve(path).normalize();
        if (path.isAbsolute() || !resolved.startsWith(root))
            throw new IllegalArgumentException("Path outside datalake");
        return resolved;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
