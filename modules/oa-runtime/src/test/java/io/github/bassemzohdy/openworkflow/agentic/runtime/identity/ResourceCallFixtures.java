package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallBinding;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallScope;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceCallBindings;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Test doubles of the durable resource-call binding store (GPT's producer supplies the real one). */
final class ResourceCallFixtures {

    private ResourceCallFixtures() {
    }

    static final String TOPIC_REQUEST = "{\"topic\":\"x\"}";

    /** Enforces the port's conflict rules in memory. */
    static final class InMemory implements ResourceCallBindings {
        private final Map<String, ResourceCallBinding> byToken = new ConcurrentHashMap<>();
        private final Map<String, String> contracts = new ConcurrentHashMap<>();

        @Override
        public synchronized ResourceCallBinding bind(ResourceCallBinding binding) {
            String known = contracts.putIfAbsent(binding.scope().key(), binding.scope().fingerprint());
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
        public Optional<ResourceCallBinding> find(String issuer, String tokenId) {
            return Optional.ofNullable(byToken.get("rb1-" + CanonicalEncoder.sha256Hex(
                    new CanonicalEncoder("oa.resource.credential-binding.v1").text(issuer).text(tokenId).toBytes())));
        }
    }

    /** A store that cannot answer. */
    static final class Down implements ResourceCallBindings {
        @Override
        public ResourceCallBinding bind(ResourceCallBinding binding) {
            throw new IllegalStateException("store down");
        }

        @Override
        public Optional<ResourceCallBinding> find(String issuer, String tokenId) {
            throw new IllegalStateException("store down");
        }
    }

    static ResourceCallScope scope(String tenant, Subject owner, String audience, String operation, String request) {
        return new ResourceCallScope(tenant, UlidGenerator.shared().next(), owner, "app", "engine-1", "ns",
                "research-and-approve", "1", "do/0/research", 1, "root", audience, operation, "a".repeat(64),
                ResourceCallScope.requestFingerprint(request));
    }

    static ResourceCallBinding binding(VerifiedToken token, io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid grant,
                                       ResourceCallScope scope) {
        return new ResourceCallBinding(token.issuer(), token.tokenId(), grant, scope, token.issuedAt(),
                token.expiresAt());
    }
}
