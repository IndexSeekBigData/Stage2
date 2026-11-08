package com.inverseek.systemtests;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("fault")
class PartialDataIT extends FaultTest {
    @Test void incompleteCorruptAndTemporaryFilesNeverPublishOrBlockOtherBooks() throws Exception {
        Path partition=env.lake().resolve("20991231/23");
        Path temporary=env.lake().resolve(".tmp/system-validation-"+UUID.randomUUID());
        List<Path> created=new ArrayList<>();
        Files.createDirectories(partition);Files.createDirectories(temporary);
        try {
            for(String suffix:List.of(".header.txt",".body.txt")) {
                Path p=partition.resolve("9000001"+suffix);
                Files.writeString(p,"Title: Incomplete\nlove validation");created.add(p);
            }
            assertEquals("book_not_found",api.post("indexing",env.indexing(),"/index/update/9000001",null).expect(404).get("error").getAsString());
            for(String url:env.search()) api.get("search",url,"/books/9000001").expect(404);
            Path bad=partition.resolve("9000002.success.json");
            Files.writeString(bad,"{\"broken\":");created.add(bad);
            api.post("indexing",env.indexing(),"/index/update/11",null).expect(200);
            for(String suffix:List.of(".header.txt",".body.txt",".success.json")) {
                Path p=temporary.resolve("9000003"+suffix);
                Files.writeString(p,"unpublished");created.add(p);
            }
            api.post("indexing",env.indexing(),"/index/update/9000003",null).expect(404);
            for(String url:env.ingestion()) {
                var list=api.get("ingestion",url,"/ingest/list").expect(200).getAsJsonArray("books");
                assertFalse(list.asList().stream().anyMatch(id->id.getAsInt()>=9000001 && id.getAsInt()<=9000003));
            }
            for(String url:env.search()) api.get("search",url,"/books/9000003").expect(404);
        } finally {
            for(Path path:created) {
                if(!path.toAbsolutePath().normalize().startsWith(env.lake())) throw new AssertionError("Unsafe cleanup path");
                Files.deleteIfExists(path);
            }
            Files.deleteIfExists(temporary);
            try(var children=Files.list(partition)) {if(children.findAny().isEmpty()) Files.delete(partition);}
            Path day=partition.getParent();
            try(var children=Files.list(day)) {if(children.findAny().isEmpty()) Files.delete(day);}
        }
    }
}
