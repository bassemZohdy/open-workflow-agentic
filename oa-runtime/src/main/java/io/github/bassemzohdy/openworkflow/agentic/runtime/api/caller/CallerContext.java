package io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller;

import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import java.util.Objects;
import java.util.Set;

/**
 * Who is calling: the tenant, the subject and the scopes the caller holds. Every registry read and write is scoped by
 * the tenant, every start records the subject as owner, and the operations API checks the scopes through the policy
 * enforcement point (WP-105d).
 *
 * <p>It is filled only by {@link CallerContextResolver}: from a verified bearer token (tenant, subject and scopes are
 * its claims), or, in a development or test profile with {@code agentic.dev.trust-caller-headers=true}, from request
 * headers. {@link #verified()} says which. Scopes are never taken from a request that is not trusted.
 *
 * @param tenant     the tenant of the caller
 * @param subject    the user the caller acts for
 * @param scopes     the scopes the caller holds; empty when none were given
 * @param provenance how the identity was established
 */
public record CallerContext(String tenant, Subject subject, Set<String> scopes, Provenance provenance) {

    /** How a caller's identity was established. */
    public enum Provenance {
        /** The claims of a bearer token whose signature, issuer, audience and expiry were verified. */
        VERIFIED_TOKEN,
        /** Development headers, accepted only in a development or test profile. Not an authentication. */
        DEV_HEADERS,
        /** Built by hand, for example in a unit test. Treated as not verified. */
        UNVERIFIED
    }

    public CallerContext {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        Objects.requireNonNull(provenance, "provenance");
    }

    /** A caller built by hand (not verified). */
    public CallerContext(String tenant, Subject subject, Set<String> scopes) {
        this(tenant, subject, scopes, Provenance.UNVERIFIED);
    }

    /** A caller with no scopes (not verified). */
    public CallerContext(String tenant, Subject subject) {
        this(tenant, subject, Set.of(), Provenance.UNVERIFIED);
    }

    /** True only for an identity taken from a verified token. */
    public boolean verified() {
        return provenance == Provenance.VERIFIED_TOKEN;
    }
}
