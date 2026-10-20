package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxMessage;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class StoredCloudEventDecoderTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String HEADER="\"specversion\":\"1.0\",\"source\":\"urn:test:orders\",\"id\":\"one\",\"type\":\"order.ready\"";
    private static StructuredEvent parse(String json) {
        return StructuredEvent.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }
    private static InboxMessage receipt(String json) {
        var event=parse(json);
        return new InboxMessage("trusted-tenant",event.source(),event.id(),event.type(),UlidGenerator.shared().next(),
                "trusted-route",event.canonicalEnvelope());
    }

    @Test void reconstructsTheWholeEnvelopeWithoutNestingItInsideDataOrTrustingRoutingExtensions() throws Exception {
        var stored=receipt("{"+HEADER+",\"time\":\"2026-10-20T00:00:00Z\",\"subject\":\"order/42\","
                +"\"dataschema\":\"urn:test:schema:v1\",\"datacontenttype\":\"application/json\","
                +"\"traceparent\":\"00-untrusted\",\"tenant\":\"forged-tenant\",\"instanceid\":\"forged-target\","
                +"\"correlation\":\"forged-route\",\"data\":{\"order\":42,\"ok\":true}}");
        String fingerprint=stored.fingerprint();
        var decoded=StoredCloudEventDecoder.decode(stored);
        assertEquals(stored.eventId(),decoded.getId());assertEquals(URI.create(stored.source()),decoded.getSource());
        assertEquals(stored.type(),decoded.getType());assertEquals("order/42",decoded.getSubject());
        assertEquals(URI.create("urn:test:schema:v1"),decoded.getDataSchema());
        assertEquals(OffsetDateTime.parse("2026-10-20T00:00:00Z"),decoded.getTime());
        assertEquals("application/json",decoded.getDataContentType());
        assertEquals(JSON.readTree("{\"order\":42,\"ok\":true}"),JSON.readTree(decoded.getData().toBytes()));
        assertEquals("forged-tenant",decoded.getExtension("tenant"));
        assertEquals("forged-target",decoded.getExtension("instanceid"));
        assertEquals("forged-route",decoded.getExtension("correlation"));
        assertEquals("00-untrusted",decoded.getExtension("traceparent"));
        assertEquals("trusted-tenant",stored.tenant());assertEquals("trusted-route",stored.correlationKey());
        assertEquals(fingerprint,stored.fingerprint());
    }
    @Test void preservesBinaryEmptyAndTextDataWithoutTreatingBinaryAsJson() throws Exception {
        var binary=StoredCloudEventDecoder.decode(receipt("{"+HEADER+",\"datacontenttype\":\"application/octet-stream\",\"data_base64\":\"AP+A\"}"));
        assertArrayEquals(new byte[]{0,(byte)255,(byte)128},binary.getData().toBytes());
        assertEquals("application/octet-stream",binary.getDataContentType());
        var empty=StoredCloudEventDecoder.decode(receipt("{"+HEADER+",\"datacontenttype\":\"application/octet-stream\",\"data_base64\":\"\"}"));
        assertArrayEquals(new byte[0],empty.getData().toBytes());
        var text=StoredCloudEventDecoder.decode(receipt("{"+HEADER+",\"data\":\"hello Ω\"}"));
        assertEquals("hello Ω",JSON.readTree(text.getData().toBytes()).textValue());
    }
    @Test void preservesAbsentVersusExplicitNullAndExactJsonNumberData() throws Exception {
        assertNull(StoredCloudEventDecoder.decode(receipt("{"+HEADER+"}")).getData());
        var explicitNull=StoredCloudEventDecoder.decode(receipt("{"+HEADER+",\"data\":null}"));
        assertNotNull(explicitNull.getData());assertTrue(JSON.readTree(explicitNull.getData().toBytes()).isNull());
        var number=StoredCloudEventDecoder.decode(receipt("{"+HEADER+",\"data\":1.000000000000000000001}"));
        assertEquals("1.000000000000000000001",new String(number.getData().toBytes(),StandardCharsets.UTF_8));
    }
    @Test void refusesMismatchedReceiptMetadataBeforeAnySdkPredicateOrCallbackCanUseTheEvent() {
        var stored=receipt("{"+HEADER+",\"data\":{}}");
        for(var changed:new InboxMessage[]{
                new InboxMessage(stored.tenant(),"urn:other",stored.eventId(),stored.type(),stored.instanceId(),stored.correlationKey(),stored.dataJson()),
                new InboxMessage(stored.tenant(),stored.source(),"other",stored.type(),stored.instanceId(),stored.correlationKey(),stored.dataJson()),
                new InboxMessage(stored.tenant(),stored.source(),stored.eventId(),"other.type",stored.instanceId(),stored.correlationKey(),stored.dataJson())})
            assertThrows(IllegalStateException.class,()->StoredCloudEventDecoder.decode(changed));
    }
    @Test void refusesLegacyPayloadOnlyMalformedAmbiguousAndTooDeepStoredJson() {
        var stored=receipt("{"+HEADER+"}");
        for(String invalid:new String[]{"{\"ok\":true}","[]","null","{", "{"+HEADER+"} {}",
                "{"+HEADER+",\"id\":\"duplicate\"}","{"+HEADER+",\"data\":{},\"data_base64\":\"eA==\"}",
                "{"+HEADER+",\"data\":"+"[".repeat(65)+"null"+"]".repeat(65)+"}"}) {
            var changed=new InboxMessage(stored.tenant(),stored.source(),stored.eventId(),stored.type(),stored.instanceId(),stored.correlationKey(),invalid);
            assertThrows(IllegalStateException.class,()->StoredCloudEventDecoder.decode(changed));
        }
    }
    @Test void decodesIndependentlyForEachCallbackWithoutMutatingStoredIdentityOrEnvelope() {
        var stored=receipt("{"+HEADER+",\"datacontenttype\":\"application/octet-stream\",\"data_base64\":\"aGk=\"}");
        String envelope=stored.dataJson(),fingerprint=stored.fingerprint();
        var first=StoredCloudEventDecoder.decode(stored);first.getData().toBytes()[0]=0;
        var second=StoredCloudEventDecoder.decode(stored);
        assertNotSame(first,second);assertArrayEquals("hi".getBytes(StandardCharsets.UTF_8),second.getData().toBytes());
        assertEquals(envelope,stored.dataJson());assertEquals(fingerprint,stored.fingerprint());
    }
}
