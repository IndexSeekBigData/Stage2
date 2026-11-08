package com.inverseek.systemtests;

import com.inverseek.systemtests.support.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("infrastructure")
class InfrastructureTest {
    @Test void loadsAllSpecificationsAndRejectsAnInvalidResponse() {
        var schemas = new OpenApi(Path.of(System.getProperty("repository.root")).normalize());
        schemas.validate("search","/search","GET",400,Map.of("Content-Type",List.of("application/json")),
                "{\"error\":\"invalid_query\",\"message\":\"Missing q\"}");
        assertThrows(AssertionError.class, () -> schemas.validate("search","/search","GET",200,
                Map.of("Content-Type",List.of("application/json")),"{\"total\":0}"));
    }
    @Test void pollingStopsOnSuccessAndFailsAtItsDeadline() throws Exception {
        var attempts=new AtomicInteger();
        Await.until("eventual result",Duration.ofSeconds(1),()->attempts.incrementAndGet()==2);
        assertEquals(2,attempts.get());
        assertThrows(AssertionError.class,()->Await.until("never",Duration.ofMillis(10),()->false));
    }
}
