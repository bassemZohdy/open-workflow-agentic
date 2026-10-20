package io.github.bassemzohdy.openworkflow.agentic.runtime.conformance;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import io.cloudevents.CloudEvent;
import io.serverlessworkflow.impl.*;
import io.serverlessworkflow.impl.events.*;
import io.serverlessworkflow.impl.executors.http.HttpClientResolver;
import io.serverlessworkflow.impl.executors.openapi.jackson.JacksonUnifiedOpenAPIReader;
import io.serverlessworkflow.impl.resources.*;
import jakarta.ws.rs.client.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Controlled provider substitution for the exact pinned CTK, not production target permission or public TLS proof.
 * Actual SDK HTTP executors/auth/output and OpenAPI parsing run; a per-client filter confines transport to loopback.
 * Workflow text/input/expectations are unchanged. No global mapper, DNS, proxy or trust configuration is changed. */
final class CtkProviders implements AutoCloseable {
    static final String DOCUMENT="https://petstore.swagger.io/v2/swagger.json";
    static final String EVENT_TYPE="com.fake-source.user.greeted.v1";
    static final String EVENT_SOURCE="https://fake-source.com";
    static final String DOCUMENT_SHA="bb116730d391e330119c691f9b124d07b9afdea61b5a252345b577e9a4c2f98b";
    private static final String AUTH="Basic "+Base64.getEncoder().encodeToString("serverless-workflow:conformance-test".getBytes(StandardCharsets.UTF_8));
    final HttpServer server;
    final Client client;
    final InMemoryEvents broker=new InMemoryEvents();
    final List<ObjectNode> requests=new CopyOnWriteArrayList<>(),deliveries=new CopyOnWriteArrayList<>();
    final List<String> resources=new CopyOnWriteArrayList<>();
    final List<String> refusals=new CopyOnWriteArrayList<>();
    final List<ObjectNode> refusedRequests=new CopyOnWriteArrayList<>();
    final AtomicInteger responses=new AtomicInteger();
    final AtomicInteger documentOpens=new AtomicInteger();
    final byte[] document;
    CtkProviders() throws Exception {
        document=resource("providers/petstore-swagger.json");
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(document));
        if(!hash.equals(DOCUMENT_SHA))throw new IllegalStateException("Pinned OpenAPI document changed");
        server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath(),query=exchange.getRequestURI().getRawQuery();
            int status=200;String body;
            if(!exchange.getRequestMethod().equals("GET")){status=405;body="{}";}
            else if(path.equals("/v2/pet/findByStatus")&&"status=available".equals(query))body="["+pet(1)+"]";
            else if(path.equals("/v2/pet/1"))body=pet(1);
            else if(path.equals("/v2/pet/2"))body=pet(2);
            else if(path.equals("/v2/pet/getPetByName/Milou")){status=404;body="{\"code\":1,\"type\":\"error\",\"message\":\"Pet not found\"}";}
            else if(path.equals("/basic-auth/serverless-workflow/conformance-test")) {
                if(!AUTH.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    status=401;exchange.getResponseHeaders().set("WWW-Authenticate","Basic realm=\"ctk-fixture\"");body="{}";
                }else body="{\"authenticated\":true,\"user\":\"serverless-workflow\"}";
            }else {status=404;body="{}";}
            responses.incrementAndGet();exchange.getResponseHeaders().set("Content-Type","application/json");
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}finally{exchange.close();}
        });
        server.start();
        try {
            client=ClientBuilder.newBuilder().connectTimeout(2,TimeUnit.SECONDS).readTimeout(2,TimeUnit.SECONDS)
                    .register((ClientRequestFilter)this::route)
                    .register(new org.jboss.resteasy.reactive.server.jackson.JacksonBasicMessageBodyReader(OwsConformance.JSON)).build();
        }catch(RuntimeException|Error failed){server.stop(0);broker.close();throw failed;}
        broker.register(EVENT_TYPE,event->{
            var delivered=OwsConformance.JSON.createObjectNode().put("id",event.getId()).put("specversion",event.getSpecVersion().toString())
                    .put("source",event.getSource().toString()).put("type",event.getType());
            if(event.getTime()!=null)delivered.put("time",event.getTime().toString());
            try {if(event.getData()!=null)delivered.set("data",OwsConformance.JSON.readTree(event.getData().toBytes()));}
            catch(IOException malformed){throw new UncheckedIOException(malformed);}
            deliveries.add(delivered);
        });
    }
    private static String pet(int id){return "{\"id\":"+id+",\"name\":\"ctk-fixture-pet-"+id+"\",\"photoUrls\":[\"urn:ctk:photo\"],\"status\":\"available\"}";}
    static byte[] resource(String path) throws IOException {
        try(var in=CtkProviders.class.getResourceAsStream("/conformance/"+path)){if(in==null)throw new IOException("Missing provider fixture");return in.readAllBytes();}
    }
    void route(ClientRequestContext request) {
        URI uri=request.getUri();String host=uri.getHost(),path=uri.getPath(),query=uri.getRawQuery();
        boolean pet="petstore.swagger.io".equals(host)&&Set.of("/v2/pet/findByStatus","/v2/pet/1","/v2/pet/2","/v2/pet/getPetByName/Milou").contains(path);
        boolean basic="httpbin.org".equals(host)&&path.equals("/basic-auth/serverless-workflow/conformance-test");
        boolean queryOk=path.equals("/v2/pet/findByStatus")?"status=available".equals(query):query==null;
        if(!Set.of("https","http").contains(uri.getScheme())||uri.getUserInfo()!=null||uri.getPort()!=-1
                ||!request.getMethod().equals("GET")||!(pet||basic)||!queryOk||requests.size()>=4) {
            if(refusedRequests.size()<4)refusedRequests.add(OwsConformance.JSON.createObjectNode().put("originalUri",uri.toString()).put("method",request.getMethod()));
            refusals.add("HTTP_ROUTE_REFUSED");throw new IllegalArgumentException("CTK provider route refused before transport");
        }
        var observed=OwsConformance.JSON.createObjectNode().put("originalUri",uri.toString()).put("method",request.getMethod())
                .put("basicAuthMatched",AUTH.equals(request.getHeaderString("Authorization")));
        URI loopback=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+uri.getRawPath()+(query==null?"":"?"+query));
        observed.put("transportUri",loopback.toString());requests.add(observed);request.setUri(loopback);
    }
    WorkflowApplication.Builder configure(WorkflowApplication.Builder builder) {
        // The SDK HTTP resolver uses the CONTEXTUAL map. Supplier overloads populate a separate global map.
        return builder.withAdditionalObject(HttpClientResolver.HTTP_CLIENT_PROVIDER,(workflow,task)->client)
                .withAdditionalObject("UnifiedOpenAPIReader",(workflow,task)->new JacksonUnifiedOpenAPIReader())
                .withResourceLoaderFactory((app,path)->new ResourceLoader(app) {
                    @Override protected <T> T loadURI(URI uri,Function<ExternalResourceHandler,T> use,Optional<String> auth) {
                        if(!DOCUMENT.equals(uri.toString())||auth.isPresent()) {
                            refusals.add("RESOURCE_ROUTE_REFUSED");throw new IllegalArgumentException("CTK resource route refused");
                        }
                        resources.add(uri.toString());return use.apply(new ExternalResourceHandler() {
                            public String name(){return "petstore-swagger.json";}
                            public InputStream open(){documentOpens.incrementAndGet();return new ByteArrayInputStream(document);}
                        });
                    }
                    @Override public void close(){ }
                }).withEventConsumer(broker).withEventPublisher(new EventPublisher() {
                    public CompletableFuture<Void> publish(CloudEvent event) {return publishGuarded(event);}
                    public void close(){ }
                });
    }
    void verifyBindings(WorkflowApplication application) {
        if(application.additionalObject(HttpClientResolver.HTTP_CLIENT_PROVIDER,null,null).orElse(null)!=client
                ||!(application.additionalObject("UnifiedOpenAPIReader",null,null).orElse(null) instanceof JacksonUnifiedOpenAPIReader))
            throw new IllegalStateException("Contextual CTK provider binding missing; execution refused");
    }
    CompletableFuture<Void> publishGuarded(CloudEvent event) {
        if(!EVENT_TYPE.equals(event.getType())||!URI.create(EVENT_SOURCE).equals(event.getSource())) {
            refusals.add("EVENT_ROUTE_REFUSED");return CompletableFuture.failedFuture(new IllegalArgumentException("CTK event route refused"));
        }
        return broker.publish(event);
    }
    ObjectNode observation() {
        var node=OwsConformance.JSON.createObjectNode().put("scope","Controlled loopback HTTP/pinned resource/in-memory event transport; no public target TLS or production authority proof")
                .put("actualHttpResponses",responses.get()).put("actualResourceOpens",documentOpens.get()).put("openApiDocumentSha256",DOCUMENT_SHA);
        node.set("requests",OwsConformance.JSON.valueToTree(requests));node.set("resources",OwsConformance.JSON.valueToTree(resources));
        node.set("deliveredEvents",OwsConformance.JSON.valueToTree(deliveries));node.set("refusals",OwsConformance.JSON.valueToTree(refusals));
        node.set("refusedRequests",OwsConformance.JSON.valueToTree(refusedRequests));return node;
    }
    ObjectNode summary() {
        var summary=OwsConformance.JSON.createObjectNode().put("httpResponses",responses.get()).put("resourceOpens",documentOpens.get()).put("deliveredEvents",deliveries.size());
        var sent=summary.putArray("requests");for(var request:requests) {var copy=request.deepCopy();copy.remove("transportUri");sent.add(copy);}
        summary.set("resources",OwsConformance.JSON.valueToTree(resources));summary.set("refusals",OwsConformance.JSON.valueToTree(refusals));
        summary.set("refusedRequests",OwsConformance.JSON.valueToTree(refusedRequests));return summary;
    }
    static void requireControlledSource(JsonNode workflow) {
        if(workflow.has("schedule")||(workflow.has("use")&&!workflow.path("use").isEmpty()))throw new IllegalArgumentException("CTK reusable/scheduled execution refused");
        for(String key:List.of("input","output"))if(workflow.path(key).has("schema"))throw new IllegalArgumentException("CTK source schema resolution refused");
        tasks(workflow.path("do"));
    }
    private static void tasks(JsonNode list) {
        for(var named:list)named.fields().forEachRemaining(entry->{
            var task=entry.getValue();
            if(task.has("run")||task.has("listen"))throw new IllegalArgumentException("CTK run/listen refused");
            if(task.has("call")&&(!task.path("call").isTextual()||!Set.of("http","openapi").contains(task.path("call").asText())))
                throw new IllegalArgumentException("CTK unknown provider refused");
            for(String key:List.of("input","output","export"))if(task.path(key).has("schema"))throw new IllegalArgumentException("CTK task schema resolution refused");
            tasks(task.path("do"));tasks(task.path("try"));tasks(task.path("catch").path("do"));tasks(task.path("fork").path("branches"));
        });
    }
    @Override public void close() throws Exception {
        try{client.close();}finally{try{broker.close();}finally{server.stop(0);}}
    }
}
