package com.inverseek.systemtests;

import com.inverseek.systemtests.support.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@Tag("flow")
class PipelineFlowIT extends SystemTest {
    @Test void freshBookTraversesAllServicesAndIsSearchable() throws Exception {
        int id=newId();
        assertEquals(1,api.post("control",env.control(),"/control/books","{\"ids\":["+id+"]}").expect(202).get("inserted").getAsInt());
        Corpus.indexed(api,env,List.of(id));
        var terms=oracle.postings(id);
        assertFalse(terms.isEmpty());
        String term=terms.keySet().iterator().next();
        for(String url:env.search()) {
            api.get("search",url,"/books/"+id).expect(200);
            var page=api.get("search",url,"/search?q="+Api.encode(term)+"&limit=100").expect(200);
            assertTrue(page.getAsJsonArray("results").asList().stream().anyMatch(r->r.getAsJsonObject().get("book_id").getAsInt()==id));
        }
    }
    private int newId() throws Exception {
        for(int id:List.of(98,120,74,76,1661,43)) {
            if(api.get("control",env.control(),"/control/books/"+id).status()==404) return id;
        }
        throw new AssertionError("Flow ids exhausted; run validate-all with a new data directory");
    }
    @Test void missingBookHasReasonAndNeverAppearsInSearch() throws Exception {
        var missing=api.get("control",env.control(),"/control/books/"+Corpus.MISSING).expect(200);
        assertEquals("NOT_FOUND",missing.get("state").getAsString());
        assertFalse(missing.get("last_error").isJsonNull());
        for(String url:env.search()) api.get("search",url,"/books/"+Corpus.MISSING).expect(404);
    }
    @Test void controlInventoryAndMetricsAreConsistent() throws Exception {
        var status=api.get("control",env.control(),"/control/status").expect(200);
        var page=api.get("control",env.control(),"/control/books?limit=500").expect(200);
        assertEquals(status.get("total").getAsLong(),page.get("total").getAsLong());
        long sum=0;
        for(var state:status.getAsJsonObject("states").entrySet()) sum+=state.getValue().getAsLong();
        assertEquals(status.get("total").getAsLong(),sum);
        var metrics=api.get("control",env.control(),"/control/metrics").expect(200);
        assertTrue(metrics.get("books_indexed").getAsInt()>=5);
        for(String phase:List.of("queue_wait_ms","ingest_ms","index_wait_ms","index_ms","end_to_end_ms")) {
            assertFalse(metrics.getAsJsonObject(phase).get("avg").isJsonNull());
            assertFalse(metrics.getAsJsonObject(phase).get("p95").isJsonNull());
        }
    }
}
