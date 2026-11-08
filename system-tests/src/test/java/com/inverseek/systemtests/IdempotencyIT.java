package com.inverseek.systemtests;

import com.inverseek.systemtests.support.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("flow")
class IdempotencyIT extends SystemTest {
    @Test void duplicateRegistrationKeepsTheExistingBook() throws Exception {
        var original=api.get("control",env.control(),"/control/books/1342").expect(200);
        for(int i=0;i<2;i++) {
            var registered=api.post("control",env.control(),"/control/books","{\"ids\":[1342]}").expect(202);
            assertEquals(0,registered.get("inserted").getAsInt());
            assertEquals(1,registered.get("ignored").getAsInt());
        }
        assertEquals(original,api.get("control",env.control(),"/control/books/1342").expect(200));
    }
    @Test void repeatedIngestionAcrossReplicasKeepsOnePublishedMarker() throws Exception {
        String path=null;
        for(String url:env.ingestion()) for(int i=0;i<2;i++) {
            var reply=api.post("ingestion",url,"/ingest/1342",null).expect(200);
            assertEquals("available",reply.get("status").getAsString());
            if(path==null) path=reply.get("path").getAsString();
            else assertEquals(path,reply.get("path").getAsString());
        }
        try(var files=java.nio.file.Files.walk(env.lake())) {
            assertEquals(1,files.filter(p->p.getFileName().toString().equals("1342.success.json")).count());
        }
    }
    @Test void repeatedAndConcurrentIndexUpdatesPreserveTermsAndFrequencies() throws Exception {
        var expected=oracle.postings(1342);
        int count=api.get("indexing",env.indexing(),"/index/status").expect(200).get("books_indexed").getAsInt();
        for(int i=0;i<2;i++) api.post("indexing",env.indexing(),"/index/update/1342",null).expect(200);
        var pool=Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> tasks=new ArrayList<>();
            for(int i=0;i<8;i++) tasks.add(()->api.post("indexing",env.indexing(),"/index/update/1342",null).status());
            for(var result:pool.invokeAll(tasks)) assertEquals(200,result.get());
        } finally {pool.shutdownNow();}
        assertEquals(expected,oracle.postings(1342));
        assertEquals(count,api.get("indexing",env.indexing(),"/index/status").expect(200).get("books_indexed").getAsInt());
    }
}
