package com.inverseek.indexing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class IndexingService {
    private static final Logger LOG = LoggerFactory.getLogger(IndexingService.class);
    private final Config config;
    private final DatamartStore store;
    /** Serializes SQLite writes only: reading and tokenizing run in parallel in the request threads. */
    private final Object writeLock=new Object();

    public IndexingService(Config config) throws Exception {
        this.config=config;
        this.store=new DatamartStore(config.databasePath());
    }

    public IndexingResult indexBooks() throws IOException {
        var discovery=DatalakeReader.findBooksSkippingCorrupt(config.datalakePath());
        int indexed=0, failed=discovery.skipped();
        for (var book : discovery.books()) {
            try {
                publish(prepare(book));
                indexed++;
            } catch (IOException | SQLException e) {
                failed++;
                LOG.error("Cannot index book {}",book.bookId(),e);
            }
        }
        return new IndexingResult(discovery.books().size()+discovery.skipped(),indexed,failed);
    }

    public boolean indexBook(String bookId) throws Exception {
        String canonical = canonicalBookId(bookId);
        var book=DatalakeReader.findBook(config.datalakePath(),canonical);
        if (book.isEmpty()) return false;
        publish(prepare(book.get()));
        return true;
    }

    private void publish(DatamartStore.BookData data) throws SQLException {
        synchronized (writeLock) {
            store.saveBook(data);
        }
    }

    static String canonicalBookId(String raw) {
        try {
            int id=Integer.parseInt(raw);
            if (id < 1 || id > 10_000_000) throw new NumberFormatException();
            return Integer.toString(id);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("book_id must be an integer between 1 and 10000000");
        }
    }

    private static DatamartStore.BookData prepare(DatalakeReader.BookFiles book) throws IOException {
        byte[] header=Files.readAllBytes(book.headerPath());
        byte[] body=Files.readAllBytes(book.bodyPath());
        String hash=DatalakeReader.sha256(body);
        if (header.length != book.marker().headerBytes() || body.length != book.marker().bodyBytes()
                || !hash.equals(book.marker().bodySha256()))
            throw new IOException("Published files changed or hash mismatch for book " + book.bookId());
        return new DatamartStore.BookData(book.bookId(),
                HeaderParser.parseContent(new String(header,StandardCharsets.UTF_8)),
                Path.of(book.marker().bodyPath()),Tokenizer.tokenize(new String(body,StandardCharsets.UTF_8)),hash);
    }

    public RebuildResult rebuildIndex() throws Exception {
        long start=System.nanoTime();
        var loaders=new ArrayList<DatamartStore.BookLoader>();
        for (var book : DatalakeReader.findBooks(config.datalakePath())) loaders.add(() -> prepare(book));
        // One book in memory at a time, one transaction: a corrupt input rolls back and never erases the published index.
        synchronized (writeLock) {
            store.rebuildFrom(loaders);
        }
        return new RebuildResult(loaders.size(),
                String.format(Locale.ROOT,"%.2fs",(System.nanoTime()-start)/1_000_000_000.0));
    }

    public IndexStatus getStatus() throws SQLException {
        var state=store.snapshot();
        return new IndexStatus(state.booksIndexed(),state.lastUpdate(),state.sizeMB(),state.version());
    }

    public record IndexingResult(int total,int indexed,int failed) {}
    public record RebuildResult(int books_processed,String elapsed_time) {}
    public record IndexStatus(int books_indexed,String last_update,double index_size_MB,long index_version) {}
}
