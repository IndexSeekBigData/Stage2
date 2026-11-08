package com.inverseek.systemtests;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("contract")
class SearchContractIT extends SystemTest {
    private com.google.gson.JsonObject search(String url,String parameters) throws Exception {
        return api.get("search",url,"/search?"+parameters).expect(200);
    }
    private Set<Integer> ids(com.google.gson.JsonObject page) {
        Set<Integer> ids=new TreeSet<>();
        page.getAsJsonArray("results").forEach(row->ids.add(row.getAsJsonObject().get("book_id").getAsInt()));
        return ids;
    }
    @Test void keywordAndMultipleTermsMatchThePublishedIndexOnEveryReplica() throws Exception {
        for(String url:env.search()) {
            var whale=search(url,"q=whale&limit=100");
            assertEquals(2701,whale.getAsJsonArray("results").get(0).getAsJsonObject().get("book_id").getAsInt());
            assertEquals(oracle.containing("whale"),ids(whale));
            var both=search(url,"q=love%20truth&limit=100");
            assertEquals(oracle.containing("love","truth"),ids(both));
            var repeated=search(url,"q=love%20love&limit=100");
            assertEquals(search(url,"q=love&limit=100").getAsJsonArray("results"),repeated.getAsJsonArray("results"));
        }
    }
    @Test void filtersWorkIndividuallyAndTogetherIncludingEmptyYears() throws Exception {
        for(String url:env.search()) {
            var author=search(url,"q=love&author=AUST%C3%89N&limit=100");
            assertEquals(Set.of(1342),ids(author));
            assertEquals(author.getAsJsonArray("results"),search(url,"q=love&author=austen&limit=100").getAsJsonArray("results"));
            var language=search(url,"q=love&language=EN&limit=100");
            assertFalse(ids(language).isEmpty());
            language.getAsJsonArray("results").forEach(row->assertEquals("en",row.getAsJsonObject().get("language").getAsString()));
            assertEquals(0,search(url,"q=love&language=zz").get("total").getAsInt());
            assertEquals(Set.of(1342),ids(search(url,"q=love&author=austen&language=en&limit=100")));
            assertEquals(0,search(url,"q=love&year=1813").get("total").getAsInt());
            assertEquals(0,search(url,"q=love&author=austen&language=en&year=1813").get("total").getAsInt());
            var empty=search(url,"q=nonexistentinverseekvalidationterm");
            assertEquals(0,empty.get("total").getAsInt());
            assertTrue(empty.getAsJsonArray("results").isEmpty());
        }
    }
    @Test void invalidQueriesHaveDocumentedErrorsOnEveryReplica() throws Exception {
        Map<String,String> cases=new LinkedHashMap<>();
        cases.put("","invalid_query");cases.put("?q=of","invalid_query");
        cases.put("?q=coraz%C3%B3n","invalid_query");
        for(String p:List.of("limit=0","limit=101","offset=-1","year=abc","year=99","language=english"))
            cases.put("?q=love&"+p,"invalid_parameter");
        for(String url:env.search()) for(var c:cases.entrySet())
            assertEquals(c.getValue(),api.get("search",url,"/search"+c.getKey()).expect(400).get("error").getAsString());
    }
    @Test void stablePaginationPreservesTotalOrderAndCoverage() throws Exception {
        for(String url:env.search()) {
            var all=search(url,"q=love&limit=100");
            int total=all.get("total").getAsInt();
            List<Integer> gathered=new ArrayList<>();
            long version=all.get("index_version").getAsLong();
            for(int offset=0;offset<total;offset+=2) {
                var page=search(url,"q=love&offset="+offset+"&limit=2");
                assertEquals(total,page.get("total").getAsInt());
                assertEquals(version,page.get("index_version").getAsLong());
                page.getAsJsonArray("results").forEach(row->gathered.add(row.getAsJsonObject().get("book_id").getAsInt()));
            }
            List<Integer> expected=new ArrayList<>();
            all.getAsJsonArray("results").forEach(row->expected.add(row.getAsJsonObject().get("book_id").getAsInt()));
            assertEquals(expected,gathered);
            assertEquals(gathered.size(),new HashSet<>(gathered).size());
            var beyond=search(url,"q=love&offset=2147483647");
            assertEquals(total,beyond.get("total").getAsInt());
            assertTrue(beyond.getAsJsonArray("results").isEmpty());
        }
    }
}
