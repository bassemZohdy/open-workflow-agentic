package io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.GovernedToolGateway;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/** Explicit, trusted composition of a digest-pinned OpenAPI 3.1 JSON POST with the governed effect gateway.
 * This narrow subset is not a general OpenAPI validator, approval source, credential verifier or CDI wiring.
 * The trusted base URI overrides document servers; parameters, remote references and path templates are unsupported.
 */
public final class PinnedOpenApiEffect {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64)
                    .maxStringLength(262_144).maxNumberLength(128).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
    private final String documentDigest, operationId, schema;
    private final URI endpoint;
    private final Set<Integer> documentedResponses;
    private final boolean loopbackTest;

    public PinnedOpenApiEffect(String document, String expectedSha256, String operationId, URI trustedOrigin) {
        this(document, expectedSha256, operationId, trustedOrigin, false);
    }

    // Package-local seam for the IPv4 loopback integration fixture; public composition requires HTTPS.
    PinnedOpenApiEffect(String document, String expectedSha256, String operationId, URI trustedOrigin, boolean loopbackTest) {
        this.loopbackTest = loopbackTest;
        byte[] bytes = CanonicalEncoder.strictUtf8(Objects.requireNonNull(document));
        if (bytes.length > 262_144) throw new IllegalArgumentException("OpenAPI document exceeds 256 KiB");
        this.documentDigest = CanonicalEncoder.sha256Hex(bytes);
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}") || !documentDigest.equals(expectedSha256))
            throw new IllegalArgumentException("OpenAPI digest differs from the approved document");
        if (operationId == null || !operationId.matches("[A-Za-z0-9._-]{1,128}"))
            throw new IllegalArgumentException("invalid operation ID");
        this.operationId = operationId;
        Objects.requireNonNull(trustedOrigin);
        boolean permittedScheme = "https".equals(trustedOrigin.getScheme()) || (loopbackTest
                && "http".equals(trustedOrigin.getScheme()) && "127.0.0.1".equals(trustedOrigin.getHost()));
        if (!permittedScheme || trustedOrigin.getHost() == null
                || trustedOrigin.getRawUserInfo() != null || trustedOrigin.getRawQuery() != null
                || trustedOrigin.getRawFragment() != null || !(trustedOrigin.getRawPath().isEmpty() || "/".equals(trustedOrigin.getRawPath())))
            throw new IllegalArgumentException("trusted origin must be an HTTPS origin without a path");
        final JsonNode root;
        try { root = JSON.readTree(document); }
        catch (Exception invalid) { throw new IllegalArgumentException("invalid OpenAPI JSON", invalid); }
        if (root == null || !root.isObject() || root.has("jsonSchemaDialect") || !root.path("openapi").asText().matches("3\\.1\\.[0-9]+")
                || !root.path("paths").isObject()) throw new IllegalArgumentException("OpenAPI 3.1 paths required");
        JsonNode selected = null, selectedPath = null; String path = null; int matches = 0;
        var paths = root.path("paths").fields();
        while (paths.hasNext()) {
            var item = paths.next();
            if (!item.getValue().isObject()) throw new IllegalArgumentException("invalid path item");
            for (String method : METHODS) {
                JsonNode operation = item.getValue().get(method);
                if (operation != null && operation.isObject() && operationId.equals(operation.path("operationId").asText())) {
                    matches++; selected = operation; selectedPath = item.getValue(); path = item.getKey();
                    if (!method.equals("post")) throw new IllegalArgumentException("only POST operations are supported");
                }
            }
        }
        if (matches != 1 || path == null || !path.matches("/(?:[A-Za-z0-9_~-]+(?:\\.[A-Za-z0-9_~-]+)*/?)+")
                || selectedPath.has("$ref") || selectedPath.has("parameters") || selected.has("parameters")
                || selectedPath.has("servers") || selected.has("servers"))
            throw new IllegalArgumentException("operation must be unique, fixed-path and parameter-free");
        JsonNode request = resolveObject(selected.path("requestBody"), root.path("components").path("requestBodies"), "requestBodies");
        JsonNode input = request.path("content").path("application/json").path("schema");
        if (!request.path("required").isBoolean() || !request.path("required").asBoolean() || !input.isObject())
            throw new IllegalArgumentException("required JSON object schema is required");
        JsonNode resolved = resolveSchema(input, root.path("components").path("schemas"), new java.util.HashSet<>(), new int[]{0}, 0);
        if (!"object".equals(resolved.path("type").asText())) throw new IllegalArgumentException("JSON object schema required");
        this.schema = resolved.toString();
        if (CanonicalEncoder.strictUtf8(schema).length > 65_536) throw new IllegalArgumentException("resolved schema exceeds 64 KiB");
        JsonNode responses = selected.path("responses");
        if (!responses.isObject()) throw new IllegalArgumentException("documented responses required");
        var allowed = new java.util.HashSet<Integer>();
        for (int code : new int[]{200, 201, 204}) {
            JsonNode response = responses.get(Integer.toString(code));
            if (response != null) {
                JsonNode resolvedResponse = resolveObject(response, root.path("components").path("responses"), "responses");
                if (!resolvedResponse.path("description").isTextual())
                    throw new IllegalArgumentException("documented response description required");
                allowed.add(code);
            }
        }
        this.documentedResponses = Set.copyOf(allowed);
        this.endpoint = URI.create(trustedOrigin.toString().replaceAll("/$", "") + path);
    }

    /** Trusted composition only. Gateway validation occurs before target binding and no network I/O occurs here. */
    public GovernedToolGateway.Registration registration(String target, String audience, String correlation,
            Duration timeout, Set<Integer> confirmed, PinnedHttpEffectTarget.CredentialExpectation expectation,
            PinnedHttpEffectTarget.CredentialProvider credentials, PinnedHttpEffectTarget.PreSendFence fence) {
        Objects.requireNonNull(expectation); Set<Integer> statuses = Set.copyOf(confirmed);
        if (!documentedResponses.containsAll(statuses)) throw new IllegalArgumentException("confirmation response is not documented");
        var encoder = new CanonicalEncoder("oa.openapi.effect.v1").text(documentDigest).text(operationId)
                .text(endpoint.toASCIIString()).text(target).text(audience).text(correlation)
                .number(timeout.getSeconds()).number(timeout.getNano()).text(expectation.issuer())
                .text(expectation.grantId().toString()).number(expectation.scopes().size());
        expectation.scopes().stream().sorted().forEach(encoder::text);
        encoder.number(statuses.size()); statuses.stream().sorted().forEach(s -> encoder.number(s));
        String fingerprint = CanonicalEncoder.sha256Hex(encoder.toBytes());
        // Construct once at registration to reject malformed pinned configuration before any tool call.
        new PinnedHttpEffectTarget(target, endpoint, audience, correlation, "{}", timeout, statuses, expectation, credentials, fence, loopbackTest);
        return GovernedToolGateway.Registration.effectful(schema, "{}", timeout, fingerprint, target,
                body -> new PinnedHttpEffectTarget(target, endpoint, audience, correlation, body, timeout,
                        statuses, expectation, credentials, fence, loopbackTest));
    }

    private static JsonNode resolveObject(JsonNode node, JsonNode components, String category) {
        var visited = new java.util.HashSet<String>();
        String prefix = "#/components/" + category + "/";
        while (node.isObject() && node.has("$ref")) {
            JsonNode ref = node.get("$ref");
            if (node.size() != 1 || !ref.isTextual() || !ref.textValue().startsWith(prefix))
                throw new IllegalArgumentException("only pure local component references are supported");
            String name = ref.textValue().substring(prefix.length());
            if (!name.matches("[A-Za-z0-9._-]{1,128}") || !visited.add(name) || visited.size() > 32)
                throw new IllegalArgumentException("invalid or cyclic component reference");
            node = components.path(name);
        }
        if (!node.isObject()) throw new IllegalArgumentException("missing or invalid component object");
        return node;
    }

    private static void requireSelfContained(JsonNode node) {
        if (node.isObject()) for (String key : new String[]{"$ref", "$dynamicRef", "$recursiveRef", "$id", "$schema"})
            if (node.has(key)) throw new IllegalArgumentException("schema references are unsupported");
        node.elements().forEachRemaining(PinnedOpenApiEffect::requireSelfContained);
    }

    /** Resolve only pure, acyclic references to named schemas in this exact pinned document. */
    private static JsonNode resolveSchema(JsonNode node, JsonNode components, Set<String> active, int[] count, int depth) {
        if (++count[0] > 10_000 || active.size() > 32 || depth > 128) throw new IllegalArgumentException("schema expansion limit exceeded");
        if (node.isObject()) {
            if (node.has("$ref")) {
                JsonNode ref = node.get("$ref");
                if (node.size() != 1 || !ref.isTextual() || !ref.textValue().matches("#/components/schemas/[A-Za-z0-9._-]{1,128}"))
                    throw new IllegalArgumentException("only pure local component schema references are supported");
                String name = ref.textValue().substring("#/components/schemas/".length());
                JsonNode target = components.get(name);
                if (target == null || !target.isObject() || !active.add(name))
                    throw new IllegalArgumentException("missing or cyclic component schema");
                try { return resolveSchema(target, components, active, count, depth + 1); }
                finally { active.remove(name); }
            }
            for (String key : new String[]{"$dynamicRef", "$recursiveRef", "$id", "$schema"})
                if (node.has(key)) throw new IllegalArgumentException("schema references are unsupported");
            var out = JSON.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                // Instance-valued annotations and assertions must never be interpreted as schema references.
                if (Set.of("const", "default", "enum", "examples", "example").contains(entry.getKey())) {
                    requireSelfContained(entry.getValue()); out.set(entry.getKey(), entry.getValue().deepCopy());
                } else out.set(entry.getKey(), resolveSchema(entry.getValue(), components, active, count, depth + 1));
            });
            return out;
        }
        if (node.isArray()) {
            var out = JSON.createArrayNode();
            node.forEach(item -> out.add(resolveSchema(item, components, active, count, depth + 1)));
            return out;
        }
        return node.deepCopy();
    }
}
