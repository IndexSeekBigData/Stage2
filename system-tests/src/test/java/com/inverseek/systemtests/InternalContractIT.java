package com.inverseek.systemtests;

import com.inverseek.systemtests.support.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@Tag("contract")
class InternalContractIT extends SystemTest {
    @Test void ingestionStatusInventoryAndValidationMatchItsSpecification() throws Exception {
        for(String url:env.ingestion()) {
            api.get("ingestion",url,"/health").expect(200);
            assertEquals("available",api.get("ingestion",url,"/ingest/status/1342").expect(200).get("status").getAsString());
            assertEquals("not_available",api.get("ingestion",url,"/ingest/status/9999999").expect(404).get("status").getAsString());
            assertTrue(api.get("ingestion",url,"/ingest/list").expect(200).get("count").getAsInt()>=5);
            assertEquals("invalid_book_id",api.post("ingestion",url,"/ingest/abc",null).expect(400).get("error").getAsString());
        }
    }
    @Test void indexingStatusAndInvalidOrMissingBooksMatchItsSpecification() throws Exception {
        api.get("indexing",env.indexing(),"/health").expect(200);
        assertTrue(api.get("indexing",env.indexing(),"/index/status").expect(200).get("books_indexed").getAsInt()>=5);
        assertEquals("invalid_book_id",api.post("indexing",env.indexing(),"/index/update/abc",null).expect(400).get("error").getAsString());
        assertEquals("book_not_found",api.post("indexing",env.indexing(),"/index/update/9999999",null).expect(404).get("error").getAsString());
    }
    @Test void controlValidationAndExplicitReindexFollowTheContract() throws Exception {
        api.get("control",env.control(),"/health").expect(200);
        assertEquals("invalid_request",api.post("control",env.control(),"/control/books","{\"ids\":[]}").expect(400).get("error").getAsString());
        api.get("control",env.control(),"/control/books?state=invalid").expect(400);
        api.get("control",env.control(),"/control/books/0").expect(400);
        api.post("control",env.control(),"/control/books/9000010/retry",null).expect(404);
        api.post("control",env.control(),"/control/books/1342/retry",null).expect(202);
        Corpus.indexed(api,env,List.of(1342));
    }
}
