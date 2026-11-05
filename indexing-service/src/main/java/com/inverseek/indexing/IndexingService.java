package com.inverseek.indexing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class IndexingService {
    private static final Logger LOG = LoggerFactory.getLogger(IndexingService.class);
    private final Config config;
    private final DatamartStore store;

    public IndexingService(Config config) throws Exception {
        this.config=config;
        this.store=new DatamartStore(config.databasePath());
    }

    public synchronized IndexingResult indexBooks() throws IOException {
        var books=DatalakeReader.findBooks(config.datalakePath());
        int indexed=0, failed=0;
        for (var book : books) {
            try {
                store.saveBook(prepare(book));
                indexed++;
            } catch (IOException | SQLException e) {
                failed++;
                LOG.error("Cannot index book {}",book.bookId(),e);
            }
        }
        return new IndexingResult(books.size(),indexed,failed);
    }

    public synchronized boolean indexBook(String bookId) throws Exception {
        String canonical = canonicalBookId(bookId);
        for (var book : DatalakeReader.findBooks(config.datalakePath())) {
            if (book.bookId().equals(canonical)) {
                store.saveBook(prepare(book));
                return true;
            }
        }
        return false;
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
                book.bodyPath(),Tokenizer.tokenize(new String(body,StandardCharsets.UTF_8)),hash);
    }

    public synchronized RebuildResult rebuildIndex() throws Exception {
        long start=System.nanoTime();
        var prepared=new ArrayList<DatamartStore.BookData>();
        // Prepare first: a corrupt input never erases the previous published index.
        for (var book : DatalakeReader.findBooks(config.datalakePath())) prepared.add(prepare(book));
        store.rebuildIndex(prepared);
        return new RebuildResult(prepared.size(),
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
