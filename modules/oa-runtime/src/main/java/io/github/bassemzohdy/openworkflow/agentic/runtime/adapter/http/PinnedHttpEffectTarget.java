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
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.AuthorityLostException;
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
 * replay or real provider lookup contract is claimed.
 * Credential acquisition occupies a shared slot and must independently bound detached provider work. An unresolved
 * POST or an incomplete lookup permanently reserves its slot: timeout/cancellation does not prove physical work stopped.
 * A completed bounded lookup response releases the slot even if its business status is unknown. The service must
 * guarantee lookup is read-only. Restarting the process or using another classloader is outside this bound.
 */
public final class PinnedHttpEffectTarget implements EffectTarget {
    static final int CAPACITY = 16;
    private static final Semaphore SLOTS = new Semaphore(CAPACITY);
    private static final int MAX_LOOKUP_BODY = 65_536;
    private static final ScheduledThreadPoolExecutor LOOKUP_TIMER = lookupTimer();
    private static final Set<Integer> TERMINAL_STATUSES = Set.of(200, 201, 204);
    private static final Set<Integer> REJECTION_STATUSES = Set.of(400, 404, 409, 422);
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

    private static ScheduledThreadPoolExecutor lookupTimer() {
        var timer = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task,"effect-lookup-deadline"); thread.setDaemon(true); return thread;
        });
        timer.setRemoveOnCancelPolicy(true); return timer;
    }

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
    /** The service must guarantee read-only lookup and that selected responses prove completion for the supplied key. */
    public record Lookup(URI endpoint, Set<Integer> confirmed) {
        public Lookup {
            Objects.requireNonNull(endpoint); confirmed=Set.copyOf(confirmed);
            if(confirmed.isEmpty() || !Set.of(200,204).containsAll(confirmed))
                throw new IllegalArgumentException("lookup confirmation statuses must be explicitly pinned");
            if(endpoint.getHost()==null || endpoint.getUserInfo()!=null || endpoint.getRawQuery()!=null
                    || endpoint.getRawFragment()!=null || !endpoint.normalize().equals(endpoint)
                    || endpoint.getRawPath()==null || endpoint.getRawPath().length()>2048
                    || !endpoint.getRawPath().matches("/(?:[A-Za-z0-9_~-]+(?:\\.[A-Za-z0-9_~-]+)*/?)+"))
                throw new IllegalArgumentException("lookup endpoint must be fixed without query, userinfo or fragment");
        }
    }
    private final String target, audience, correlation, body;
    private final URI endpoint;
    private final Duration timeout;
    private final Set<Integer> confirmed, rejected;
    private final CredentialProvider credentials;
    private final CredentialExpectation expectation;
    private final PreSendFence fence;
    private final Lookup lookup;

    public PinnedHttpEffectTarget(String target, URI endpoint, String audience, String correlation, String body,
                                  Duration timeout, Set<Integer> confirmed, CredentialExpectation expectation,
                                  CredentialProvider credentials, PreSendFence fence) {
        this(target,endpoint,audience,correlation,body,timeout,confirmed,expectation,credentials,fence,false);
    }

    /** Rejection codes must be proven by the service contract to mean that no effect was applied. */
    public PinnedHttpEffectTarget(String target, URI endpoint, String audience, String correlation, String body,
                                  Duration timeout, Set<Integer> confirmed, Set<Integer> rejected, CredentialExpectation expectation,
                                  CredentialProvider credentials, PreSendFence fence) {
        this(target,endpoint,audience,correlation,body,timeout,confirmed,rejected,expectation,credentials,fence,false,false);
    }

    // Test-only transport to an explicit IPv4 loopback literal; public composition always requires HTTPS.
    PinnedHttpEffectTarget(String target, URI endpoint, String audience, String correlation, String body,
                           Duration timeout, Set<Integer> confirmed, CredentialExpectation expectation,
                           CredentialProvider credentials, PreSendFence fence, boolean loopbackTest) {
        this(target,endpoint,audience,correlation,body,timeout,confirmed,expectation,credentials,fence,loopbackTest,false);
    }

    // Only the pinned operation binder may supply a constrained, canonical query string.
    PinnedHttpEffectTarget(String target, URI endpoint, String audience, String correlation, String body,
                           Duration timeout, Set<Integer> confirmed, CredentialExpectation expectation,
                           CredentialProvider credentials, PreSendFence fence, boolean loopbackTest, boolean safeQuery) {
        this(target,endpoint,audience,correlation,body,timeout,confirmed,Set.of(),expectation,credentials,fence,loopbackTest,safeQuery);
    }

    PinnedHttpEffectTarget(String target, URI endpoint, String audience, String correlation, String body,
                           Duration timeout, Set<Integer> confirmed, Set<Integer> rejected, CredentialExpectation expectation,
                           CredentialProvider credentials, PreSendFence fence, boolean loopbackTest, boolean safeQuery) {
        this(target,endpoint,audience,correlation,body,timeout,confirmed,rejected,expectation,credentials,fence,loopbackTest,safeQuery,null);
    }

    PinnedHttpEffectTarget(String target, URI endpoint, String audience, String correlation, String body,
                           Duration timeout, Set<Integer> confirmed, Set<Integer> rejected, CredentialExpectation expectation,
                           CredentialProvider credentials, PreSendFence fence, boolean loopbackTest, boolean safeQuery, Lookup lookup) {
        this.target=Checks.requireMatching("target",target,AgentTaskContract.TOKEN);
        this.endpoint=Objects.requireNonNull(endpoint);
        boolean permittedScheme="https".equals(endpoint.getScheme()) || (loopbackTest && "http".equals(endpoint.getScheme())
                && "127.0.0.1".equals(endpoint.getHost()));
        String query = endpoint.getRawQuery();
        boolean permittedQuery = query == null || (safeQuery && query.length() <= 4096 && query.matches(
                "[A-Za-z][A-Za-z0-9_-]{0,63}=[A-Za-z0-9_-]{1,128}(?:&[A-Za-z][A-Za-z0-9_-]{0,63}=[A-Za-z0-9_-]{1,128})*"));
        if (!permittedScheme || endpoint.getHost()==null || endpoint.getUserInfo()!=null || !permittedQuery
                || endpoint.getRawFragment()!=null || !endpoint.normalize().equals(endpoint))
            throw new IllegalArgumentException("a pinned HTTPS endpoint with a permitted query and without userinfo, fragment or dot segments is required");
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
        this.rejected=Set.copyOf(rejected);
        if(!REJECTION_STATUSES.containsAll(this.rejected))
            throw new IllegalArgumentException("unsupported terminal rejection status");
        this.expectation=Objects.requireNonNull(expectation);this.credentials=Objects.requireNonNull(credentials);
        this.fence=Objects.requireNonNull(fence);
        if(lookup!=null && (!endpoint.getScheme().equals(lookup.endpoint().getScheme())
                || !endpoint.getHost().equalsIgnoreCase(lookup.endpoint().getHost())
                || endpoint.getPort()!=lookup.endpoint().getPort()))
            throw new IllegalArgumentException("lookup must use the effect's exact origin");
        this.lookup=lookup;
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
        return perform(idempotencyKey,false);
    }

    @Override public Reply lookup(String idempotencyKey) throws Exception {
        if(lookup==null)return new Reply(Outcome.UNKNOWN,null);
        return perform(idempotencyKey,true);
    }

    private Reply perform(String idempotencyKey, boolean lookupCall) throws Exception {
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
                    state.observed=send(Objects.requireNonNull(issued,"issued token"),idempotencyKey,started,state,lookupCall);
                    return state.observed;
                } finally {state.phase.set(2);}
            });
            if(state.phase.get()!=2 || result==null || result!=state.observed)
                throw new IllegalStateException("credential provider did not return the scoped operation result");
            return result;
        } catch(AuthorityLostException lost) {
            if(state.observed!=null && state.observed.outcome()!=Outcome.UNKNOWN)
                throw new EffectTarget.ObservedOutcomeException(state.observed,lost);
            throw lost;
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw interrupted;
        } catch(IOException failure) {
            return new Reply(Outcome.UNKNOWN,"target outcome unresolved");
        } finally {
            state.phase.set(3);
            if(!state.dispatched || state.finished)SLOTS.release();
        }
    }

    private Reply send(IssuedToken issued,String idempotencyKey,long started,CallState state,boolean lookupCall) throws Exception {
        requireToken(issued);
        fence.requireCurrent(issued); // May block; expiry and the operation deadline are checked again afterward.
        requireToken(issued);
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("transport interrupted");
        long elapsed=System.nanoTime()-started;
        if(elapsed<0 || elapsed>=timeout.toNanos())return new Reply(Outcome.UNKNOWN,"transport deadline elapsed");
        String token=issued.accessToken().reveal();
        if(token.length()>8192 || !token.matches("[A-Za-z0-9._~+/-]+=*"))
            throw new IllegalStateException("target-scoped credential unavailable");
        var builder=HttpRequest.newBuilder(lookupCall ? lookup.endpoint() : endpoint)
                .header("Authorization","Bearer "+token).header("Idempotency-Key",idempotencyKey)
                .header("X-Correlation-ID",correlation);
        if(lookupCall)builder.GET();
        else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8));
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
        HttpResponse<Void> response;
        if(lookupCall) {
            var drain=new BoundedLookupBody();
            var deadline=LOOKUP_TIMER.schedule(drain::expire,Math.max(1,timeout.toNanos()-beforeSend),TimeUnit.NANOSECONDS);
            try {response=CLIENT.send(request,ignored->drain);}
            finally {deadline.cancel(false);}
            state.finished=true; // The bounded lookup response was completely consumed, even if its status is unknown.
        } else response=CLIENT.send(request,headersOnly());
        if((lookupCall ? lookup.confirmed() : confirmed).contains(response.statusCode())) {
            state.finished=true;
            return new Reply(Outcome.CONFIRMED,lookupCall ? "lookup confirmed" : "target confirmed");
        }
        if(!lookupCall && rejected.contains(response.statusCode())) {
            state.finished=true;
            return new Reply(Outcome.FAILED,"target rejected");
        }
        return new Reply(Outcome.UNKNOWN,"target outcome unresolved");
    }

    private void requireToken(IssuedToken issued) {
        if(!audience.equals(issued.audience()) || !expectation.issuer().equals(issued.issuer())
                || !expectation.grantId().equals(issued.grantId()) || !expectation.scopes().equals(issued.scopes())
                || issued.tokenId().isBlank() || !issued.usableAt(Instant.now()))
            throw new IllegalStateException("target-scoped credential unavailable");
    }

    /** Drains a bounded read-only lookup response; timeout, oversize and read errors remain physically uncertain. */
    private static final class BoundedLookupBody implements HttpResponse.BodySubscriber<Void> {
        private final CompletableFuture<Void> body=new CompletableFuture<>();
        private final java.util.concurrent.atomic.AtomicReference<Flow.Subscription> subscription=new java.util.concurrent.atomic.AtomicReference<>();
        private long size;
        public CompletionStage<Void> getBody(){return body;}
        public void onSubscribe(Flow.Subscription next) {
            if(!subscription.compareAndSet(null,next)){next.cancel();return;}
            if(body.isDone())next.cancel();else next.request(1);
        }
        public void onNext(List<ByteBuffer> buffers) {
            if(body.isDone())return;
            for(ByteBuffer buffer:buffers) {
                size+=buffer.remaining();
                if(size>MAX_LOOKUP_BODY){fail(new IOException("lookup body exceeds limit"));return;}
            }
            Flow.Subscription active=subscription.get();if(active!=null && !body.isDone())active.request(1);
        }
        public void onError(Throwable failure){body.completeExceptionally(failure);}
        public void onComplete(){body.complete(null);}
        void expire(){fail(new java.net.http.HttpTimeoutException("lookup body deadline elapsed"));}
        private void fail(Throwable error){
            if(body.completeExceptionally(error)){Flow.Subscription active=subscription.get();if(active!=null)active.cancel();}
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
