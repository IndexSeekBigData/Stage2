
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

    public IndexingResult indexBooks() throws IOException {

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
}
