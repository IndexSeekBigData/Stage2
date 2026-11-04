
package benchmark_datalake;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class RangeBased implements Datalake {

    private final Path basePath;

    public RangeBased(Path basePath) {
        this.basePath = basePath;
    }

    @Override
    public void saveBook(
            String bookId,
            String header,
            String body
    ) throws IOException {

        int bookNumber = Integer.parseInt(bookId);
        int rangeStart = (bookNumber / 25) * 25;
        int rangeEnd = rangeStart + 24;

        Path directory = basePath.resolve(
                rangeStart + "-" + rangeEnd
        );

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
