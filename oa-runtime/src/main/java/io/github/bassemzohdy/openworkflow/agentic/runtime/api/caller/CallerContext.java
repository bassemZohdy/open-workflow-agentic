package io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller;

import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import java.util.Set;

/**
 * Who is calling: the tenant, the subject and the scopes the caller holds. Every registry read and write is scoped by
 * the tenant, every start records the subject as owner, and the operations API checks the scopes through the policy
 * enforcement point (WP-105d).
 *
 * <p>This is a seam for WP-102 (authentication and delegation). Until then it is filled only by
 * {@link CallerContextResolver} from headers, and only when the development flag is on. Scopes are never taken from a
 * request that is not trusted.
 *
 * @param tenant  the tenant of the caller
 * @param subject the user the caller acts for
 * @param scopes  the scopes the caller holds; empty when none were given
 */
public record CallerContext(String tenant, Subject subject, Set<String> scopes) {

    public CallerContext {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    /** A caller with no scopes. */
    public CallerContext(String tenant, Subject subject) {
        this(tenant, subject, Set.of());
    }
}
