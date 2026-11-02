package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.DefinitionRef;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StartRequest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventStartBindingTest {
    private static final Subject OWNER = new Subject(SubjectKind.USER, "owner");
    private static final Subject PRODUCER = new Subject(SubjectKind.SERVICE, "producer");
    private static final Ulid GRANT = UlidGenerator.shared().next();
    private static StructuredEvent event(String data) {
        String body = "{\"specversion\":\"1.0\",\"source\":\"urn:test\",\"id\":\"e-1\","
                + "\"type\":\"approved\",\"data\":" + data + "}";
        return StructuredEvent.read(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }
    private static StartRequest request(StructuredEvent e, Subject owner, String version, Map<String,Object> input) {
        return new StartRequest("tenant", owner, new DefinitionRef("demo/start", version), input,
                EventStartBinding.startKey("tenant", e.source(), e.id()));
    }
    private static EventStartBinding binding(StructuredEvent e, StartRequest r, Subject producer, Ulid grant,
                                             String route, String routeVersion) {
        return new EventStartBinding(e, r, producer, grant, route, routeVersion);
    }

    @Test void redeliveryKeepsKeyAndCompleteContractDetectsChangedFacts() {
        var e = event("{\"n\":1}"); var r = request(e, OWNER, "1", Map.of("x", 1));
        var original = binding(e, r, PRODUCER, GRANT, "route", "1");
        assertEquals(original.fingerprint(), binding(event("{\"n\":1}"), r, PRODUCER, GRANT, "route", "1").fingerprint());
        assertEquals(original.startKey(), binding(event("{\"n\":2}"), r, PRODUCER, GRANT, "route", "1").startKey());
        assertNotEquals(original.fingerprint(), binding(event("{\"n\":2}"), r, PRODUCER, GRANT, "route", "1").fingerprint());
        assertNotEquals(original.fingerprint(), binding(e, request(e, OWNER, "2", Map.of("x", 1)), PRODUCER, GRANT, "route", "1").fingerprint());
        assertNotEquals(original.fingerprint(), binding(e, request(e, OWNER, "1", Map.of("x", 2)), PRODUCER, GRANT, "route", "1").fingerprint());
        assertNotEquals(original.fingerprint(), binding(e, request(e, new Subject(SubjectKind.SERVICE, "owner"), "1", Map.of("x", 1)), PRODUCER, GRANT, "route", "1").fingerprint());
        assertNotEquals(original.fingerprint(), binding(e, r, new Subject(SubjectKind.USER, "producer"), GRANT, "route", "1").fingerprint());
        assertNotEquals(original.fingerprint(), binding(e, r, PRODUCER, UlidGenerator.shared().next(), "route", "1").fingerprint());
        assertNotEquals(original.fingerprint(), binding(e, r, PRODUCER, GRANT, "route", "2").fingerprint());
        assertEquals("EventStartBinding[redacted]", original.toString());
    }

    @Test void identityIsScopedAndInjectivelyEncodedAndBadKeysRefuse() {
        var e = event("null");
        assertNotEquals(EventStartBinding.startKey("a", "urn:x", "y"), EventStartBinding.startKey("a", "urn:xy", "y"));
        assertNotEquals(EventStartBinding.startKey("a", "urn:x", "y"), EventStartBinding.startKey("b", "urn:x", "y"));
        assertNotEquals(EventStartBinding.startKey("a", "urn:x", "y:z"), EventStartBinding.startKey("a", "urn:x:y", "z"));
        assertThrows(IllegalArgumentException.class, () -> EventStartBinding.startKey("tenant", "urn:x", "\ud800"));
        var wrong = new StartRequest("tenant", OWNER, new DefinitionRef("demo/start", "1"), Map.of(), "caller-key");
        assertThrows(IllegalArgumentException.class, () -> binding(e, wrong, PRODUCER, GRANT, "route", "1"));
        var retry = new StartRequest("tenant", OWNER, new DefinitionRef("demo/start", "1"), Map.of(),
                EventStartBinding.startKey("tenant", e.source(), e.id()), UlidGenerator.shared().next());
        assertThrows(IllegalArgumentException.class, () -> binding(e, retry, PRODUCER, GRANT, "route", "1"));
        assertThrows(NullPointerException.class, () -> binding(e, request(e, OWNER, "1", Map.of()), PRODUCER, null, "route", "1"));
    }
}
