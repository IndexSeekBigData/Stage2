package com.inverseek.ingestion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * File-based datalake that keeps the Stage 1 layout:
 *
 * <pre>
 * datalake/AAAAMMDD/HH/{id}.header.txt
 * datalake/AAAAMMDD/HH/{id}.body.txt
 * datalake/AAAAMMDD/HH/{id}.success.json   (completion marker, written last)
 * datalake/.tmp/                           (downloads in progress, never read by other services)
 * </pre>
 *
 * <p>A book exists for the rest of the system only when its {@code .success.json} marker exists.
 * Files are first written under {@code .tmp/} and then moved into the partition with an atomic
 * rename; the marker is moved last. A crash at any point therefore leaves either nothing visible
 * or a complete book, never a half-written one.
 *
 * <p>This class is the only writer of the datalake. It is thread-safe; callers serialise work on
 * the same book id (see {@link IngestionService}).
 */
public class Datalake {

    private static final Logger log = LoggerFactory.getLogger(Datalake.class);

    static final String TMP_DIR = ".tmp";
    static final String MARKER_SUFFIX = ".success.json";
    static final String HEADER_SUFFIX = ".header.txt";
    static final String BODY_SUFFIX = ".body.txt";
    /** Leftovers younger than this may belong to a write in progress in another replica. */
    static final Duration ORPHAN_AGE = Duration.ofMinutes(10);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH");

    private final Path root;
    private final ZoneId zone;
    private final Clock clock;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<Integer, BookRecord> catalog = new ConcurrentHashMap<>();

    public Datalake(Path root, ZoneId zone, Clock clock) {
        this.root = root;
        this.zone = zone;
        this.clock = clock;
        try {
            Files.createDirectories(root.resolve(TMP_DIR));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create datalake at " + root, e);
        }
    }

    public Path root() {
        return root;
    }

    /**
     * Rebuilds the in-memory catalog from the markers on disk and removes old leftovers of
     * interrupted writes. Called at start-up.
     */
    public void recover() {
        removeOldLeftovers();
        catalog.clear();
        scanMarkers();
        log.info("Datalake {} ready with {} books", root, catalog.size());
    }

    /** Returns the completed book, looking at the disk again if it is not in memory (another replica may have written it). */
    public Optional<BookRecord> find(int bookId) {
        BookRecord cached = catalog.get(bookId);
        if (cached != null) {
            return Optional.of(cached);
        }
        scanMarkers();
        return Optional.ofNullable(catalog.get(bookId));
    }

