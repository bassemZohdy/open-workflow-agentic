package io.github.bassemzohdy.openworkflow.agentic.runtime.conformance;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.serverlessworkflow.api.*;
import io.serverlessworkflow.impl.*;
import io.serverlessworkflow.impl.lifecycle.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Executes unchanged official definitions with controlled HTTP/OpenAPI/event providers.
 * This strict pinned-feature reader is deliberately not a general Gherkin implementation. */
class OfficialCtkTest {
    record Step(String text,String block) { }
    record Scenario(String feature,String name,List<Step> steps) { }
    record Observation(String status,JsonNode output,JsonNode error,List<String> tasks) { }
    static final String PREFIX="upstream/ctk/";
    static final Pattern ORDER=Pattern.compile("And (\\w+) should run (first|last|after \\w+)");
    static final Pattern PROPERTIES=Pattern.compile("And the workflow output should have properties ('.+'(?:, '.+')*)");
    static final Pattern VALUE=Pattern.compile("And the workflow output should have a '([^']+)' property with value:");
    static final Pattern COUNT=Pattern.compile("And the workflow output should have a '([^']+)' property containing (\\d+) items");

    static List<Scenario> parse(String file,String text) {
        var scenarios=new ArrayList<Scenario>();String name=null;var steps=new ArrayList<Step>();
        String[] lines=text.split("\\R",-1);
        for(int i=0;i<lines.length;i++) {
            String line=lines[i].strip();
            if(line.startsWith("Scenario:")) {
                if(name!=null)scenarios.add(new Scenario(file,name,List.copyOf(steps)));
                name=line.substring(9).strip();steps.clear();continue;
            }
            if(line.isEmpty()||line.startsWith("#")||name==null)continue;
            if(!line.matches("(?:Given|And|When|Then) .*"))throw new IllegalArgumentException("Unknown CTK line: "+line);
            String block=null;
            if(i+1<lines.length&&lines[i+1].strip().equals("\"\"\"yaml")) {
                i+=2;var raw=new StringBuilder();
                while(i<lines.length&&!lines[i].strip().equals("\"\"\""))raw.append(lines[i++]).append('\n');
                if(i==lines.length)throw new IllegalArgumentException("Unclosed CTK docstring");
                block=raw.toString().stripIndent();
            }
            validateStep(line,block);steps.add(new Step(line,block));
        }
        if(name!=null)scenarios.add(new Scenario(file,name,List.copyOf(steps)));
        return scenarios;
    }
    static void validateStep(String text,String block) {
        boolean needsBlock=Set.of("Given a workflow with definition:","And given the workflow input is:",
                "Then the workflow should complete with output:","Then the workflow should fault with error:").contains(text)
                ||VALUE.matcher(text).matches();
        boolean plain=Set.of("When the workflow is executed","Then the workflow should complete","Then the workflow should fault").contains(text)
                ||ORDER.matcher(text).matches()||PROPERTIES.matcher(text).matches()||COUNT.matcher(text).matches();
        if(!(needsBlock||plain)||needsBlock!=(block!=null))throw new IllegalArgumentException("Unsupported CTK step/docstring: "+text);
    }
    static JsonNode yaml(String text) throws Exception {return OwsConformance.YAML.readTree(text);}
    static Observation execute(String source,JsonNode input) throws Exception {
        OwsConformance.requirePure(yaml(source)); // fail closed before constructing an executable definition
        return execute(source,input,WorkflowApplication.builder(),null);
    }
    static Observation execute(String source,JsonNode input,WorkflowApplication.Builder builder,CtkProviders providers) throws Exception {
        var tasks=new CopyOnWriteArrayList<String>();
        var listener=new WorkflowExecutionListener() {
            public void onTaskStarted(TaskStartedEvent e){tasks.add(e.taskContext().taskName());}
        };
        try(var app=builder.withListener(listener).disableLifeCycleCEPublishing().disableStatusChangePublishing().build()) {
            if(providers!=null)providers.verifyBindings(app); // BEFORE definition construction or any execution
            var instance=app.workflowDefinition(WorkflowReader.readWorkflowFromString(source,WorkflowFormat.YAML))
                    .instance(OwsConformance.JSON.convertValue(input,Object.class));
            JsonNode output=NullNode.instance,error=NullNode.instance;
            try {output=OwsConformance.JSON.valueToTree(instance.start().get(3,TimeUnit.SECONDS).asJavaObject());}
            catch(ExecutionException failed) {
                if(!(failed.getCause() instanceof WorkflowException wf))throw failed;
                var e=wf.getWorkflowError();error=OwsConformance.JSON.createObjectNode().put("type",e.type()).put("status",e.status())
                        .put("title",e.title()).put("instance",e.instance()).put("detail",e.detail());
            }
            return new Observation(instance.status().name(),output,error,List.copyOf(tasks));
        }
    }
    static JsonNode property(JsonNode output,String dotted) {
        for(String key:dotted.split("\\."))output=output.path(key);return output;
    }
    static boolean assertion(Step step,Observation actual) throws Exception {
        String text=step.text();
        if(text.equals("Then the workflow should complete"))return actual.status().equals("COMPLETED");
        if(text.equals("Then the workflow should fault"))return actual.status().equals("FAULTED");
        if(text.equals("Then the workflow should complete with output:"))return actual.status().equals("COMPLETED")&&yaml(step.block()).equals(actual.output());
        if(text.equals("Then the workflow should fault with error:")) {
            var expected=yaml(step.block());boolean match=actual.status().equals("FAULTED");
            for(var fields=expected.fields();fields.hasNext();) {var e=fields.next();match&=e.getValue().equals(actual.error().path(e.getKey()));}
            return match;
        }
        var order=ORDER.matcher(text);
        if(order.matches()) {
            int index=actual.tasks().indexOf(order.group(1));if(index<0)return false;
            return switch(order.group(2)) {
                case "first" -> index==0;
                case "last" -> index==actual.tasks().size()-1;
                default -> actual.tasks().indexOf(order.group(2).substring(6))>=0&&index>actual.tasks().indexOf(order.group(2).substring(6));
            };
        }
        var properties=PROPERTIES.matcher(text);
        if(properties.matches()) {
            var names=Pattern.compile("'([^']+)'").matcher(properties.group(1));boolean match=true;
            while(names.find())match&=!property(actual.output(),names.group(1)).isMissingNode();return match;
        }
        var value=VALUE.matcher(text);if(value.matches())return yaml(step.block()).equals(property(actual.output(),value.group(1)));
        var count=COUNT.matcher(text);if(count.matches()) {
            var items=property(actual.output(),count.group(1));return items.isArray()&&items.size()==Integer.parseInt(count.group(2));
        }
        throw new IllegalArgumentException("Not an assertion: "+text);
    }
    @Test void allPinnedOfficialScenariosRunUnchangedWithPureOrControlledProvidersAndEveryAssertionIsMeasured() throws Exception {
        try (var nativeSdk = new NativeSdkUriResolverScope()) {
            measure(false);
        }
    }
    @Test void registeredPlatformRuntimeExecutesEveryUnchangedPinnedScenarioSeparatelyFromNativeSdkMeasurement() throws Exception {
        try (var app = WorkflowApplication.builder().build()) {
            assertInstanceOf(io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.source.ComponentUriTemplateResolver.class,
                    app.templateResolver().orElseThrow());
        }
        measure(true);
        var nativeReport=OwsConformance.JSON.readTree(Files.readString(OwsConformance.repository().resolve("docs/conformance/official-ctk-report.json")));
        var platformReport=OwsConformance.JSON.readTree(Files.readString(Path.of("target/conformance/platform-ctk/report.json")));
        int differences=0;
        for(int index=0;index<21;index++) {
            var upstream=nativeReport.path("scenarios").get(index);var adapted=platformReport.path("scenarios").get(index);
            assertEquals(upstream.path("definitionSha256"),adapted.path("definitionSha256"));
            if(!upstream.equals(adapted)) {
                differences++;assertEquals("Call HTTP With Content Output",adapted.path("scenario").asText());
                assertEquals("DIVERGED",upstream.path("status").asText());assertEquals("MATCH",adapted.path("status").asText());
            }
        }
        assertEquals(1,differences,"Do not hide another semantic change in the URI adapter assessment");
    }
    private void measure(boolean platform) throws Exception {
        var manifest=OwsConformance.JSON.readTree(OwsConformance.resource(PREFIX+"manifest.json"));
        var scenarios=new ArrayList<Scenario>();int features=0;
        for(var file:manifest.path("files")) {
            String path=file.path("path").asText();byte[] bytes;
            try(var in=getClass().getResourceAsStream("/conformance/"+PREFIX+path)){assertNotNull(in);bytes=in.readAllBytes();}
            assertEquals(file.path("bytes").asInt(),bytes.length,path);
            assertEquals(file.path("sha256").asText(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),path);
            if(path.endsWith(".feature")){features++;scenarios.addAll(parse(path,new String(bytes,StandardCharsets.UTF_8)));}
        }
        assertEquals(11,features);assertEquals(21,scenarios.size());
        var report=OwsConformance.JSON.createObjectNode().put("specCommit",manifest.path("commit").asText())
                .put("sdkVersion","7.35.3.Final").put("fullSemanticConformance","NOT_ESTABLISHED")
                .put("scope","All pinned development CTK scenarios attempted unchanged with pure or controlled HTTP/OpenAPI/event providers")
                .put("publicProviderInteroperability","NOT_ESTABLISHED: loopback transport substitution, pinned document and in-memory event delivery");
        if(platform)report.put("runtimeAdapter","ComponentUriTemplateResolver: packaged service provider; upstream SDK measurement remains separate");
        var results=report.putArray("scenarios");int executed=0,unmeasured=0,matched=0,diverged=0,assertions=0;
        Path artifacts=Path.of("target/conformance/"+(platform?"platform-ctk":"official-ctk"));Files.createDirectories(artifacts);
        for(int index=0;index<scenarios.size();index++) {
            var scenario=scenarios.get(index);String source=null;JsonNode input=OwsConformance.JSON.createObjectNode();int acts=0;
            for(var step:scenario.steps()) {
                if(step.text().equals("Given a workflow with definition:")){assertNull(source);source=step.block();}
                if(step.text().equals("And given the workflow input is:"))input=yaml(step.block());
                if(step.text().equals("When the workflow is executed"))acts++;
            }
            assertNotNull(source);assertEquals(1,acts);var checks=scenario.steps().stream().filter(s->s.text().startsWith("Then ")
                    ||(s.text().startsWith("And ")&&!s.text().equals("And given the workflow input is:"))).toList();assertFalse(checks.isEmpty());
            var item=results.addObject().put("feature",scenario.feature()).put("scenario",scenario.name()).put("definitionSha256",OwsConformance.sha(source));
            var checked=item.putArray("assertions");
            boolean external=false;
            try {OwsConformance.requirePure(yaml(source));}catch(IllegalArgumentException unavailable){external=true;}
            ObjectNode providerObservation=null;Observation actual;
            if(external) {
                CtkProviders.requireControlledSource(yaml(source));
                try(var providers=new CtkProviders()) {
                    actual=execute(source,input,providers.configure(WorkflowApplication.builder()),providers);providerObservation=providers.observation();
                    item.set("providerSummary",providers.summary());
                }
                item.put("provider","CONTROLLED_HTTP_OPENAPI_EVENT");
            }else {actual=execute(source,input);item.put("provider","PURE");}
            executed++;boolean match=true;
            for(var check:checks){boolean ok=assertion(check,actual);match&=ok;assertions++;checked.addObject().put("step",check.text()).put("status",ok?"MATCH":"DIVERGED");}
            item.put("status",match?"MATCH":"DIVERGED");if(match)matched++;else diverged++;
            String artifact=String.format(Locale.ROOT,"scenario-%02d.json",index+1);item.put("observation",artifact);
            var observation=OwsConformance.JSON.createObjectNode().put("workflowStatus",actual.status());
            observation.set("output",actual.output());observation.set("error",actual.error());observation.set("taskStarts",OwsConformance.JSON.valueToTree(actual.tasks()));
            if(providerObservation!=null)observation.set("provider",providerObservation);
            Files.writeString(artifacts.resolve(artifact),OwsConformance.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(observation)+"\n");
        }
        report.putObject("summary").put("features",features).put("scenarios",scenarios.size()).put("executed",executed)
                .put("unmeasured",unmeasured).put("matched",matched).put("diverged",diverged).put("measuredAssertions",assertions);
        assertEquals(21,executed);assertEquals(0,unmeasured);assertEquals(45,assertions);
        assertEquals(platform?15:14,matched);assertEquals(platform?6:7,diverged);
        String text=OwsConformance.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)+"\n";
        Files.writeString(artifacts.resolve("report.json"),text);
        Path baseline=OwsConformance.repository().resolve("docs/conformance/"+(platform?"platform-ctk-report.json":"official-ctk-report.json"));
        if(Boolean.getBoolean("ows.updateReport"))Files.writeString(baseline,text);
        assertEquals(OwsConformance.JSON.readTree(Files.readString(baseline)),report,"Inspect actual CTK observations; never silently bless changed semantics");
    }
    @Test void unknownStepsAndUnclosedDocstringsFailInsteadOfDisappearingFromCoverage() {
        assertThrows(IllegalArgumentException.class,()->parse("control","Scenario: Test\nThen the workflow should magically pass\n"));
        assertThrows(IllegalArgumentException.class,()->parse("control","Scenario: Test\nGiven a workflow with definition:\n\"\"\"yaml\ndo: []\n"));
        assertThrows(IllegalArgumentException.class,()->validateStep("Then the workflow should complete", "unrequested"));
    }
    @Test void assertionsDetectWrongOrderOutputErrorAndMissingOrNonArrayProperties() throws Exception {
        var actual=new Observation("COMPLETED",yaml("colors: [red]\n"),NullNode.instance,List.of("setRed","setGreen"));
        assertTrue(assertion(new Step("And setGreen should run after setRed",null),actual));
        assertFalse(assertion(new Step("And setRed should run after setGreen",null),actual));
        assertFalse(assertion(new Step("Then the workflow should complete with output:","colors: [blue]"),actual));
        assertFalse(assertion(new Step("Then the workflow should fault with error:","status: 400"),actual));
        assertFalse(assertion(new Step("And the workflow output should have properties 'missing'",null),actual));
        assertFalse(assertion(new Step("And the workflow output should have a 'missing' property containing 0 items",null),actual));
        assertFalse(assertion(new Step("And the workflow output should have a 'colors' property with value:","[blue]"),actual));
        assertTrue(assertion(new Step("And the workflow output should have a 'colors' property containing 1 items",null),actual));
        var fault=new Observation("FAULTED",NullNode.instance,yaml("status: 400\ninstance: /do/0/raiseError\n"),List.of("raiseError"));
        assertTrue(assertion(new Step("Then the workflow should fault with error:","status: 400\ninstance: /do/0/raiseError\n"),fault));
        assertFalse(assertion(new Step("Then the workflow should fault with error:","status: 401"),fault));
        assertFalse(assertion(new Step("Then the workflow should fault with error:","instance: do/0/raiseError"),fault));
    }
}
