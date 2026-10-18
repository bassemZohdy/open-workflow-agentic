package io.github.bassemzohdy.openworkflow.agentic.contracts.port;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
 * </ul>
 *
 * <p>A timed-out delegate is not stopped (Java cannot stop it safely). Its result is discarded when it arrives. A
 * delegate that never returns therefore leaks one virtual thread; implementations should bound their own time.
 */
public final class FailClosedPolicyDecisionPoint implements PolicyDecisionPoint {

    /** Name of the component in decisions that this decorator produces. */
    public static final String DECIDED_BY = "fail-closed";
    /** Longest deadline accepted, so the nanosecond conversion cannot overflow. */
    public static final Duration MAX_TIMEOUT = Duration.ofMinutes(5);

    private final PolicyDecisionPoint delegate;
    private final Duration timeout;
    private final Clock clock;

    public FailClosedPolicyDecisionPoint(PolicyDecisionPoint delegate, Duration timeout, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("timeout must be positive and at most " + MAX_TIMEOUT);
        }
        this.timeout = timeout;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public FailClosedPolicyDecisionPoint(PolicyDecisionPoint delegate, Duration timeout) {
        this(delegate, timeout, Clock.systemUTC());
    }

    @Override
    public Decision decide(AuthorizationRequest request) {
        Objects.requireNonNull(request, "request");
        CompletableFuture<Decision> result = new CompletableFuture<>();
        Thread.ofVirtual().name("pdp-decision").start(() -> {
            try {
                result.complete(delegate.decide(request));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
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

    private Decision error(String reason) {
        return new Decision(DECIDED_BY, DecisionOutcome.ERROR, reason, PolicyRef.FAIL_CLOSED, clock.instant());
    }
}
