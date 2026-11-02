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
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IssuedToken;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
 * Credential acquisition occupies a shared slot and must independently bound any detached provider work. Once HTTP
 * dispatch begins, a nonconfirmed call permanently reserves its slot: timeout/cancellation does not prove that remote
 * work stopped. Restarting the process or using another classloader is outside this bound.
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

    /** Executes once, synchronously, inside the verified exchange scope; provider must bound its own physical work. */
    @FunctionalInterface public interface CredentialProvider {
        Reply withToken(String audience, TokenAction action) throws Exception;
    }
    @FunctionalInterface public interface TokenAction { Reply run(IssuedToken token) throws Exception; }
    /** Trusted composition rechecks this exact token, current identity, task, policy and owner after acquisition. */
    @FunctionalInterface public interface PreSendFence { void requireCurrent(IssuedToken token) throws Exception; }
    /** Server-pinned expectations, never taken from the tool request. */
    public record CredentialExpectation(String issuer, Ulid grantId, Set<String> scopes) {
        public CredentialExpectation {
            issuer=Checks.requireText("issuer",issuer,300);CanonicalEncoder.strictUtf8(issuer);
            Objects.requireNonNull(grantId);scopes=Set.copyOf(scopes);
            if(scopes.isEmpty() || scopes.size()>64)throw new IllegalArgumentException("expected scopes must contain 1..64 entries");
            for(String scope:scopes){Checks.requireText("scope",scope,256);CanonicalEncoder.strictUtf8(scope);}
        }
    }
    private final String target, audience, correlation, body;
    private final URI endpoint;
    private final Duration timeout;
    private final Set<Integer> confirmed;
    private final CredentialProvider credentials;
    private final CredentialExpectation expectation;
    private final PreSendFence fence;

    public PinnedHttpEffectTarget(String target, URI endpoint, String audience, String correlation, String body,
                                  Duration timeout, Set<Integer> confirmed, CredentialExpectation expectation,
                                  CredentialProvider credentials, PreSendFence fence) {
        this(target,endpoint,audience,correlation,body,timeout,confirmed,expectation,credentials,fence,false);
    }

    // Test-only transport to an explicit IPv4 loopback literal; public composition always requires HTTPS.
    PinnedHttpEffectTarget(String target, URI endpoint, String audience, String correlation, String body,
                           Duration timeout, Set<Integer> confirmed, CredentialExpectation expectation,
                           CredentialProvider credentials, PreSendFence fence, boolean loopbackTest) {
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
        this.expectation=Objects.requireNonNull(expectation);this.credentials=Objects.requireNonNull(credentials);
        this.fence=Objects.requireNonNull(fence);
    }

    @Override public String target() { return target; }
    /** Unknown target deduplication means no automatic replay is permitted. */
    @Override public Duration replayWindow() { return Duration.ZERO; }

    private static final class CallState {
        final Thread owner=Thread.currentThread();
        final java.util.concurrent.atomic.AtomicInteger phase=new java.util.concurrent.atomic.AtomicInteger();
        boolean dispatched,finished;
        Reply observed;
    }

    @Override public Reply execute(String idempotencyKey) throws Exception {
        if(idempotencyKey==null || !idempotencyKey.matches("[!-~]{1,200}"))
            throw new IllegalArgumentException("idempotency key must be bounded visible ASCII");
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("transport interrupted");
        if(!SLOTS.tryAcquire())return new Reply(Outcome.UNKNOWN,"transport capacity unavailable");
        long started=System.nanoTime();var state=new CallState();
        try {
            Reply result=credentials.withToken(audience,issued->{
                if(Thread.currentThread()!=state.owner || !state.phase.compareAndSet(0,1))
                    throw new IllegalStateException("credential callback must run once on its owning thread");
                try {
                    state.observed=send(Objects.requireNonNull(issued,"issued token"),idempotencyKey,started,state);
                    return state.observed;
                } finally {state.phase.set(2);}
            });
            if(state.phase.get()!=2 || result==null || result!=state.observed)
                throw new IllegalStateException("credential provider did not return the scoped operation result");
            return result;
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw interrupted;
        } catch(IOException failure) {
            return new Reply(Outcome.UNKNOWN,"target outcome unresolved");
        } finally {
            state.phase.set(3);
            if(!state.dispatched || state.finished)SLOTS.release();
        }
    }

    private Reply send(IssuedToken issued,String idempotencyKey,long started,CallState state) throws Exception {
        requireToken(issued);
        fence.requireCurrent(issued); // May block; expiry and the operation deadline are checked again afterward.
        requireToken(issued);
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("transport interrupted");
        long elapsed=System.nanoTime()-started;
        if(elapsed<0 || elapsed>=timeout.toNanos())return new Reply(Outcome.UNKNOWN,"transport deadline elapsed");
        String token=issued.accessToken().reveal();
        if(token.length()>8192 || !token.matches("[A-Za-z0-9._~+/-]+=*"))
            throw new IllegalStateException("target-scoped credential unavailable");
        var builder=HttpRequest.newBuilder(endpoint)
                .header("Authorization","Bearer "+token).header("Idempotency-Key",idempotencyKey)
                .header("X-Correlation-ID",correlation).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8));
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("transport interrupted");
        elapsed=System.nanoTime()-started;
        if(elapsed<0 || elapsed>=timeout.toNanos() || !issued.usableAt(Instant.now()))
            return new Reply(Outcome.UNKNOWN,"transport deadline elapsed");
        HttpRequest request=builder.timeout(Duration.ofNanos(timeout.toNanos()-elapsed)).build();
        requireToken(issued);
        long beforeSend=System.nanoTime()-started;
        if(beforeSend<0 || beforeSend>=timeout.toNanos())return new Reply(Outcome.UNKNOWN,"transport deadline elapsed");
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("transport interrupted");
        state.dispatched=true;
        var response=CLIENT.send(request,headersOnly());
        if(confirmed.contains(response.statusCode())) {
            state.finished=true;
            return new Reply(Outcome.CONFIRMED,"target confirmed");
        }
        return new Reply(Outcome.UNKNOWN,"target outcome unresolved");
    }

    private void requireToken(IssuedToken issued) {
        if(!audience.equals(issued.audience()) || !expectation.issuer().equals(issued.issuer())
                || !expectation.grantId().equals(issued.grantId()) || !expectation.scopes().equals(issued.scopes())
                || issued.tokenId().isBlank() || !issued.usableAt(Instant.now()))
            throw new IllegalStateException("target-scoped credential unavailable");
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
