package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ModelCall;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ModelGateway;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ModelResult;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDenied;
import java.time.Clock;
import java.util.Objects;
import java.util.function.ToLongBiFunction;

/**
 * The {@link ModelGateway} contract over the governed model boundary (WP-201): one path from an agent to a model, with
 * the sealed fail-closed outcomes the contract promises instead of exceptions.
 *
 * <p>It adds no authority and no second policy path. Every call goes through {@link GovernedLlmClient}, so the approved
 * context, the zero-budget refusal, the audited PEP decision and the one-attempt rule are exactly those of that client,
 * and no provider is reached unless they pass. The gateway only translates:
 * <ul>
 *   <li>no approved context, a call for another task or another model than the approved context names, and a policy
 *       DENY or ERROR become {@link ModelResult.Rejected}. A call can never switch the model or borrow another task's
 *       approval, and nothing falls back to a different model.</li>
 *   <li>any other failure of the provider becomes {@link ModelResult.Failed} with a fixed reason; the provider's message
 *       is not copied, as it may carry request text.</li>
 *   <li>success becomes {@link ModelResult.Completed} with the cost the composition root's {@code cost} function states
 *       for this call and output. There is deliberately no default: a provider port that reports no usage must be
 *       priced explicitly, since an implicit zero would understate spend.</li>
 * </ul>
 *
 * <p>The requested output-token ceiling is sent through a provider that declares bounded-call support; an unsupported
 * provider is refused before it is called. Enforcement by the remote provider and durable usage accounting remain
 * external requirements. The prompt is sent as user text with an empty system text, and calls are synchronous.
 */
public final class GovernedModelGateway implements ModelGateway {

    static final String DECIDED_BY = "model-gateway";

    private final GovernedLlmClient governed;
    private final ApprovedModelContext context;
    private final ToLongBiFunction<ModelCall, String> cost;
    private final Clock clock;

    public GovernedModelGateway(GovernedLlmClient governed, ApprovedModelContext context,
                                ToLongBiFunction<ModelCall, String> cost, Clock clock) {
        this.governed = Objects.requireNonNull(governed, "governed");
        this.context = Objects.requireNonNull(context, "context");
        this.cost = Objects.requireNonNull(cost, "cost");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public ModelResult complete(ModelCall call) {
        Objects.requireNonNull(call, "call");
        final ApprovedModelContext.Approved approved;
        try {
            approved = context.current().orElse(null);
        } catch (RuntimeException unavailable) {
            return reject("the approved model context is unavailable");
        }
        if (approved == null) return reject("no approved context for this model call");
        if (!approved.task().id().equals(call.taskId())) return reject("the call is for a task other than the approved one");
        if (!approved.modelId().equals(call.modelId())) return reject("the call names a model other than the approved one");
        String text;
        try {
            text = governed.complete(approved, "", call.prompt(), call.maxOutputTokens());
        } catch (GovernedLlmClient.ApprovalLostAfterCall e) {
            return new ModelResult.Failed("model output withheld after approval changed");
        } catch (ModelCallRefused e) {
            return reject(e.getMessage());
        } catch (PolicyDenied e) {
            return new ModelResult.Rejected(e.decision());
        } catch (RuntimeException e) {
            return new ModelResult.Failed("the model call failed");
        }
        if (text == null) return new ModelResult.Failed("the model call produced no answer");
        long micros;
        try {
            micros = cost.applyAsLong(call, text);
        } catch (RuntimeException e) {
            return new ModelResult.Failed("the cost of the model call could not be determined");
        }
        if (micros < 0) return new ModelResult.Failed("the cost of the model call could not be determined");
        // Cost calculation can block after the governed client's post-provider check. Recheck before output release.
        try {
            if (context.current().filter(approved::equals).isEmpty())
                return new ModelResult.Failed("model output withheld after approval changed");
        } catch (RuntimeException unavailable) {
            return new ModelResult.Failed("model output withheld after approval changed");
        }
        return new ModelResult.Completed(text, micros);
    }

    private ModelResult.Rejected reject(String reason) {
        return new ModelResult.Rejected(new Decision(DECIDED_BY, DecisionOutcome.DENY, reason, PolicyRef.FAIL_CLOSED,
                clock.instant()));
    }
}
