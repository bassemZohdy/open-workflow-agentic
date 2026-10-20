package io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.source;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.source.WorkflowSourceCodec.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.serverlessworkflow.api.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Exercises the production offline codec, not an alternative workflow engine. */
class WorkflowSourceCodecTest {
    final WorkflowSourceCodec codec=new WorkflowSourceCodec();
    WorkflowSourceCodecTest() throws Exception { }
    static final String BASIC="""
            # authored comment survives in original source
            document: {dsl: '1.0.3', namespace: authoring, name: source, version: '1.0.0'}
            do:
              - copy:
                  set: {message: 'é 😀'}
            """;
    static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    Imported read(String s){return codec.importSource(bytes(s),Format.YAML,Profile.DEVELOPMENT_1_0_3);}
    static byte[] resource(String path) throws Exception {
        try(var in=WorkflowSourceCodecTest.class.getResourceAsStream("/conformance/"+path)){assertNotNull(in);return in.readAllBytes();}
    }
    static JsonNode tree(byte[] data,Format format) throws Exception {
        return (format==Format.JSON?new ObjectMapper():new ObjectMapper(YAMLFactory.builder()
                .enable(com.fasterxml.jackson.dataformat.yaml.YAMLParser.Feature.PARSE_BOOLEAN_LIKE_WORDS_AS_STRINGS).build()))
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).readTree(data);
    }
    static void rejected(Imported imported,Code code){assertFalse(imported.schemaAccepted());assertTrue(imported.diagnostics().stream().anyMatch(d->d.code()==code),imported.diagnostics().toString());}
    @Test void exactOriginalRetainsBomCrLfCommentsUnicodeAndByteFingerprintWithDefensiveCopies() throws Exception {
        byte[] source=bytes("\ufeff"+BASIC.replace("\n","\r\n"));byte[] expected=source.clone();
        Imported imported=codec.importSource(source,Format.YAML,Profile.DEVELOPMENT_1_0_3);assertTrue(imported.schemaAccepted(),imported.diagnostics().toString());
        source[0]=0;assertArrayEquals(expected,imported.original());byte[] returned=imported.original();returned[1]=0;
        assertArrayEquals(expected,imported.original());assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected)),imported.originalSha256());
        var exported=codec.exportOriginal(imported);assertTrue(exported.succeeded());assertTrue(exported.exactSource());
        byte[] output=exported.bytes();output[0]=0;assertArrayEquals(expected,exported.bytes());assertEquals("EXACT_ORIGINAL_BYTES",exported.presentationNotice());
    }
    @Test void formatConversionRequiresAcknowledgementAndRetainsOriginalAndParsedData() throws Exception {
        var imported=read(BASIC);assertTrue(imported.schemaAccepted());
        assertThrows(IllegalArgumentException.class,()->codec.export(imported,Format.JSON,ConversionPolicy.PRESERVE_ORIGINAL));
        var converted=codec.export(imported,Format.JSON,ConversionPolicy.ALLOW_FORMATTING_CHANGES);assertTrue(converted.succeeded());assertFalse(converted.exactSource());
        assertEquals(tree(bytes(BASIC),Format.YAML),tree(converted.bytes(),Format.JSON));assertTrue(converted.presentationNotice().contains("COMMENTS_NOT_PRESERVED"));
        assertArrayEquals(bytes(BASIC),imported.original());assertEquals(imported.originalSha256(),converted.originalSha256());
    }
    @Test void nestedOpaqueMetadataAndReservedRuntimeTypeKeysRemainPlainData() throws Exception {
        String source=BASIC+"      metadata:\n        vendor.example.review:\n          enabled: false\n          values: [é, 1, null, {type: java.lang.Runtime, value: opaque}]\n";
        var imported=read(source);assertTrue(imported.schemaAccepted(),imported.diagnostics().toString());
        for(var format:Format.values()) {
            var converted=codec.export(imported,format,ConversionPolicy.ALLOW_FORMATTING_CHANGES);assertTrue(converted.succeeded(),converted.diagnostics().toString());
            assertEquals(tree(bytes(source),Format.YAML),tree(converted.bytes(),format));
        }
    }
    @Test void invalidUtf8IsNeverReplacedAndOriginalBytesRemainRecoverable() {
        byte[] malformed={(byte)0xc3,0x28};var imported=codec.importSource(malformed,Format.JSON,Profile.RELEASED_1_0_1);
        rejected(imported,Code.INVALID_UTF8);assertArrayEquals(malformed,codec.exportOriginal(imported).bytes());
        var conversion=codec.export(imported,Format.YAML,ConversionPolicy.ALLOW_FORMATTING_CHANGES);assertFalse(conversion.succeeded());assertEquals(0,conversion.bytes().length);
    }
    @Test void duplicateJsonAndYamlKeysAreRefusedBeforeAValueCanBeOverwritten() {
        rejected(codec.importSource(bytes("{\"document\":{},\"document\":{}}"),Format.JSON,Profile.RELEASED_1_0_1),Code.MALFORMED_SOURCE);
        rejected(read("document: {}\ndocument: {}\n"),Code.MALFORMED_SOURCE);
    }
    @Test void trailingJsonAndAdditionalYamlDocumentsAreRefused() {
        rejected(codec.importSource(bytes("{} {}"),Format.JSON,Profile.RELEASED_1_0_1),Code.MALFORMED_SOURCE);
        rejected(read(BASIC+"---\n{}\n"),Code.MALFORMED_SOURCE);
    }
    @Test void aliasesAreExplicitlyUnsupportedAndOriginalSourceStillExportsExactly() throws Exception {
        byte[] source=resource("cases/comments-and-aliases.yaml");var imported=codec.importSource(source,Format.YAML,Profile.DEVELOPMENT_1_0_3);
        rejected(imported,Code.YAML_ALIAS_UNSUPPORTED);assertArrayEquals(source,codec.exportOriginal(imported).bytes());
        assertFalse(codec.export(imported,Format.JSON,ConversionPolicy.ALLOW_FORMATTING_CHANGES).succeeded());
    }
    @Test void yamlTypeTagsBinaryAndNonFiniteNumbersCannotBecomeRuntimeObjectsOrChangedJsonValues() {
        rejected(read(BASIC+"tagged: !java.lang.Runtime value\n"),Code.YAML_TAG_UNSUPPORTED);
        assertFalse(read(BASIC+"tagged: !!binary SGVsbG8=\n").schemaAccepted());
        // This pinned YAML parser refuses these spellings before producing a numeric token.
        rejected(read(BASIC+"number: .nan\n"),Code.MALFORMED_SOURCE);
        rejected(read(BASIC+"number: .inf\n"),Code.MALFORMED_SOURCE);
    }
    @Test void escapedUnpairedSurrogatesAreRejectedButValidUnicodeSurvives() {
        rejected(codec.importSource(bytes("{\"value\":\"\\uD800\"}"),Format.JSON,Profile.RELEASED_1_0_1),Code.INVALID_UNICODE);
        // The UTF-8 JSON parser itself refuses a surrogate field name before our text validation.
        rejected(codec.importSource(bytes("{\"\\uDC00\":1}"),Format.JSON,Profile.RELEASED_1_0_1),Code.MALFORMED_SOURCE);
        assertTrue(read(BASIC).schemaAccepted());
    }
    @Test void malformedAndSchemaDiagnosticsNeverEchoCallerValuesOrExceptionMessages() {
        String privateValue="PRIVATE-CREDENTIAL-DO-NOT-LOG!";
        var invalid=read(BASIC.replace("name: source","name: "+privateValue));rejected(invalid,Code.SCHEMA_INVALID);
        assertFalse(invalid.diagnostics().toString().contains(privateValue));assertTrue(invalid.diagnostics().size()<=32);
        var malformed=read("document: ["+privateValue);rejected(malformed,Code.MALFORMED_SOURCE);assertFalse(malformed.diagnostics().toString().contains(privateValue));
        assertArrayEquals(bytes("document: ["+privateValue),codec.exportOriginal(malformed).bytes());
    }
    @Test void callerSelectsReleasedOrDevelopmentSchemaWithoutRewritingDeclaredDialect() throws Exception {
        byte[] source=resource("cases/emit-event.yaml");var dev=codec.importSource(source,Format.YAML,Profile.DEVELOPMENT_1_0_3);
        assertTrue(dev.schemaAccepted(),dev.diagnostics().toString());var release=codec.importSource(source,Format.YAML,Profile.RELEASED_1_0_1);rejected(release,Code.SCHEMA_INVALID);
        var converted=codec.export(dev,Format.JSON,ConversionPolicy.ALLOW_FORMATTING_CHANGES);assertTrue(converted.succeeded());
        assertEquals("1.0.3",tree(converted.bytes(),Format.JSON).path("document").path("dsl").asText());
    }
    @Test void arbitraryPrecisionNumbersAreNotConvertedThroughDoubleOrTruncated() throws Exception {
        String source=BASIC+"documentedValues: [1234567890123456789012345678901234567890, 0.123456789012345678901234567890, 1.0e1000]\n";
        var imported=read(source);assertTrue(imported.schemaAccepted(),imported.diagnostics().toString());
        var converted=codec.export(imported,Format.JSON,ConversionPolicy.ALLOW_FORMATTING_CHANGES);assertTrue(converted.succeeded(),converted.diagnostics().toString());
        assertEquals(tree(bytes(source),Format.YAML),tree(converted.bytes(),Format.JSON));
    }
    @Test void booleanLikeWordsRemainStringsAndTrueFalseRemainBooleans() throws Exception {
        var imported=read(BASIC+"values: [on, off, yes, no, true, false]\n");assertTrue(imported.schemaAccepted());
        var output=codec.export(imported,Format.JSON,ConversionPolicy.ALLOW_FORMATTING_CHANGES);assertTrue(output.succeeded());
        assertEquals(new ObjectMapper().readTree("[\"on\",\"off\",\"yes\",\"no\",true,false]"),tree(output.bytes(),Format.JSON).path("values"));
    }
    @Test void byteDepthAndTokenLimitsAreEnforcedBeforeUnboundedTreeOrSchemaWork() {
        assertThrows(IllegalArgumentException.class,()->codec.importSource(new byte[MAX_BYTES+1],Format.JSON,Profile.RELEASED_1_0_1));
        assertThrows(IllegalArgumentException.class,()->codec.importSource(new byte[0],Format.JSON,Profile.RELEASED_1_0_1));
        rejected(read("[".repeat(70)+"0"+"]".repeat(70)),Code.MALFORMED_SOURCE);
        rejected(read("["+"0,".repeat(100_001)+"0]"),Code.TOKEN_LIMIT);
    }
    @Test void noExternalWorkflowSchemaUriIsResolvedOrWorkflowExecutedByImportOrExport() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(),0),0);
        try {
            var requests=new java.util.concurrent.atomic.AtomicInteger();server.createContext("/",exchange->{requests.incrementAndGet();exchange.sendResponseHeaders(500,-1);exchange.close();});server.start();
            String source=BASIC+"input:\n  schema:\n    resource:\n      endpoint: http://127.0.0.1:"+server.getAddress().getPort()+"/schema\n";
            var imported=read(source);assertTrue(imported.schemaAccepted(),imported.diagnostics().toString());assertTrue(codec.export(imported,Format.JSON,ConversionPolicy.ALLOW_FORMATTING_CHANGES).succeeded());assertEquals(0,requests.get());
        }finally{server.stop(0);}
    }
    @Test void originalGlobalSdkCheckpointSerializerAndKnownWriterFailureAreUnchanged() throws Exception {
        var model=WorkflowReader.readWorkflow(resource("cases/schedule-and-metadata.yaml"),WorkflowFormat.YAML);
        String before=WorkflowFormat.JSON.mapper().getSerializerProviderInstance().findValueSerializer(io.serverlessworkflow.api.types.TaskMetadata.class).getClass().getName();
        assertThrows(java.io.IOException.class,()->WorkflowWriter.workflowAsString(model,WorkflowFormat.JSON));
        var imported=codec.importSource(resource("cases/schedule-and-metadata.yaml"),Format.YAML,Profile.DEVELOPMENT_1_0_3);
        assertTrue(codec.export(imported,Format.JSON,ConversionPolicy.ALLOW_FORMATTING_CHANGES).succeeded());
        assertEquals(before,WorkflowFormat.JSON.mapper().getSerializerProviderInstance().findValueSerializer(io.serverlessworkflow.api.types.TaskMetadata.class).getClass().getName());
        assertThrows(java.io.IOException.class,()->WorkflowWriter.workflowAsString(model,WorkflowFormat.JSON));
    }
    @Test void allPinnedCorpusSourcesRetainOriginalsAndOnlyLosslessSchemaAcceptedDataConverts() throws Exception {
        var manifest=new ObjectMapper().readTree(resource("cases.json"));var report=new ObjectMapper().createObjectNode();var cases=report.putArray("cases");
        Path retained=Path.of("target/conformance/source-codec");Files.createDirectories(retained.resolve("originals"));Files.createDirectories(retained.resolve("exports"));
        Path repository=Path.of("").toAbsolutePath();while(!Files.isRegularFile(repository.resolve("docs/plan/work-packages.json")))repository=Objects.requireNonNull(repository.getParent());
        int accepted=0,converted=0;
        for(var entry:manifest.path("cases")) {
            byte[] source=entry.has("repositoryPath")?Files.readAllBytes(repository.resolve(entry.path("repositoryPath").asText())):resource(entry.path("source").asText());
            var imported=codec.importSource(source,Format.YAML,Profile.DEVELOPMENT_1_0_3);assertArrayEquals(source,codec.exportOriginal(imported).bytes());
            boolean alias=entry.path("id").asText().equals("comments-and-aliases");assertEquals(entry.path("schemaExpected").asText().equals("VALID")&&!alias,imported.schemaAccepted(),entry.path("id").asText()+imported.diagnostics());
            if(imported.schemaAccepted())accepted++;
            var item=cases.addObject().put("id",entry.path("id").asText()).put("originalSha256",imported.originalSha256()).put("schemaAccepted",imported.schemaAccepted());
            String id=entry.path("id").asText();assertTrue(id.matches("[a-z0-9-]+"));
            Files.write(retained.resolve("originals/"+id+".yaml"),codec.exportOriginal(imported).bytes());
            item.set("diagnostics",new ObjectMapper().valueToTree(imported.diagnostics()));
            for(var format:Format.values()) {
                var out=codec.export(imported,format,ConversionPolicy.ALLOW_FORMATTING_CHANGES);assertEquals(imported.schemaAccepted(),out.succeeded(),entry.path("id").asText()+out.diagnostics());
                if(out.succeeded()) {
                    converted++;assertEquals(tree(source,Format.YAML),tree(out.bytes(),format));
                    Files.write(retained.resolve("exports/"+id+"."+format.name().toLowerCase(Locale.ROOT)),out.bytes());
                }
                item.put(format.name()+"Converted",out.succeeded());
            }
        }
        assertEquals(42,cases.size());assertEquals(38,accepted);assertEquals(76,converted);
        report.putObject("summary").put("sources",cases.size()).put("originalsRetained",cases.size()).put("schemaAccepted",accepted).put("converted",converted)
                .put("scope","Offline source data conversion only; no SDK compatibility, execution, activation or full conformance proof");
        Path output=Path.of("target/conformance/source-codec-report.json");Files.createDirectories(output.getParent());Files.writeString(output,report.toPrettyString()+"\n");
    }
    @Test void packagedMainSchemasAndLicenseAreTheExactIndependentlyPinnedAssessmentBytes() throws Exception {
        Path root=Path.of("").toAbsolutePath();while(!Files.isRegularFile(root.resolve("docs/plan/work-packages.json")))root=Objects.requireNonNull(root.getParent());
        for(String name:List.of("workflow-1.0.1.yaml","workflow-1.0.3.yaml","LICENSE")) {
            byte[] main=Files.readAllBytes(root.resolve("modules/oa-runtime/src/main/resources/conformance/schema/"+name));
            assertArrayEquals(resource("schema/"+name),main,name);
        }
    }
}