    /** Ids of all completed books, ascending. */
    public List<Integer> listBookIds() {
        scanMarkers();
        List<Integer> ids = new ArrayList<>(catalog.keySet());
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    public int size() {
        return catalog.size();
    }

    /** Writes a complete book and publishes it by moving its marker into place last. */
    public BookRecord store(int bookId, String header, String body) {
        Instant now = clock.instant();
        ZonedDateTime local = now.atZone(zone);
        String partition = DAY.format(local) + "/" + HOUR.format(local);
        Path partitionDir = root.resolve(partition);
        Path tmp = root.resolve(TMP_DIR).resolve(bookId + "-" + UUID.randomUUID());

        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String prefix = partition + "/" + bookId;
        BookRecord record = new BookRecord(
                bookId,
                prefix + HEADER_SUFFIX,
                prefix + BODY_SUFFIX,
                headerBytes.length,
                bodyBytes.length,
                sha256(bodyBytes),
                now.toString());

        try {
            Files.createDirectories(tmp);
            Files.createDirectories(partitionDir);
            Path tmpHeader = write(tmp.resolve(bookId + HEADER_SUFFIX), headerBytes);
            Path tmpBody = write(tmp.resolve(bookId + BODY_SUFFIX), bodyBytes);
            Path tmpMarker = write(tmp.resolve(bookId + MARKER_SUFFIX), gson.toJson(record).getBytes(StandardCharsets.UTF_8));

            moveAtomic(tmpHeader, partitionDir.resolve(bookId + HEADER_SUFFIX));
            moveAtomic(tmpBody, partitionDir.resolve(bookId + BODY_SUFFIX));
            moveAtomic(tmpMarker, partitionDir.resolve(bookId + MARKER_SUFFIX)); // publication point
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot store book " + bookId + " in the datalake", e);
        } finally {
            deleteQuietly(tmp);
        }
        catalog.put(bookId, record);
        return record;
    }

    private static Path write(Path file, byte[] content) throws IOException {
        Files.write(file, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC);
        return file;
    }

    private static void moveAtomic(Path from, Path to) throws IOException {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Loads every marker on disk into the catalog. If a book has two markers, the oldest one wins. */
    private void scanMarkers() {
        try (Stream<Path> days = listDirs(root)) {
            days.filter(d -> !d.getFileName().toString().equals(TMP_DIR))
                .forEach(day -> {
                    try (Stream<Path> hours = listDirs(day)) {
                        hours.forEach(this::scanPartition);
                    }
                });
        }
    }

    private void scanPartition(Path partition) {
        try (DirectoryStream<Path> markers = Files.newDirectoryStream(partition, "*" + MARKER_SUFFIX)) {
            for (Path marker : markers) {
                readMarker(marker).ifPresent(r -> catalog.merge(r.bookId(), r,
                        (a, b) -> a.ingestedAt().compareTo(b.ingestedAt()) <= 0 ? a : b));
            }
        } catch (IOException e) {
            log.warn("Cannot scan partition {}: {}", partition, e.getMessage());
        }
    }

    private Optional<BookRecord> readMarker(Path marker) {
        try {
            BookRecord record = gson.fromJson(Files.readString(marker, StandardCharsets.UTF_8), BookRecord.class);
            if (record == null || record.headerPath() == null || record.bodyPath() == null) {
                log.warn("Ignoring malformed marker {}", marker);
                return Optional.empty();
            }
            return Optional.of(record);
        } catch (IOException | JsonParseException e) {
            log.warn("Ignoring unreadable marker {}: {}", marker, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Deletes temporary folders and header/body files without a marker, if they are older than
     * {@link #ORPHAN_AGE}. Younger leftovers may belong to a write still running in another replica.
     */
    private void removeOldLeftovers() {
        Instant limit = clock.instant().minus(ORPHAN_AGE);
        try (Stream<Path> tmps = listDirs(root.resolve(TMP_DIR))) {
            tmps.filter(p -> olderThan(p, limit)).forEach(Datalake::deleteQuietly);
        }
        try (Stream<Path> days = listDirs(root)) {
            days.filter(d -> !d.getFileName().toString().equals(TMP_DIR)).forEach(day -> {
                try (Stream<Path> hours = listDirs(day)) {
                    hours.forEach(partition -> removeOrphansIn(partition, limit));
                }
            });
        }
    }

    private void removeOrphansIn(Path partition, Instant limit) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(partition, "*.txt")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String id = name.endsWith(HEADER_SUFFIX) ? name.substring(0, name.length() - HEADER_SUFFIX.length())
                        : name.endsWith(BODY_SUFFIX) ? name.substring(0, name.length() - BODY_SUFFIX.length())
                        : null;
                if (id != null && !Files.exists(partition.resolve(id + MARKER_SUFFIX)) && olderThan(file, limit)) {
                    log.info("Removing leftover of an interrupted write: {}", file);
                    deleteQuietly(file);
                }
            }
        } catch (IOException e) {
            log.warn("Cannot clean partition {}: {}", partition, e.getMessage());
        }
    }

    private static boolean olderThan(Path path, Instant limit) {
        try {
            FileTime modified = Files.getLastModifiedTime(path);
            return modified.toInstant().isBefore(limit);
        } catch (IOException e) {
            return false;
        }
    }

    private static Stream<Path> listDirs(Path dir) {
        if (!Files.isDirectory(dir)) {
            return Stream.empty();
        }
        try {
            List<Path> dirs = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, Files::isDirectory)) {
                entries.forEach(dirs::add);
            }
            return dirs.stream();
        } catch (IOException e) {
            log.warn("Cannot list {}: {}", dir, e.getMessage());
            return Stream.empty();
        }
    }

    static void deleteQuietly(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best effort: a later start-up will try again.
                }
            });
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
