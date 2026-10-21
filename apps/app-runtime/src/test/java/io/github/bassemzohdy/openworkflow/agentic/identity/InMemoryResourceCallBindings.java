package io.github.bassemzohdy.openworkflow.agentic.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallBinding;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceCallBindings;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double of the durable resource-call binding store that GPT's producer will supply (contracts port
 * {@link ResourceCallBindings}). It enforces the port's conflict rules (one immutable contract per invocation key, one
 * immutable binding per issuer and token id) so that admission is proved against the real semantics. Production has no
 * such bean, and admission then refuses every delegated token.
 */
@ApplicationScoped
public class InMemoryResourceCallBindings implements ResourceCallBindings {

    private final Map<String, ResourceCallBinding> byToken = new ConcurrentHashMap<>();
    private final Map<String, String> contractByInvocation = new ConcurrentHashMap<>();

    @Override
    public synchronized ResourceCallBinding bind(ResourceCallBinding binding) {
        String known = contractByInvocation.putIfAbsent(binding.scope().key(), binding.scope().fingerprint());
        if (known != null && !known.equals(binding.scope().fingerprint())) {
            throw new IllegalStateException("invocation contract conflict");
        }
        ResourceCallBinding existing = byToken.putIfAbsent(binding.key(), binding);
        if (existing != null && !existing.equals(binding)) {
            throw new IllegalStateException("credential binding conflict");
        }
        return existing != null ? existing : binding;
    }

    @Override
    public Optional<ResourceCallBinding> find(String verifiedIssuer, String verifiedTokenId) {
        return Optional.ofNullable(byToken.get(keyOf(verifiedIssuer, verifiedTokenId)));
    }

    /** The same exact issuer + token id identity as {@link ResourceCallBinding#key()}. */
    private static String keyOf(String issuer, String tokenId) {
        return "rb1-" + CanonicalEncoder.sha256Hex(
                new CanonicalEncoder("oa.resource.credential-binding.v1").text(issuer).text(tokenId).toBytes());
    }
}
