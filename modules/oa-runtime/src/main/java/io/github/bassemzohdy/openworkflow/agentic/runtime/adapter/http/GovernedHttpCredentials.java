package io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.http;

import io.github.bassemzohdy.openworkflow.agentic.contracts.port.EffectTarget.Reply;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.GovernedAsyncDispatch;
import java.util.Objects;
import java.util.Set;

/** Explicit composition: exchanges and uses one token inside the live instance's identity scope. Not a CDI producer. */
public final class GovernedHttpCredentials implements PinnedHttpEffectTarget.CredentialProvider {
    private final GovernedAsyncDispatch dispatch;
    private final String engineInstanceId;
    private final Set<String> scopes;

    public GovernedHttpCredentials(GovernedAsyncDispatch dispatch,String engineInstanceId,Set<String> scopes) {
        this.dispatch=Objects.requireNonNull(dispatch);this.engineInstanceId=Objects.requireNonNull(engineInstanceId);
        if(engineInstanceId.isBlank())throw new IllegalArgumentException("engine instance required");
        this.scopes=Set.copyOf(scopes);
        if(this.scopes.isEmpty())throw new IllegalArgumentException("scopes required");
    }

    @Override public Reply withToken(String audience,PinnedHttpEffectTarget.TokenAction action) throws Exception {
        Objects.requireNonNull(action);
        try {
            return dispatch.run(engineInstanceId,audience,scopes,token->{
                try {return action.run(token);}
                catch(Exception failure){throw new WorkFailure(failure);}
            });
        } catch(WorkFailure wrapped) {
            if(wrapped.failure instanceof InterruptedException)Thread.currentThread().interrupt();
            throw wrapped.failure;
        }
    }

    private static final class WorkFailure extends RuntimeException {
        final Exception failure;
        WorkFailure(Exception failure){super(null,null,false,false);this.failure=failure;}
    }
}
