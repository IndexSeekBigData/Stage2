
package benchmark_datalake;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class BookBased implements Datalake {

    private final Path basePath;

    public BookBased(Path basePath) {
        this.basePath = basePath;
    }

    @Override
    public void saveBook(
            String bookId,
            String header,
            String body
    ) throws IOException {

        Path directory = basePath.resolve(bookId);

        Files.createDirectories(directory);

        Files.writeString(
                directory.resolve(bookId + ".header.txt"),
                header,
                StandardCharsets.UTF_8
        );

        Files.writeString(
                directory.resolve(bookId + ".body.txt"),
                body,
                StandardCharsets.UTF_8
        );
    }
}
