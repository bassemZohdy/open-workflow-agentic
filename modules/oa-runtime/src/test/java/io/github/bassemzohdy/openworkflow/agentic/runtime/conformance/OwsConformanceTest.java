package io.github.bassemzohdy.openworkflow.agentic.runtime.conformance;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;

/** Baseline is an explicit assessment including gaps, not a green semantic-conformance certificate. */
class OwsConformanceTest {
    @Test void pinnedCorpusIsAssessedAndCurrentResultsMatchTheRecordedBaseline() throws Exception {
        Path root=OwsConformance.repository();var harness=new OwsConformance(root);var report=harness.assess();
        Path generated=Path.of("target","conformance","ows-portability-report.json");Files.createDirectories(generated.getParent());
        String text=OwsConformance.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)+"\n";Files.writeString(generated,text);
        Path baseline=root.resolve("docs/conformance/ows-portability-report.json");
        if(Boolean.getBoolean("ows.updateReport")){Files.createDirectories(baseline.getParent());Files.writeString(baseline,text);}
        assertTrue(Files.exists(baseline),"Generate and inspect assessment with -Dows.updateReport=true");
        assertEquals(OwsConformance.JSON.readTree(Files.readString(baseline)),report,"Actual SDK/schema/roundtrip behavior changed: inspect generated assessment");
        assertEquals(42,report.path("cases").size());assertEquals("NOT_ESTABLISHED",report.path("fullSemanticConformance").asText());
        assertEquals(76,report.path("summary").path("exportAttempts").asInt());
        assertEquals(74,report.path("summary").path("serializationSucceeded").asInt());
        assertEquals(2,report.path("summary").path("serializationFailed").asInt(),"Known metadata failures must stay visible, not count as successful exports");
        int invalid=0;
        for(var sample:report.path("cases")) {
            assertEquals(sample.path("expectedSchema").asText(),sample.path("sourceSchema").path("status").asText(),sample.path("id").asText());
            if(sample.path("expectedSchema").asText().equals("INVALID"))invalid++;
        }
        assertEquals(3,invalid,"Negative schema controls must execute");
        int measured=0;
        for(var sample:report.path("cases"))if(OwsConformance.PURE.contains(sample.path("id").asText())) {
            for(String format:java.util.List.of("source","JSON","YAML")) {
                String state=sample.path("execution").path(format).path("status").asText();
                assertTrue(java.util.Set.of("MATCH","DIVERGED").contains(state),sample.path("id").asText()+" execution did not produce evidence: "+format);measured++;
                if(java.util.Set.of("branch","iteration","ctk-for","pause","catch-error").contains(sample.path("id").asText().replaceFirst("^released-","")))assertEquals("MATCH",state,sample.path("id").asText()+" lost its positive semantic control");
            }
        }
        assertEquals(48,measured);
    }
    @Test void assessmentDetectsRemovedMetadataAndChangedOrderedTasksWithoutReportingValues() throws Exception {
        var before=OwsConformance.JSON.readTree("{\"metadata\":{\"vendor/key\":\"private-value\"},\"do\":[\"first\",\"second\"]}");
        var after=OwsConformance.JSON.readTree("{\"do\":[\"second\",\"first\"]}");var changes=OwsConformance.JSON.createArrayNode();
        OwsConformance.diff(before,after,"",changes);
        assertEquals(OwsConformance.JSON.readTree("[{\"path\":\"/do/0\",\"kind\":\"VALUE_CHANGED\"},{\"path\":\"/do/1\",\"kind\":\"VALUE_CHANGED\"},{\"path\":\"/metadata\",\"kind\":\"REMOVED\"}]"),changes);
        assertFalse(changes.toString().contains("private-value"));assertEquals("vendor~1key~0",OwsConformance.escape("vendor/key~"));
    }
    @Test void arbitraryPayloadKeysCannotInventVendorDependencies() throws Exception {
        var source=OwsConformance.JSON.readTree("{\"document\":{\"metadata\":{\"vendor/flag\":true}},\"do\":[{\"copy\":{\"set\":{\"call\":\"private-value\",\"run\":{},\"metadata\":{}},\"metadata\":{\"vendor.example\":false}}}]}");
        var inventory=OwsConformance.JSON.createArrayNode();OwsConformance.inventoryWorkflow(source,inventory);
        assertEquals(2,inventory.size());assertTrue(inventory.toString().contains("/document/metadata/vendor~1flag"));
        assertFalse(inventory.toString().contains("CALL_PROVIDER"));assertFalse(inventory.toString().contains("private-value"));
    }
    @Test void pureSubsetRejectsProviderOrResourceExecutionButPermitsOpaqueData() throws Exception {
        var source=OwsConformance.JSON.readTree("{\"do\":[{\"container\":{\"do\":[{\"fetch\":{\"call\":\"http\"}}]}}]}");
        assertThrows(IllegalArgumentException.class,()->OwsConformance.requirePure(source));
        assertThrows(IllegalArgumentException.class,()->OwsConformance.requirePure(OwsConformance.JSON.readTree("{\"use\":{\"extensions\":[{}]},\"do\":[]}")));
        assertThrows(IllegalArgumentException.class,()->OwsConformance.requirePure(OwsConformance.JSON.readTree("{\"input\":{\"schema\":{\"resource\":\"https://example.invalid/schema\"}},\"do\":[]}")));
        OwsConformance.requirePure(OwsConformance.JSON.readTree("{\"do\":[{\"copy\":{\"set\":{\"call\":\"opaque\",\"run\":{}}}}]}"));
    }
    @Test void selectedOfficialCtkInputWorkflowAndOutputMatchThePinnedFeature() throws Exception {
        var harness=new OwsConformance(OwsConformance.repository());JsonNode selected=null;
        for(var entry:harness.manifest.path("cases"))if(entry.path("id").asText().equals("ctk-for"))selected=entry;
        assertNotNull(selected);String feature=OwsConformance.resource("upstream/for.feature");
        assertEquals(selected.path("upstream").path("sha256").asText(),OwsConformance.sha(feature));
        var blocks=java.util.regex.Pattern.compile("\"\"\"yaml\\R(.*?)\\R\\s*\"\"\"",java.util.regex.Pattern.DOTALL).matcher(feature);
        java.util.List<JsonNode> data=new java.util.ArrayList<>();while(blocks.find())data.add(OwsConformance.YAML.readTree(blocks.group(1).stripIndent()));
        assertEquals(3,data.size());assertEquals(data.get(0),OwsConformance.YAML.readTree(OwsConformance.resource(selected.path("source").asText())));
        assertEquals(data.get(1),selected.path("execution").path("input"));assertEquals(data.get(2),selected.path("execution").path("expected"));
    }
    @Test void releasedExecutionVariantsChangeOnlyDeclaredDialectAndArtifactVersion() throws Exception {
        var harness=new OwsConformance(OwsConformance.repository());var entries=new java.util.HashMap<String,JsonNode>();
        harness.manifest.path("cases").forEach(e->entries.put(e.path("id").asText(),e));int variants=0;
        for(var entry:entries.values())if(entry.has("derivedFrom")) {
            var original=entries.get(entry.path("derivedFrom").asText());assertNotNull(original);
            var expected=OwsConformance.YAML.readTree(OwsConformance.resource(original.path("source").asText()));
            ((com.fasterxml.jackson.databind.node.ObjectNode)expected.path("document")).put("dsl","1.0.1").put("version","1.0.1");
            assertEquals(expected,OwsConformance.YAML.readTree(OwsConformance.resource(entry.path("source").asText())));
            assertEquals(original.path("execution"),entry.path("execution"));variants++;
        }
        assertEquals(8,variants);
    }
}
