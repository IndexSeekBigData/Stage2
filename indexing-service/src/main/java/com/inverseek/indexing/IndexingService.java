
package com.inverseek.indexing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public class IndexingService {

    private final Config config;
    private final DatamartStore store;

    public IndexingService(Config config) throws Exception {
        this.config = config;
        this.store = new DatamartStore(config.databasePath());
    }

    public synchronized IndexingResult indexBooks() throws IOException {

        List<DatalakeReader.BookFiles> books =
                DatalakeReader.findBooks(config.datalakePath());

        int indexed = 0;
        int failed = 0;

        for (DatalakeReader.BookFiles book : books) {

            try {
                HeaderParser.BookMetadata metadata =
                        HeaderParser.parse(book.headerPath());

                String content = Files.readString(
                        book.bodyPath(),
                        StandardCharsets.UTF_8
                );

                Map<String, Integer> frequencies =
                        Tokenizer.tokenize(content);

                store.saveBook(
                        book.bookId(),
                        metadata,
                        book.bodyPath(),
                        frequencies
                );

                indexed++;

                System.out.println(
                        "Indexed book: " + book.bookId()
                );

            } catch (IOException | SQLException e) {

                failed++;

                System.err.println(
                        "Error indexing book " +
                                book.bookId() + ": " + e.getMessage()
                );
            }
        }

        return new IndexingResult(
                books.size(),
                indexed,
                failed
        );
    }

    public record IndexingResult(
            int total,
            int indexed,
            int failed
    ) {}


    public synchronized boolean indexBook(String bookId) throws Exception {

        List<DatalakeReader.BookFiles> books =
                DatalakeReader.findBooks(config.datalakePath());

        for (DatalakeReader.BookFiles book : books) {

            if (!book.bookId().equals(bookId)) {
                continue;
            }

            HeaderParser.BookMetadata metadata =
                    HeaderParser.parse(book.headerPath());

            String body = Files.readString(
                    book.bodyPath(),
                    StandardCharsets.UTF_8
            );

            Map<String, Integer> frequencies =
                    Tokenizer.tokenize(body);

            store.saveBook(
                    bookId,
                    metadata,
                    book.bodyPath(),
                    frequencies
            );

            return true;
        }

        return false;
    }



    public synchronized RebuildResult rebuildIndex() throws Exception {

        long startTime = System.nanoTime();

        List<DatalakeReader.BookFiles> books =
                DatalakeReader.findBooks(config.datalakePath());

        List<DatamartStore.BookData> bookData =
                new java.util.ArrayList<>();

        for (DatalakeReader.BookFiles book : books) {

            HeaderParser.BookMetadata metadata =
                    HeaderParser.parse(book.headerPath());

            String content = Files.readString(
                    book.bodyPath(),
                    StandardCharsets.UTF_8
            );

            Map<String, Integer> frequencies =
                    Tokenizer.tokenize(content);

            bookData.add(
                    new DatamartStore.BookData(
                            book.bookId(),
                            metadata,
                            book.bodyPath(),
                            frequencies
                    )
            );
        }

        // Atomically replace the complete index
        store.rebuildIndex(bookData);

        double elapsedSeconds =
                (System.nanoTime() - startTime) / 1_000_000_000.0;

        return new RebuildResult(
                bookData.size(),
                String.format(
                        java.util.Locale.US,
                        "%.2fs",
                        elapsedSeconds
                )
        );
    }


    public record RebuildResult(
            int books_processed,
            String elapsed_time
    ) {}


    public IndexStatus getStatus() throws SQLException {

        int booksIndexed = store.countBooks();

        String lastUpdate = store.getLastUpdate();

        double indexSizeMB = store.getIndexSizeMB();

        return new IndexStatus(
                booksIndexed,
                lastUpdate,
                indexSizeMB
        );
    }

    public record IndexStatus(
            int books_indexed,
            String last_update,
            double index_size_MB
    ) {}


}


