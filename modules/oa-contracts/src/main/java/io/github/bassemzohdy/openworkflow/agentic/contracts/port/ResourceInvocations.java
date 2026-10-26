package io.github.bassemzohdy.openworkflow.agentic.contracts.port;

import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallScope;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallBinding;
import java.util.Optional;

/**
 * Trusted producer lookup of historical canonical attribution. Existing issuer/jti consumers keep ResourceCallBindings.
 * This port does not derive the complete business occurrence, create an execution claim or authorize a caller.
 */
public interface ResourceInvocations extends ResourceCallBindings {
    /**
     * Historical DATA lookup by the exact rb1 credential fingerprint obtained from opaque verified admission, not
     * request/header metadata. A fingerprint, returned binding or missing row is never current authority or dispatch
     * permission. The private admitted credential must separately match EVERY returned binding field through the
     * pairing-aware live check, then the composite identity/PDP/registry/task/target/lease/audit fence must run.
     * Missing returns empty; malformed keys, corrupt canonical attribution or an unavailable store refuse. No issuer,
     * jti-only, latest-credential or alternate-key fallback. Lookup remains historical after expiry/completion.
     */
    Optional<ResourceCallBinding> findBinding(String credentialBindingKey);

    /**
     * Finds the FIRST retained scope for candidate.occurrenceKey, ignoring only the new observed SDK entry counter.
     * All other fields, including approved target/audience/operation/request, must match exactly; differences conflict.
     * The producer uses the returned original scope before binding a refreshed credential, not a fresh rc1 derived
     * from the changing counter. Missing is not permission; outage/conflict refuses release. Historical reads after
     * expiry/completion remain data. Registry/actual SDK/target/current authority/audit/execution gates still apply.
     */
    Optional<ResourceCallScope> findInvocation(ResourceCallScope candidate);
}
