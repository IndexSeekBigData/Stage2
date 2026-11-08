package com.inverseek.systemtests;

import com.inverseek.systemtests.support.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("fault")
class RestartIT extends FaultTest {
    private Connection writer() throws Exception {
        env.requireFaultOwnership();
        Connection c=DriverManager.getConnection("jdbc:sqlite:"+env.database().toUri()+"?mode=rw");
        try(var s=c.createStatement()) {s.execute("PRAGMA busy_timeout=0");}
        return c;
    }

    @Test void searchRestartPreservesResultsOnAllReplicas() throws Exception {
        String before=api.get("search",env.search().get(0),"/search?q=whale&limit=100").expect(200).toString();
        services.action("search","Restart");
        for(String url:env.search()) assertEquals(before,api.get("search",url,"/search?q=whale&limit=100").expect(200).toString());
    }

    @Test void controlCrashWithActiveWorkRecoversAfterLeaseExpiry() throws Exception {
        try(var connection=writer();var statement=connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            Corpus.register(api,env,Corpus.BOOKS);
            Await.until("Control holds indexing leases",Duration.ofSeconds(15),()->{
                var status=api.get("control",env.control(),"/control/status").expect(200);
                return status.getAsJsonObject("states").get("INDEXING").getAsInt()>0;
            });
            services.action("control","Stop");
            statement.execute("ROLLBACK");
        }
        services.action("control","Start");
        Corpus.indexed(api,env,Corpus.BOOKS);
        for(String url:env.search()) for(int id:Corpus.BOOKS) api.get("search",url,"/books/"+id).expect(200);
    }

    @Test void killingRebuildDuringItsWriteTransactionKeepsThePreviousCompleteIndex() throws Exception {
        services.action("control","Stop");
        String before=digest(oracle.snapshot());
        var executor=Executors.newSingleThreadExecutor();
        Future<?> operation=null;
        try {
            // An owned test-only trigger keeps the real rebuild transaction observable until killed.
            // It is removed in finally and never installed in an application database outside this run.
            try(var c=writer();var s=c.createStatement()) {
                s.execute("""
                    CREATE TRIGGER system_validation_rebuild_gate BEFORE DELETE ON books BEGIN
                      SELECT SUM(x) FROM (
                        WITH RECURSIVE count(x) AS (
                          VALUES(0) UNION ALL SELECT x+1 FROM count WHERE x<1000000000
                        ) SELECT x FROM count
                      );
                    END
                    """);
            }
            operation=executor.submit(()->{
                try {return api.post("indexing",env.indexing(),"/index/rebuild",null);}
                catch(Exception e) {throw new CompletionException(e);}
            });
            Future<?> current=operation;
            Await.until("rebuild owns the SQLite writer lock",Duration.ofSeconds(15),()->{
                assertFalse(current.isDone(),"Rebuild ended before the interruption");
                try(var c=writer();var s=c.createStatement()) {
                    try {s.execute("BEGIN IMMEDIATE");s.execute("ROLLBACK");return false;}
                    catch(SQLException e) {if(e.getErrorCode()==5 || e.getMessage().contains("SQLITE_BUSY")) return true;throw e;}
                }
            });
            services.action("indexing","Stop");
            assertEquals(before,digest(oracle.snapshot()));
            for(String url:env.search()) api.get("search",url,"/search?q=whale").expect(200);
            try {
                Object reply=operation.get(35,TimeUnit.SECONDS);
                if(reply instanceof Api.Reply response) assertNotEquals(200,response.status(),"Interrupted rebuild must not report a committed publication");
            } catch(ExecutionException expected) {
                assertTrue(expected.getCause() instanceof CompletionException);
            }
        } finally {
            try {
                services.action("indexing","Stop");
                services.action("indexing","Start");
                try(var c=writer();var s=c.createStatement()) {s.execute("DROP TRIGGER IF EXISTS system_validation_rebuild_gate");}
            } finally {
                if(operation!=null) operation.cancel(true);
                executor.shutdownNow();
                services.action("control","Start");
            }
        }
        assertEquals(before,digest(oracle.snapshot()));
        api.post("indexing",env.indexing(),"/index/rebuild",null).expect(200);
    }

    private static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
