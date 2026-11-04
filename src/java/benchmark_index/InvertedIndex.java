
package benchmark_index;

import java.io.IOException;
import java.util.List;
import java.util.Set;

public interface InvertedIndex {

    void buildIndex(List<Book> books) throws IOException;

    Set<String> updateIndex(List<Book> books) throws IOException;

    List<String> searchTerm(String term) throws IOException;
}
