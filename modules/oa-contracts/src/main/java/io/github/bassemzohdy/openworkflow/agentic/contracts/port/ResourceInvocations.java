package io.github.bassemzohdy.openworkflow.agentic.contracts.port;

import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallScope;
import java.util.Optional;

/**
 * Trusted producer lookup of historical canonical attribution. Existing issuer/jti consumers keep ResourceCallBindings.
 * This port does not derive the complete business occurrence, create an execution claim or authorize a caller.
 */
public interface ResourceInvocations extends ResourceCallBindings {
    /**
     * Finds the FIRST retained scope for candidate.occurrenceKey, ignoring only the new observed SDK entry counter.
     * All other fields, including approved target/audience/operation/request, must match exactly; differences conflict.
     * The producer uses the returned original scope before binding a refreshed credential, not a fresh rc1 derived
     * from the changing counter. Missing is not permission; outage/conflict refuses release. Historical reads after
     * expiry/completion remain data. Registry/actual SDK/target/current authority/audit/execution gates still apply.
     */
    Optional<ResourceCallScope> findInvocation(ResourceCallScope candidate);
}
