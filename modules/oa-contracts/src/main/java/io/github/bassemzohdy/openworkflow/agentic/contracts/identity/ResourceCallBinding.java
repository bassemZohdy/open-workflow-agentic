package io.github.bassemzohdy.openworkflow.agentic.contracts.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.time.*;
import java.util.Objects;

/**
 * Immutable verified issuer/jti-to-invocation metadata. Contains no bearer or refresh token. The issuingGrantId must
 * equal the separate V65 issuing binding, and signed issue/expiry must equal the admitted token. This is not a verified
 * token, replay allowance, current authority, graph result, effect outcome or permission to run.
 */
public record ResourceCallBinding(String issuer,String tokenId,Ulid issuingGrantId,ResourceCallScope scope,
                                  Instant issuedAt,Instant expiresAt) {
    public ResourceCallBinding {
        issuer=Checks.requireText("verified issuer",issuer,300);CanonicalEncoder.strictUtf8(issuer);
        tokenId=Checks.requireText("verified token id",tokenId,300);CanonicalEncoder.strictUtf8(tokenId);
        Objects.requireNonNull(issuingGrantId);Objects.requireNonNull(scope);Objects.requireNonNull(issuedAt);Objects.requireNonNull(expiresAt);
        Duration lifetime=Duration.between(issuedAt,expiresAt);
        if(lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(Duration.ofSeconds(300))>0)
            throw new IllegalArgumentException("resource credential lifetime must be positive and at most 300 seconds");
    }
    /** Exact issuer/jti identity, domain separated from the invocation key. Never use jti alone. */
    public String key() {
        return "rb1-"+CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.resource.credential-binding.v1").text(issuer).text(tokenId).toBytes());
    }
}
