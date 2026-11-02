package io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.EffectTarget;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/**
 * Explicit candidate for one trusted, pinned JSON operation. Not a CDI bean, OpenAPI parser, approval source,
 * credential verifier, gateway, or production registration. The caller must prove the endpoint's chosen terminal
 * statuses really establish completed application, bind the exact request to its ledger occurrence, provide fresh
 * target-scoped authority, and enforce policy/audit and network egress. The target must atomically deduplicate any
 * physical retries by the HTTP implementation; this candidate has not proven that transport behavior. No ledger-level
 * replay or authoritative target lookup is claimed.
 * A nonconfirmed call permanently reserves one shared classloader slot: timeout/cancellation does not prove that the
 * remote work stopped. Restarting the process or using another classloader is outside this bound.
 */
public final class PinnedHttpEffectTarget implements EffectTarget {
    static final int CAPACITY = 16;
    private static final Semaphore SLOTS = new Semaphore(CAPACITY);
    private static final Set<Integer> TERMINAL_STATUSES = Set.of(200, 201, 204);
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64)
                    .maxStringLength(65_536).maxNumberLength(128).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final ProxySelector DIRECT = new ProxySelector() {
        @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
        @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
    };
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).proxy(DIRECT).build();

    /** Must obtain fresh scoped credentials within its own deadline; never returns a preformatted header. */
    @FunctionalInterface public interface CredentialProvider { String tokenFor(String audience) throws Exception; }
    private final String target, audience, correlation, body;
    private final URI endpoint;
    private final Duration timeout;
    private final Set<Integer> confirmed;
    private final CredentialProvider credentials;

    public PinnedHttpEffectTarget(String target, URI endpoint, String audience, String correlation, String body,
                                  Duration timeout, Set<Integer> confirmed, CredentialProvider credentials) {
        this(target,endpoint,audience,correlation,body,timeout,confirmed,credentials,false);
    }

    // Test-only transport to an explicit IPv4 loopback literal; public composition always requires HTTPS.
    PinnedHttpEffectTarget(String target, URI endpoint, String audience, String correlation, String body,
                           Duration timeout, Set<Integer> confirmed, CredentialProvider credentials, boolean loopbackTest) {
        this.target=Checks.requireMatching("target",target,AgentTaskContract.TOKEN);
        this.endpoint=Objects.requireNonNull(endpoint);
        boolean permittedScheme="https".equals(endpoint.getScheme()) || (loopbackTest && "http".equals(endpoint.getScheme())
                && "127.0.0.1".equals(endpoint.getHost()));
        if (!permittedScheme || endpoint.getHost()==null || endpoint.getUserInfo()!=null || endpoint.getRawQuery()!=null
                || endpoint.getRawFragment()!=null || !endpoint.normalize().equals(endpoint))
            throw new IllegalArgumentException("a pinned HTTPS endpoint without userinfo, query, fragment or dot segments is required");
        this.audience=Checks.requireText("audience",audience,256);
        this.correlation=Checks.requireMatching("correlation",correlation,java.util.regex.Pattern.compile("[A-Za-z0-9._:-]{1,128}"));
        this.body=Objects.requireNonNull(body);
        if(CanonicalEncoder.strictUtf8(body).length>65_536)throw new IllegalArgumentException("request body exceeds 64 KiB");
        try { if(JSON.readTree(body)==null)throw new IllegalArgumentException("JSON request body required"); }
        catch(IOException invalid){throw new IllegalArgumentException("invalid JSON request body");}
        this.timeout=Objects.requireNonNull(timeout);
        if(timeout.compareTo(Duration.ofMillis(100))<0 || timeout.compareTo(Duration.ofSeconds(30))>0)
            throw new IllegalArgumentException("timeout must be between 100 ms and 30 s");
        this.confirmed=Set.copyOf(confirmed);
        if(this.confirmed.isEmpty() || !TERMINAL_STATUSES.containsAll(this.confirmed))
            throw new IllegalArgumentException("terminal confirmation statuses must be explicitly pinned");
        this.credentials=Objects.requireNonNull(credentials);
    }

    @Override public String target() { return target; }
    /** Unknown target deduplication means no automatic replay is permitted. */
    @Override public Duration replayWindow() { return Duration.ZERO; }

    @Override public Reply execute(String idempotencyKey) throws Exception {
        if(idempotencyKey==null || !idempotencyKey.matches("[!-~]{1,200}"))
            throw new IllegalArgumentException("idempotency key must be bounded visible ASCII");
        String token=credentials.tokenFor(audience);
        if(token==null || token.length()>8192 || !token.matches("[A-Za-z0-9._~+/-]+=*"))
            throw new IllegalStateException("target-scoped credential unavailable");
        HttpRequest request=HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Authorization","Bearer "+token).header("Idempotency-Key",idempotencyKey)
                .header("X-Correlation-ID",correlation).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build();
        if(!SLOTS.tryAcquire())return new Reply(Outcome.UNKNOWN,"transport capacity unavailable");
        boolean finished=false;
        try {
            var response=CLIENT.send(request,headersOnly());
            if(confirmed.contains(response.statusCode())) {
                finished=true;
                return new Reply(Outcome.CONFIRMED,"target confirmed");
            }
            return new Reply(Outcome.UNKNOWN,"target outcome unresolved");
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw interrupted;
        } catch(IOException failure) {
            return new Reply(Outcome.UNKNOWN,"target outcome unresolved");
        } finally {
            if(finished)SLOTS.release();
        }
    }

    /** Discard/cancel the body immediately: no unbounded response body is retained or returned. */
    private static HttpResponse.BodyHandler<Void> headersOnly() {
        return ignored -> new HttpResponse.BodySubscriber<>() {
            private final CompletableFuture<Void> body=new CompletableFuture<>();
            public CompletionStage<Void> getBody(){return body;}
            public void onSubscribe(Flow.Subscription subscription){subscription.cancel();body.complete(null);}
            public void onNext(List<ByteBuffer> ignoredBytes){ }
            public void onError(Throwable failure){body.completeExceptionally(failure);}
            public void onComplete(){body.complete(null);}
        };
    }
}
