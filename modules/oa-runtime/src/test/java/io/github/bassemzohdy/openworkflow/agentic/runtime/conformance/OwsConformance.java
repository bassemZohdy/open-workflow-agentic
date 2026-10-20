package io.github.bassemzohdy.openworkflow.agentic.runtime.conformance;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.networknt.schema.*;
import io.serverlessworkflow.api.*;
import io.serverlessworkflow.api.types.Workflow;
import io.serverlessworkflow.impl.WorkflowApplication;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Assessment harness, not a product authoring API or a conformance certificate. Never executes external tasks. */
final class OwsConformance {
    static final ObjectMapper JSON=new ObjectMapper();
    static final ObjectMapper YAML=WorkflowFormat.YAML.mapper().copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    static final Set<String> PURE=Set.of("dataflow","branch","iteration","parallel","pause","catch-error","ctk-for","export-context");
    static final String SCHEMA_SHA="704ef5e91c5d823167dd8751794edb1dd1a6f9a3bdf9bfd389bf9c6b23ae3816";
    final Path repository;
    final JsonNode manifest;
    final Schema schema;
    OwsConformance(Path repository) throws Exception {
        this.repository=repository.toAbsolutePath().normalize();manifest=JSON.readTree(resource("cases.json"));
        String pinned=resource("schema/workflow-1.0.3.yaml");
        if(!SCHEMA_SHA.equals(sha(pinned)))throw new IllegalStateException("pinned normative schema changed");
        schema=SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,b->b.schemaRegistryConfig(
                SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())).getSchema(JSON.writeValueAsString(YAML.readTree(pinned)));
        var properties=new Properties();
        try(var in=WorkflowReader.class.getResourceAsStream("/META-INF/maven/io.serverlessworkflow/serverlessworkflow-api/pom.properties")) {
            if(in==null)throw new IllegalStateException("actual SDK version missing");properties.load(in);
        }
        if(!manifest.path("sdkVersion").asText().equals(properties.getProperty("version")))throw new IllegalStateException("SDK version changed; reassess profile");
    }
    static String resource(String name) throws IOException {
        try(var in=OwsConformance.class.getResourceAsStream("/conformance/"+name)) {
            if(in==null)throw new IOException("missing conformance resource: "+name);
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    static String sha(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.replace("\r\n","\n").getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException unavailable){throw new IllegalStateException(unavailable);}
    }
    ObjectNode assess() throws Exception {
        var report=JSON.createObjectNode();report.put("profile","OWS-1.0.3-assessment");report.put("sdkVersion",manifest.path("sdkVersion").asText());
        report.put("specCommit",manifest.path("specCommit").asText());report.put("schemaSha256",SCHEMA_SHA);
        report.put("scope","schema/model roundtrip; selected pure executions only; not full semantic conformance or product import/export");
        report.put("fullSemanticConformance","NOT_ESTABLISHED");
        var cases=report.putArray("cases");
        for(var entry:manifest.path("cases"))cases.add(assess(entry));
        return report;
    }
    private ObjectNode assess(JsonNode entry) throws Exception {
        String id=entry.path("id").asText(),source;
        if(entry.has("repositoryPath")) {
            Path path=repository.resolve(entry.path("repositoryPath").asText()).normalize();
            if(!path.startsWith(repository) || Files.size(path)>4_194_304)throw new IOException("source outside bounded repository scope");
            source=Files.readString(path);
        }else source=resource(entry.path("source").asText());
        var data=YAML.readTree(source);var result=JSON.createObjectNode();result.put("id",id);result.set("features",entry.path("features"));
        result.put("sourceSha256",sha(source));result.put("declaredDsl",data.path("document").path("dsl").asText());
        result.put("expectedSchema",entry.path("schemaExpected").asText());result.set("sourceSchema",validate(data));
        if(entry.has("upstream"))result.set("upstream",entry.get("upstream"));
        if(entry.has("expectationSource"))result.set("expectationSource",entry.get("expectationSource"));
        Workflow standard=null,strict=null;
        try{standard=WorkflowReader.readWorkflowFromString(source,WorkflowFormat.YAML);result.set("defaultImport",status("ACCEPTED"));}
        catch(Exception rejected){result.set("defaultImport",failure(rejected));}
        try{strict=WorkflowReader.validation().read(source,WorkflowFormat.YAML);result.set("validatedImport",status("ACCEPTED"));}
        catch(Exception rejected){result.set("validatedImport",failure(rejected));}
        var inventory=result.putArray("definitionDependencies");inventoryWorkflow(data,inventory);
        if(standard==null && strict==null){result.put("exports","UNAVAILABLE");result.put("execution","NOT_MEASURED");return result;}
        boolean useDefault=standard!=null;result.put("exportModelFrom",useDefault?"DEFAULT_READER":"VALIDATED_READER");
        var executions=result.putObject("execution");
        if(entry.has("execution")) {
            if(!PURE.contains(id))throw new IllegalStateException("external execution not allowlisted");
            executions.set("source",execute(source,WorkflowFormat.YAML,entry.path("execution")));
        }else executions.put("status","NOT_MEASURED");
        var exports=result.putObject("exports");
        for(var format:List.of(WorkflowFormat.JSON,WorkflowFormat.YAML)) {
            var out=exports.putObject(format.name());
            try {
                // Independent fresh source model for EACH direction: serializer getter side effects cannot mask loss.
                var model=useDefault?WorkflowReader.readWorkflowFromString(source,WorkflowFormat.YAML):WorkflowReader.validation().read(source,WorkflowFormat.YAML);
                String exported=WorkflowWriter.workflowAsString(model,format);
                out.put("lexicallyIdenticalToSource",source.replace("\r\n","\n").equals(exported.replace("\r\n","\n")));
                JsonNode tree=format.mapper().readTree(exported);out.set("schema",validate(tree));
                var changes=out.putArray("structuralChanges");diff(data,tree,"",changes);
                try{WorkflowReader.validation().read(exported,format);out.set("validatedReimport",status("ACCEPTED"));}
                catch(Exception rejected){out.set("validatedReimport",failure(rejected));}
                var reopened=WorkflowReader.readWorkflowFromString(exported,format);
                var second=format.mapper().readTree(WorkflowWriter.workflowAsString(reopened,format));
                out.put("stableAfterFirstRoundtrip",tree.equals(second));
                Path directory=Path.of("target","conformance","exports");Files.createDirectories(directory);
                Files.writeString(directory.resolve(id+"."+format.name().toLowerCase(Locale.ROOT)),exported);
                if(entry.has("execution"))executions.set(format.name(),execute(exported,format,entry.path("execution")));
            }catch(Exception failed){out.set("failure",failure(failed));}
        }
        return result;
    }
    ObjectNode validate(JsonNode data) {
        var errors=schema.validate(data);var stage=status(errors.isEmpty()?"VALID":"INVALID");var issues=stage.putArray("issues");
        errors.stream().map(e->e.getInstanceLocation()+":"+e.getKeyword()).distinct().sorted().forEach(issues::add);return stage;
    }
    private static ObjectNode execute(String text,WorkflowFormat format,JsonNode fixture) {
        try {
            requirePure(format.mapper().readTree(text));
            try(var app=WorkflowApplication.builder().disableLifeCycleCEPublishing().disableStatusChangePublishing().build()) {
            var model=WorkflowReader.readWorkflowFromString(text,format);
            var instance=app.workflowDefinition(model).instance(JSON.convertValue(fixture.path("input"),Object.class));
            var output=instance.start().get(3,TimeUnit.SECONDS).asJavaObject();
            JsonNode actual=JSON.valueToTree(output);boolean matches=canonical(actual).equals(canonical(fixture.path("expected")));
            Path observed=Path.of("target","conformance","execution");Files.createDirectories(observed);
            Files.writeString(observed.resolve(sha(text)+".json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(canonical(actual)));
            var result=status(matches?"MATCH":"DIVERGED");result.put("actualFingerprint",sha(canonical(actual).toString()));
            result.put("expectedFingerprint",sha(canonical(fixture.path("expected")).toString()));return result;
            }
        }catch(Exception rejected){return failure(rejected);}
    }
    static void requirePure(JsonNode workflow) {
        if(workflow.has("schedule") || (workflow.has("use") && !workflow.path("use").isEmpty()))throw new IllegalArgumentException("scheduled/reusable resources are not in the pure execution subset");
        if(workflow.path("input").has("schema") || workflow.path("output").has("schema"))throw new IllegalArgumentException("external schema resolution is not in the pure execution subset");
        pureTasks(workflow.path("do"));
    }
    static void pureTasks(JsonNode list) {
        for(var named:list)named.fields().forEachRemaining(entry->{
            var task=entry.getValue();
            for(String key:List.of("call","run","emit","listen"))if(task.has(key))throw new IllegalArgumentException("external task is not in the pure execution subset");
            for(String key:List.of("input","output","export"))if(task.path(key).has("schema"))throw new IllegalArgumentException("schema resolution is not in the pure execution subset");
            pureTasks(task.path("do"));pureTasks(task.path("try"));pureTasks(task.path("catch").path("do"));pureTasks(task.path("fork").path("branches"));
        });
    }
    static JsonNode canonical(JsonNode data) {
        if(data.isNumber())return DecimalNode.valueOf(data.decimalValue().stripTrailingZeros());
        if(data.isArray()){var array=JSON.createArrayNode();data.forEach(v->array.add(canonical(v)));return array;}
        if(data.isObject()){var object=JSON.createObjectNode();var names=new TreeSet<String>();data.fieldNames().forEachRemaining(names::add);for(String n:names)object.set(n,canonical(data.get(n)));return object;}
        return data;
    }
    /** Report paths and change kinds, never arbitrary workflow values or exception messages. */
    static void diff(JsonNode before,JsonNode after,String path,ArrayNode changes) {
        if(Objects.equals(before,after))return;
        if(before==null || after==null){change(changes,path,before==null?"ADDED":"REMOVED");return;}
        if(before.isObject() && after.isObject()) {
            var names=new TreeSet<String>();before.fieldNames().forEachRemaining(names::add);after.fieldNames().forEachRemaining(names::add);
            for(String name:names)diff(before.get(name),after.get(name),path+"/"+escape(name),changes);
        }else if(before.isArray() && after.isArray()) {
            for(int i=0;i<Math.max(before.size(),after.size());i++)diff(before.get(i),after.get(i),path+"/"+i,changes);
        }else if(before.isNumber() && after.isNumber() && before.decimalValue().compareTo(after.decimalValue())==0)change(changes,path,"NUMBER_REPRESENTATION");
        else change(changes,path,before.getNodeType()==after.getNodeType()?"VALUE_CHANGED":"TYPE_CHANGED");
    }
    static void change(ArrayNode changes,String path,String kind){changes.addObject().put("path",path).put("kind",kind);}
    static String escape(String value){return value.replace("~","~0").replace("/","~1");}
    static void inventoryWorkflow(JsonNode workflow,ArrayNode found) {
        metadata(workflow.path("document"),"/document",found);
        var use=workflow.path("use");
        if(use.has("secrets"))change(found,"/use/secrets","SECRET_PROVISIONING");
        use.path("functions").fields().forEachRemaining(e->taskInventory(e.getValue(),"/use/functions/"+escape(e.getKey()),found));
        if(use.has("extensions")) {
            change(found,"/use/extensions","STANDARD_EXTENSION_HOOK");
            for(int i=0;i<use.path("extensions").size();i++) {
                final int index=i;use.path("extensions").get(i).fields().forEachRemaining(e->{
                    String p="/use/extensions/"+index+"/"+escape(e.getKey());
                    taskList(e.getValue().path("before"),p+"/before",found);taskList(e.getValue().path("after"),p+"/after",found);
                });
            }
        }
        taskList(workflow.path("do"),"/do",found);
    }
    static void metadata(JsonNode node,String path,ArrayNode found) {
        node.path("metadata").fieldNames().forEachRemaining(k->change(found,path+"/metadata/"+escape(k),"APPLICATION_METADATA"));
    }
    static void taskList(JsonNode list,String path,ArrayNode found) {
        for(int i=0;i<list.size();i++){final int index=i;list.get(i).fields().forEachRemaining(e->taskInventory(e.getValue(),path+"/"+index+"/"+escape(e.getKey()),found));}
    }
    static void taskInventory(JsonNode task,String path,ArrayNode found) {
        metadata(task,path,found);
        if(task.has("call"))change(found,path+"/call","CALL_PROVIDER_OR_SERVICE");
        if(task.has("run"))change(found,path+"/run","EXECUTION_PROVIDER");
        taskList(task.path("do"),path+"/do",found);taskList(task.path("try"),path+"/try",found);
        taskList(task.path("fork").path("branches"),path+"/fork/branches",found);
        taskList(task.path("catch").path("do"),path+"/catch/do",found);
        taskList(task.path("foreach").path("do"),path+"/foreach/do",found);
        eventStrategy(task.path("listen").path("to"),path+"/listen/to",found);
    }
    static void eventStrategy(JsonNode strategy,String path,ArrayNode found) {
        if(strategy.has("correlate"))change(found,path+"/correlate","EVENT_CORRELATION");
        if(strategy.has("one"))eventStrategy(strategy.path("one"),path+"/one",found);
        for(String kind:List.of("any","all"))for(int i=0;i<strategy.path(kind).size();i++)eventStrategy(strategy.path(kind).get(i),path+"/"+kind+"/"+i,found);
        if(strategy.path("until").isObject())eventStrategy(strategy.path("until"),path+"/until",found);
    }
    static ObjectNode status(String value){return JSON.createObjectNode().put("status",value);}
    static ObjectNode failure(Exception failed) {
        var result=status("REJECTED");result.put("exception",failed.getClass().getSimpleName());
        Throwable cause=failed;for(int i=0;i<8 && cause.getCause()!=null;i++)cause=cause.getCause();
        result.put("cause",cause.getClass().getSimpleName());return result;
    }
    static Path repository() {
        for(Path path=Path.of("").toAbsolutePath();path!=null;path=path.getParent())if(Files.isDirectory(path.resolve("modules/oa-runtime")) && Files.isRegularFile(path.resolve("pom.xml")))return path;
        throw new IllegalStateException("repository root missing");
    }
}
