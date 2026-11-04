package benchmark_datalake;

import java.io.IOException;

public interface Datalake {

    void saveBook(
            String bookId,
            String header,
            String body
    ) throws IOException;

}