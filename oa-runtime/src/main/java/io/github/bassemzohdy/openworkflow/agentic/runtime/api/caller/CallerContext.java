package io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller;

import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;

/**
 * Who is calling: the tenant and the subject. Every registry read and write is scoped by the tenant, and every start
 * records the subject as owner.
 *
 * <p>This is a seam for WP-102 (authentication and delegation). Until then it is filled only by
 * {@link CallerContextResolver} from headers, and only when the development flag is on.
 *
 * @param tenant  the tenant of the caller
 * @param subject the user the caller acts for
 */
public record CallerContext(String tenant, Subject subject) {
}
