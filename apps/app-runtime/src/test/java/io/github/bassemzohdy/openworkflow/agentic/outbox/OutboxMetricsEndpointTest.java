package io.github.bassemzohdy.openworkflow.agentic.outbox;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Real application scrape and real PostgreSQL observer; no fixture resets or permissive policy overrides. */
@QuarkusTest
class OutboxMetricsEndpointTest {
    @Test void actualApplicationExportsTheFiveBoundedOutboxSignalsAtTheCollectorPath() throws Exception {
        String response=given().accept("text/plain").when().get("/q/metrics").then().statusCode(200).extract().asString();
        var names=Set.of("agentic_outbox_unpublished","agentic_outbox_failed",
                "agentic_outbox_unpublished_oldest_age_seconds","agentic_outbox_observation_healthy",
                "agentic_outbox_observation_age_seconds");
        var observed=new HashMap<String,Double>();var retained=new ArrayList<String>();
        for(String line:response.lines().toList()) for(String name:names) {
            if(line.startsWith(name+" ")) {
                assertNull(observed.put(name,Double.parseDouble(line.substring(name.length()+1).split(" ")[0])),"one series per signal");
                retained.add(line);
            } else if(line.startsWith(name+"{")) fail("Outbox application labels must remain absent: "+name);
            else if(line.startsWith("# HELP "+name+" ") || line.startsWith("# TYPE "+name+" "))retained.add(line);
        }
        assertEquals(names,observed.keySet());assertEquals(1,observed.get("agentic_outbox_observation_healthy"));
        assertTrue(observed.values().stream().allMatch(Double::isFinite));
        for(String name:List.of("agentic_outbox_unpublished","agentic_outbox_failed","agentic_outbox_observation_age_seconds"))
            assertTrue(observed.get(name)>=0,name);
        var path=Path.of("target","observability","http-metrics.prom");Files.createDirectories(path.getParent());
        // Keep only bounded outbox lines, never unrelated application/route/tenant metrics from the same scrape.
        Files.writeString(path,String.join("\n",retained)+"\n");
    }
}
