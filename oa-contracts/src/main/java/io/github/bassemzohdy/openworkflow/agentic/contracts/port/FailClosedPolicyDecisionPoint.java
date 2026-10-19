package io.github.bassemzohdy.openworkflow.agentic.contracts.port;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Decorator that enforces the fail-closed rule of {@link PolicyDecisionPoint} around any delegate.
 *
 * <ul>
 *   <li>The delegate runs on a virtual thread and must answer within the timeout.</li>
 *   <li>A delegate that throws, returns null, or times out gives an {@code ERROR} decision attributed to the
 *       built-in {@link PolicyRef#FAIL_CLOSED} rule. The exception message is not copied into the reason, because
 *       messages can carry sensitive input; only the exception class name is kept.</li>
 *   <li>An interrupted wait is restored on the calling thread and gives {@code ERROR}.</li>
 *   <li>Any decision the delegate returns is passed through unchanged, so an {@code ALLOW} from the delegate stays
 *       an {@code ALLOW}.</li>
 *   <li>At most {@code maxInFlight} delegate calls are in flight at once (the bulkhead). When that bound is reached,
 *       a call is refused at once with an {@code ERROR} whose reason names the bulkhead, and the delegate is not
 *       called.</li>
 * </ul>
 *
 * <p>A timed-out delegate is not stopped (Java cannot stop it safely). Its result is discarded when it arrives. Its
 * permit is not released when the caller gives up: it is released only when the delegate itself returns or throws.
 * A delegate that never returns therefore holds one permit for ever, and at most {@code maxInFlight} such virtual
 * threads can accumulate; the next calls are refused until some of them return. Admission never waits: a full
 * bulkhead refuses at once.
 */
public final class FailClosedPolicyDecisionPoint implements PolicyDecisionPoint {

    /** Name of the component in decisions that this decorator produces. */
    public static final String DECIDED_BY = "fail-closed";
    /** Longest deadline accepted, so the nanosecond conversion cannot overflow. */
    public static final Duration MAX_TIMEOUT = Duration.ofMinutes(5);
    /** Largest in-flight bound accepted. Each in-flight call can hold one stranded virtual thread. */
    public static final int MAX_IN_FLIGHT = 1_024;
    /** In-flight bound used when none is given. Normal policy decisions are short, so 64 concurrent ones is ample. */
    public static final int DEFAULT_MAX_IN_FLIGHT = 64;

    private final PolicyDecisionPoint delegate;
    private final Duration timeout;
    private final Clock clock;
    private final Semaphore permits;

    public FailClosedPolicyDecisionPoint(PolicyDecisionPoint delegate, Duration timeout, Clock clock,
            int maxInFlight) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("timeout must be positive and at most " + MAX_TIMEOUT);
        }
        this.timeout = timeout;
        this.clock = Objects.requireNonNull(clock, "clock");
        Checks.requireInRange("maxInFlight", maxInFlight, 1, MAX_IN_FLIGHT);
        this.permits = new Semaphore(maxInFlight);
    }

    public FailClosedPolicyDecisionPoint(PolicyDecisionPoint delegate, Duration timeout, Clock clock) {
        this(delegate, timeout, clock, DEFAULT_MAX_IN_FLIGHT);
    }

    public FailClosedPolicyDecisionPoint(PolicyDecisionPoint delegate, Duration timeout) {
        this(delegate, timeout, Clock.systemUTC(), DEFAULT_MAX_IN_FLIGHT);
    }

    @Override
    public Decision decide(AuthorizationRequest request) {
        Objects.requireNonNull(request, "request");
        if (!permits.tryAcquire()) {
            return error("policy decision bulkhead is full: every permit is held by a delegate call still in flight");
        }
        CompletableFuture<Decision> result = new CompletableFuture<>();
        try {
            Thread.ofVirtual().name("pdp-decision").start(() -> run(request, result));
        } catch (RuntimeException | Error startFailure) {
            permits.release();
            return error("policy decision could not be started: " + startFailure.getClass().getSimpleName());
        }
        try {
            Decision decision = result.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            return decision == null ? error("policy decision point returned no decision") : decision;
        } catch (TimeoutException e) {
            result.cancel(true);
            return error("policy decision did not complete within " + timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.cancel(true);
            return error("interrupted while waiting for a policy decision");
        } catch (ExecutionException e) {
            return error("policy decision failed with " + e.getCause().getClass().getSimpleName());
        }
    }

    /**
     * Runs on the delegate's own thread. The permit is returned when the delegate has returned or thrown, and before
     * the caller can observe the result, so a caller that retries at once sees the capacity it was given back.
     */
    private void run(AuthorizationRequest request, CompletableFuture<Decision> result) {
        Decision decision = null;
        Throwable failure = null;
        try {
            decision = delegate.decide(request);
        } catch (Throwable thrown) {
            failure = thrown;
        }
        permits.release();
        if (failure != null) {
            result.completeExceptionally(failure);
        } else {
            result.complete(decision);
        }
    }

    private Decision error(String reason) {
        return new Decision(DECIDED_BY, DecisionOutcome.ERROR, reason, PolicyRef.FAIL_CLOSED, clock.instant());
    }
}
