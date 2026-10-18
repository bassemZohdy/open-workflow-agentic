package io.github.bassemzohdy.openworkflow.agentic.runtime.api.problem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.api.ProblemDetail;
import jakarta.ws.rs.NotFoundException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProblemDetailMapperTest {

    @Test
    void clientErrorKeepsItsStatusAndMessage() {
        ProblemDetail problem = ProblemDetailMapper.toProblem(new NotFoundException("no such path"));
        assertEquals(404, problem.status());
        assertEquals("Not Found", problem.title());
        assertEquals("no such path", problem.detail());
    }

    @Test
    void serverErrorHidesItsMessage() {
        ProblemDetail problem = ProblemDetailMapper.toProblem(new IllegalStateException("db password is wrong"));
        assertEquals(500, problem.status());
        assertEquals(ProblemDetailMapper.GENERIC_SERVER_DETAIL, problem.detail());
        assertFalse(problem.detail().contains("password"));
    }

    @Test
    void bodyCarriesRfc9457MembersAndTheCorrelationIdAtTopLevel() {
        ProblemDetail problem = ProblemDetail.of(404, "Not Found", "no such path");
        Map<String, Object> body = ProblemDetailMapper.toBody(problem, "req-7");
        assertEquals("about:blank", body.get("type"));
        assertEquals("Not Found", body.get("title"));
        assertEquals(404, body.get("status"));
        assertEquals("no such path", body.get("detail"));
        assertEquals("req-7", body.get("correlationId"));
        assertFalse(body.containsKey("instance"));
    }

    @Test
    void bodyOmitsTheCorrelationIdWhenThereIsNone() {
        Map<String, Object> body = ProblemDetailMapper.toBody(ProblemDetail.of(400, "Bad Request", null), null);
        assertFalse(body.containsKey("correlationId"));
        assertFalse(body.containsKey("detail"));
        assertTrue(body.containsKey("status"));
    }
}
