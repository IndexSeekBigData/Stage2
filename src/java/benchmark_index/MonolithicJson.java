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
import java.util.Map;
import java.util.Set;

public class MonolithicJson implements InvertedIndex {

    private final Path indexPath;
    private final ObjectMapper mapper;

    public MonolithicJson(Path basePath) {
        this.indexPath = basePath.resolve("inverted_index.json");
        this.mapper = new ObjectMapper();
    }

    @Override
    public void buildIndex(List<Book> books) throws IOException {
        Map<String, Set<String>> index = new HashMap<>();

        for (Book book : books) {
            for (String term : Tokenizer.tokenize(book.body())) {
                index.computeIfAbsent(term, key -> new HashSet<>())
                        .add(book.id());
            }
        }

        saveIndex(index);
    }

    @Override
    public Set<String> updateIndex(List<Book> books) throws IOException {
        Map<String, Set<String>> index = new HashMap<>();

        if (Files.exists(indexPath)) {
            Map<String, List<String>> existing = mapper.readValue(
                    indexPath.toFile(),
                    new TypeReference<Map<String, List<String>>>() {}
            );

            for (Map.Entry<String, List<String>> entry : existing.entrySet()) {
                index.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }
        }

        Set<String> affectedTerms = new HashSet<>();

        for (Book book : books) {
            for (String term : Tokenizer.tokenize(book.body())) {
                index.computeIfAbsent(term, key -> new HashSet<>())
                        .add(book.id());

                affectedTerms.add(term);
            }
        }

        saveIndex(index);

        return affectedTerms;
    }

    @Override
    public List<String> searchTerm(String term) throws IOException {
        if (!Files.exists(indexPath)) {
            return new ArrayList<>();
        }

        Map<String, List<String>> index = mapper.readValue(
                indexPath.toFile(),
                new TypeReference<Map<String, List<String>>>() {}
        );

        return index.getOrDefault(
                term.toLowerCase(java.util.Locale.ROOT),
                new ArrayList<>()
        );
    }

    private void saveIndex(Map<String, Set<String>> index) throws IOException {
        Files.createDirectories(indexPath.getParent());

        Map<String, List<String>> serializable = new HashMap<>();

        for (Map.Entry<String, Set<String>> entry : index.entrySet()) {
            List<String> ids = new ArrayList<>(entry.getValue());

            ids.sort(Comparator.comparingInt(Integer::parseInt));

            serializable.put(entry.getKey(), ids);
        }

        mapper.writeValue(indexPath.toFile(), serializable);
    }
}

