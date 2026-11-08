package com.inverseek.systemtests;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.TreeSet;
import static org.junit.jupiter.api.Assertions.*;

@Tag("contract")
class BooksAndFiltersIT extends SystemTest {
    @Test void detailsAndFacetsDescribeTheRealCorpus() throws Exception {
        for(String url:env.search()) {
            var book=api.get("search",url,"/books/1342").expect(200);
            assertEquals("Jane Austen",book.get("author").getAsString());
            assertTrue(book.get("year").isJsonNull());
            assertFalse(book.has("body_path"));
            assertEquals("book_not_found",api.get("search",url,"/books/9999999").expect(404).get("error").getAsString());
            var facets=api.get("search",url,"/search/filters").expect(200);
            var names=new TreeSet<String>();
            facets.getAsJsonArray("authors").forEach(a->names.add(a.getAsJsonObject().get("name").getAsString()));
            assertTrue(names.contains("Jane Austen"));
            assertTrue(facets.getAsJsonArray("languages").asList().stream()
                    .anyMatch(a->a.getAsJsonObject().get("code").getAsString().equals("en")));
            assertEquals(0,facets.getAsJsonObject("years").get("books_with_year").getAsInt());
            assertTrue(facets.getAsJsonObject("years").get("min").isJsonNull());
        }
    }
    @Test void corsIsPresentOnSuccessfulAndErrorResponses() throws Exception {
        for(String url:env.search()) for(String path:java.util.List.of("/search?q=whale","/search?q=of")) {
            var reply=api.call("search",url,"GET",path,null,Map.of("Origin","http://localhost:5173"),true);
            assertTrue(reply.headers().firstValue("Access-Control-Allow-Origin").isPresent());
        }
    }
    @Test void unknownRoutesInEveryProcessReturnJson404() throws Exception {
        var targets=new java.util.LinkedHashMap<String,java.util.List<String>>();
        targets.put("control",java.util.List.of(env.control())); targets.put("indexing",java.util.List.of(env.indexing()));
        targets.put("ingestion",env.ingestion()); targets.put("search",env.search());
        for(var target:targets.entrySet()) for(String url:target.getValue()) {
            // The intentionally nonexistent path has no OpenAPI operation to resolve.
            var reply=api.call(target.getKey(),url,"GET","/system-validation-missing-route",null,Map.of(),false);
            var error=reply.expect(404);
            assertEquals("route_not_found",error.get("error").getAsString());
            assertTrue(error.has("message"));
            assertTrue(reply.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        }
    }
}
