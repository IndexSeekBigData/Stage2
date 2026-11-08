package com.inverseek.systemtests;

import com.inverseek.systemtests.support.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@Tag("fault")
class DependencyDownIT extends FaultTest {
    private void waitingWithNoConsumedAttempt(List<Integer> ids,String state) throws Exception {
        Await.until("unreachable dependency reported without accumulating attempts",Duration.ofSeconds(30),()->{
            for(int id:ids) {
                var book=api.get("control",env.control(),"/control/books/"+id).expect(200);
                assertNotEquals("FAILED",book.get("state").getAsString());
                if(!state.equals(book.get("state").getAsString()) || book.get("attempts").getAsInt()!=0
                        || book.get("last_error").isJsonNull()
                        || !book.get("last_error").getAsString().contains("unreachable")) return false;
            }
            return true;
        });
    }
    @Test void indexingOutageKeepsPublishedSearchAvailableThenResumes() throws Exception {
        String before=api.get("search",env.search().get(0),"/search?q=whale&limit=100").expect(200).getAsJsonArray("results").toString();
        services.action("indexing","Stop");
        Corpus.register(api,env,List.of(11,84));
        waitingWithNoConsumedAttempt(List.of(11,84),"DOWNLOADED");
        var health=api.get("control",env.control(),"/health").expect(200);
        assertEquals("DOWN",health.getAsJsonObject("dependencies").get("indexing").getAsString());
        for(String url:env.search()) assertEquals(before,api.get("search",url,"/search?q=whale&limit=100").expect(200).getAsJsonArray("results").toString());
        services.action("indexing","Start");
        Corpus.indexed(api,env,List.of(11,84));
    }
    @Test void totalIngestionOutageWaitsAndRecoversOnEveryConfiguredReplica() throws Exception {
        services.action("ingestion","Stop");
        Corpus.register(api,env,List.of(345,1342));
        waitingWithNoConsumedAttempt(List.of(345,1342),"PENDING");
        assertEquals("DOWN",api.get("control",env.control(),"/health").expect(200)
                .getAsJsonObject("dependencies").get("ingestion").getAsString());
        services.action("ingestion","Start");
        Corpus.indexed(api,env,List.of(345,1342));
    }
}
