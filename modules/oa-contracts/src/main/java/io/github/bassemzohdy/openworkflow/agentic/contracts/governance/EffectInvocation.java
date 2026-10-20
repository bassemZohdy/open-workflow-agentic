package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import java.util.Objects;

/** Full-scope invocation DATA from the trusted producer; construction supplies no caller, lease or target authority. */
public record EffectInvocation(EffectScope scope, EffectCommand command, EffectKey compensationFor) {
    public EffectInvocation {
        Objects.requireNonNull(scope); Objects.requireNonNull(command); scope.requireKey(command.key());
        if (compensationFor != null) {
            scope.requireKey(compensationFor);
            if (compensationFor.equals(command.key())) throw new IllegalArgumentException("compensation needs another key");
        }
    }
}
