
package benchmark_datalake;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class TimeBased implements Datalake {

    private final Path basePath;
    private final String date;
    private final String hour;

    public TimeBased(
            Path basePath,
            String date,
            String hour
    ) {
        this.basePath = basePath;
        this.date = date;
        this.hour = hour;
    }

    @Override
    public void saveBook(
            String bookId,
            String header,
            String body
    ) throws IOException {

        Path directory = basePath
                .resolve(date)
                .resolve(hour);

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
