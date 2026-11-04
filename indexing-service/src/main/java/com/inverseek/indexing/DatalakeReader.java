
package com.inverseek.indexing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class DatalakeReader {

    public record BookFiles(
            String bookId,
            Path headerPath,
            Path bodyPath
    ) {}

    public static List<BookFiles> findBooks(Path datalakePath)
            throws IOException {

        List<BookFiles> books = new ArrayList<>();

        if (!Files.isDirectory(datalakePath)) {
            throw new IOException("Datalake directory not found: " + datalakePath);
        }

        try (Stream<Path> files = Files.walk(datalakePath)) {

            for (Path header : files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".header.txt"))
                    .toList()) {

                String filename = header.getFileName().toString();
                String bookId = filename.substring(
                        0,
                        filename.length() - ".header.txt".length()
                );

                Path body = header.resolveSibling(bookId + ".body.txt");
                Path success = header.resolveSibling(bookId + ".success.json");

                if (Files.isRegularFile(body) && Files.isRegularFile(success)) {
                    books.add(new BookFiles(bookId, header, body));
                }
            }
        }

        return books;
    }
}
