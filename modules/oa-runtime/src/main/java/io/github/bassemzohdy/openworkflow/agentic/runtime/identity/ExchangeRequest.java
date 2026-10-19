package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What the runtime asks the authorization server for (RFC 8693): a new token for one {@code audience} with some
 * {@code scopes}, for the subject of {@code subjectToken}, acting as the holder of {@code actorToken}, under one
 * delegation grant. Both tokens are {@link Credential}s, so printing or serializing a request never shows them.
 *
 * @param subjectToken the token of the user, or of the parent agent for agent-to-agent delegation
 * @param actorToken   the workload token of the agent that asks (its own service identity, never the user's)
 * @param audience     the one resource the new token is for
 * @param scopes       the scopes wanted: non-empty, each a scope token, all held by the grant
 * @param grant        the delegation grant that authorizes the exchange
 */
public record ExchangeRequest(Credential subjectToken, Credential actorToken, String audience, Set<String> scopes,
                              DelegationGrant grant) {

    static final Pattern AUDIENCE = Pattern.compile("[A-Za-z0-9._:/-]{1,256}");

    public ExchangeRequest {
        Objects.requireNonNull(subjectToken, "subjectToken");
        Objects.requireNonNull(actorToken, "actorToken");
        Objects.requireNonNull(grant, "grant");
        if (audience == null || !AUDIENCE.matcher(audience).matches()) {
            throw new ExchangeException(ExchangeException.Code.INVALID_REQUEST, "the audience is missing or malformed");
        }
        if (scopes == null || scopes.isEmpty() || scopes.size() > AgentTaskContract.MAX_ALLOW_LIST_SIZE) {
            throw new ExchangeException(ExchangeException.Code.INVALID_REQUEST,
                    "scopes are required: a token is never issued for \"whatever the grant allows\"");
        }
        for (String scope : scopes) {
            if (scope == null || !AgentTaskContract.TOKEN.matcher(scope).matches()) {
                throw new ExchangeException(ExchangeException.Code.INVALID_REQUEST, "a scope is not a valid scope token");
            }
        }
        scopes = Set.copyOf(scopes);
    }
}
