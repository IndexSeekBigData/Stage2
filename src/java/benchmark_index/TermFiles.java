
package benchmark_index;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public class TermFiles implements InvertedIndex {

    private final Path indexPath;
    private final ObjectMapper mapper;

    public TermFiles(Path indexPath) {
        this.indexPath = indexPath;
        this.mapper = new ObjectMapper();
    }

    @Override
    public void buildIndex(List<Book> books) throws IOException {
        if (Files.exists(indexPath)) {
            try (Stream<Path> stream = Files.walk(indexPath)) {
                for (Path path : stream
                        .sorted(Comparator.reverseOrder())
                        .toList()) {
                    Files.delete(path);
                }
            }
        }

        updateIndex(books);
    }

    @Override
    public Set<String> updateIndex(List<Book> books) throws IOException {
        Files.createDirectories(indexPath);

        Map<String, Set<String>> newPostings = new HashMap<>();

        for (Book book : books) {
            for (String term : Tokenizer.tokenize(book.body())) {
                newPostings
                        .computeIfAbsent(term, key -> new HashSet<>())
                        .add(book.id());
            }
        }

        Set<String> affectedTerms = new HashSet<>(newPostings.keySet());

        for (Map.Entry<String, Set<String>> entry : newPostings.entrySet()) {
            String term = entry.getKey();
            Path path = indexPath.resolve(term + ".json");

            Set<String> existingIds = new HashSet<>();

            if (Files.exists(path)) {
                List<String> ids = mapper.readValue(
                        path.toFile(),
                        new TypeReference<List<String>>() {}
                );
                existingIds.addAll(ids);
            }

            Set<String> updatedIds = new HashSet<>(existingIds);
            updatedIds.addAll(entry.getValue());

            if (!updatedIds.equals(existingIds)) {
                List<String> sortedIds = new ArrayList<>(updatedIds);
                sortedIds.sort(Comparator.comparingInt(Integer::parseInt));
                mapper.writeValue(path.toFile(), sortedIds);
            }
        }

        return affectedTerms;
    }

    @Override
    public List<String> searchTerm(String term) throws IOException {
        Path path = indexPath.resolve(
                term.toLowerCase(Locale.ROOT) + ".json"
        );

        if (!Files.exists(path)) {
            return new ArrayList<>();
        }

        return mapper.readValue(
                path.toFile(),
                new TypeReference<List<String>>() {}
        );
    }
}

