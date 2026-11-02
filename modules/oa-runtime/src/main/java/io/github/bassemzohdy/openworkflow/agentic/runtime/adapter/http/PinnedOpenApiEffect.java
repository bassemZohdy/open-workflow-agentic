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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Explicit, trusted composition of a digest-pinned OpenAPI 3.1 JSON POST with the governed effect gateway.
 * This narrow subset is not a general OpenAPI validator, approval source, credential verifier or CDI wiring.
 * The trusted base URI overrides document servers; only bounded string path and query parameters are supported.
 */
public final class PinnedOpenApiEffect {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64)
                    .maxStringLength(262_144).maxNumberLength(128).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                    DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
    private final String documentDigest, operationId, schema;
    private final URI endpoint;
    private final String endpointTemplate;
    private final List<String> pathParameters;
    private final List<String> queryParameters;
    private final Set<String> requiredQuery;
    private final Set<Integer> documentedResponses;
    private final boolean loopbackTest;
    private final Set<String> oauthScopes;

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
        if (matches != 1 || path == null
                || selectedPath.has("$ref") || selectedPath.has("parameters")
                || selectedPath.has("servers") || selected.has("servers"))
            throw new IllegalArgumentException("operation must be unique without path-level overrides");
        this.pathParameters = pathParameters(path);
        this.oauthScopes = securityScopes(root, selected);
        JsonNode request = resolveObject(selected.path("requestBody"), root.path("components").path("requestBodies"), "requestBodies");
        JsonNode input = request.path("content").path("application/json").path("schema");
        if (!request.path("required").isBoolean() || !request.path("required").asBoolean() || !input.isObject())
            throw new IllegalArgumentException("required JSON object schema is required");
        JsonNode resolved = resolveSchema(input, root.path("components").path("schemas"), new java.util.HashSet<>(), new int[]{0}, 0);
        if (!"object".equals(resolved.path("type").asText())) throw new IllegalArgumentException("JSON object schema required");
        ParameterPlan plan = requestSchema(resolved, selected.get("parameters"), root.path("components").path("schemas"), pathParameters);
        this.schema = plan.schema(); this.queryParameters = plan.query(); this.requiredQuery = plan.requiredQuery();
        if (CanonicalEncoder.strictUtf8(schema).length > 65_536) throw new IllegalArgumentException("resolved schema exceeds 64 KiB");
        JsonNode responses = selected.path("responses");
        if (!responses.isObject()) throw new IllegalArgumentException("documented responses required");
        var allowed = new java.util.HashSet<Integer>();
        for (int code : new int[]{200, 201, 204, 400, 404, 409, 422}) {
            JsonNode response = responses.get(Integer.toString(code));
            if (response != null) {
                JsonNode resolvedResponse = resolveObject(response, root.path("components").path("responses"), "responses");
                if (!resolvedResponse.path("description").isTextual())
                    throw new IllegalArgumentException("documented response description required");
                allowed.add(code);
            }
        }
        this.documentedResponses = Set.copyOf(allowed);
        this.endpointTemplate = trustedOrigin.toString().replaceAll("/$", "") + path;
        String example = endpointTemplate;
        for (String parameter : pathParameters) example = example.replace("{" + parameter + "}", "x");
        this.endpoint = URI.create(example);
    }

    /** Trusted composition only. Gateway validation occurs before target binding and no network I/O occurs here. */
    public GovernedToolGateway.Registration registration(String target, String audience, String correlation,
            Duration timeout, Set<Integer> confirmed, PinnedHttpEffectTarget.CredentialExpectation expectation,
            PinnedHttpEffectTarget.CredentialProvider credentials, PinnedHttpEffectTarget.PreSendFence fence) {
        return registration(target, audience, correlation, timeout, confirmed, Set.of(), expectation, credentials, fence);
    }

    /** Selected rejection codes require an approved service guarantee that the effect was not applied. */
    public GovernedToolGateway.Registration registration(String target, String audience, String correlation,
            Duration timeout, Set<Integer> confirmed, Set<Integer> rejected, PinnedHttpEffectTarget.CredentialExpectation expectation,
            PinnedHttpEffectTarget.CredentialProvider credentials, PinnedHttpEffectTarget.PreSendFence fence) {
        return registration(target,audience,correlation,timeout,confirmed,rejected,expectation,credentials,fence,null);
    }

    /** A configured lookup is a separate trusted service contract; it is not inferred from this OpenAPI document. */
    public GovernedToolGateway.Registration registration(String target, String audience, String correlation,
            Duration timeout, Set<Integer> confirmed, Set<Integer> rejected, PinnedHttpEffectTarget.CredentialExpectation expectation,
            PinnedHttpEffectTarget.CredentialProvider credentials, PinnedHttpEffectTarget.PreSendFence fence,
            PinnedHttpEffectTarget.Lookup lookup) {
        Objects.requireNonNull(expectation); Set<Integer> statuses = Set.copyOf(confirmed), rejections = Set.copyOf(rejected);
        if (oauthScopes != null && !oauthScopes.equals(expectation.scopes()))
            throw new IllegalArgumentException("credential scopes differ from the OpenAPI operation");
        if (!documentedResponses.containsAll(statuses) || !documentedResponses.containsAll(rejections))
            throw new IllegalArgumentException("terminal response is not documented");
        var encoder = new CanonicalEncoder(lookup == null ? "oa.openapi.effect.v2" : "oa.openapi.effect.v3").text(documentDigest).text(operationId)
                .text(endpointTemplate).text(target).text(audience).text(correlation)
                .number(timeout.getSeconds()).number(timeout.getNano()).text(expectation.issuer())
                .text(expectation.grantId().toString()).number(expectation.scopes().size());
        expectation.scopes().stream().sorted().forEach(encoder::text);
        encoder.number(statuses.size()); statuses.stream().sorted().forEach(s -> encoder.number(s));
        encoder.number(rejections.size()); rejections.stream().sorted().forEach(s -> encoder.number(s));
        if(lookup != null) {
            encoder.text(lookup.endpoint().toASCIIString()).number(lookup.confirmed().size());
            lookup.confirmed().stream().sorted().forEach(s -> encoder.number(s));
        }
        String fingerprint = CanonicalEncoder.sha256Hex(encoder.toBytes());
        // Construct once at registration to reject malformed pinned configuration before any tool call.
        new PinnedHttpEffectTarget(target, endpoint, audience, correlation, "{}", timeout, statuses, rejections, expectation, credentials, fence, loopbackTest, false, lookup);
        return GovernedToolGateway.Registration.effectful(schema, "{}", timeout, fingerprint, target,
                arguments -> {
                    BoundRequest bound = bind(arguments);
                    return new PinnedHttpEffectTarget(target, bound.endpoint(), audience, correlation, bound.body(), timeout,
                            statuses, rejections, expectation, credentials, fence, loopbackTest, true, lookup);
                });
    }

    private record BoundRequest(URI endpoint, String body) { }

    private BoundRequest bind(String arguments) {
        if (pathParameters.isEmpty() && queryParameters.isEmpty()) return new BoundRequest(endpoint, arguments);
        try {
            JsonNode input = JSON.readTree(arguments);
            int fields = 1 + (pathParameters.isEmpty() ? 0 : 1) + (queryParameters.isEmpty() ? 0 : 1);
            if (input == null || !input.isObject() || input.size() != fields || !input.path("body").isObject()
                    || (!pathParameters.isEmpty() && (!input.path("path").isObject() || input.path("path").size() != pathParameters.size()))
                    || (!queryParameters.isEmpty() && !input.path("query").isObject()))
                throw new IllegalArgumentException("invalid parameter and body arguments");
            String target = endpointTemplate;
            for (String name : pathParameters) {
                JsonNode value = input.path("path").get(name);
                if (value == null || !value.isTextual() || !value.textValue().matches("[A-Za-z0-9_-]{1,128}"))
                    throw new IllegalArgumentException("invalid path parameter");
                target = target.replace("{" + name + "}", value.textValue());
            }
            if (!queryParameters.isEmpty()) {
                JsonNode query = input.path("query");
                var supplied = new java.util.HashSet<String>(); query.fieldNames().forEachRemaining(supplied::add);
                if (!queryParameters.containsAll(supplied) || !supplied.containsAll(requiredQuery))
                    throw new IllegalArgumentException("unknown or missing query parameter");
                var encoded = new StringBuilder();
                for (String name : queryParameters) {
                    JsonNode value = query.get(name); if (value == null) continue;
                    if (!value.isTextual() || !value.textValue().matches("[A-Za-z0-9_-]{1,128}"))
                        throw new IllegalArgumentException("invalid query parameter");
                    if (!encoded.isEmpty()) encoded.append('&');
                    encoded.append(name).append('=').append(value.textValue());
                }
                if (!encoded.isEmpty()) target += "?" + encoded;
            }
            return new BoundRequest(URI.create(target), input.path("body").toString());
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("invalid path and body arguments", invalid); }
    }

    private static List<String> pathParameters(String path) {
        if (!path.startsWith("/") || path.length() > 2048) throw new IllegalArgumentException("invalid operation path");
        var names = new LinkedHashSet<String>();
        String[] parts = path.substring(1).split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            String segment = parts[i];
            if (segment.isEmpty() && i == parts.length - 1 && i > 0) continue;
            if (segment.matches("\\{[A-Za-z][A-Za-z0-9_-]{0,63}\\}")) {
                if (!names.add(segment.substring(1, segment.length() - 1)) || names.size() > 16)
                    throw new IllegalArgumentException("duplicate or excessive path parameters");
            } else if (!segment.matches("[A-Za-z0-9_~-]+(?:\\.[A-Za-z0-9_~-]+)*"))
                throw new IllegalArgumentException("unsupported operation path");
        }
        return List.copyOf(names);
    }

    private record ParameterPlan(String schema, List<String> query, Set<String> requiredQuery) { }

    private static ParameterPlan requestSchema(JsonNode body, JsonNode parameters, JsonNode components, List<String> names) {
        if (parameters == null && names.isEmpty()) return new ParameterPlan(body.toString(), List.of(), Set.of());
        if (parameters == null || !parameters.isArray() || parameters.isEmpty() || parameters.size() > 32)
            throw new IllegalArgumentException("bounded parameters must be declared");
        var pathProperties = JSON.createObjectNode(); var queryProperties = JSON.createObjectNode();
        var found = new java.util.HashSet<String>(); var query = new java.util.TreeSet<String>();
        var requiredQuery = new java.util.HashSet<String>();
        for (JsonNode parameter : parameters) {
            if (parameter.isObject()) {
                var fields = new java.util.HashSet<String>(); parameter.fieldNames().forEachRemaining(fields::add);
                if (!Set.of("name", "in", "required", "schema", "description", "style", "explode", "deprecated").containsAll(fields))
                    throw new IllegalArgumentException("unsupported parameter field");
            }
            String location = parameter.path("in").asText(); boolean isPath = "path".equals(location);
            if (!parameter.isObject() || parameter.has("$ref") || !(isPath || "query".equals(location))
                    || (parameter.has("required") && !parameter.path("required").isBoolean())
                    || (isPath && !parameter.path("required").asBoolean(false))
                    || (parameter.has("style") && !(isPath ? "simple" : "form").equals(parameter.path("style").asText()))
                    || (parameter.has("explode") && (!parameter.path("explode").isBoolean() || (isPath && parameter.path("explode").asBoolean())))
                    || parameter.has("content") || !parameter.path("name").isTextual())
                throw new IllegalArgumentException("unsupported parameter");
            String name = parameter.path("name").asText();
            if (!name.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || (isPath && !names.contains(name))
                    || !found.add(location + ":" + name) || !parameter.path("schema").isObject())
                throw new IllegalArgumentException("unknown or duplicate parameter");
            if (!isPath) { query.add(name); if (query.size() > 16) throw new IllegalArgumentException("too many query parameters");
                if (parameter.path("required").asBoolean(false)) requiredQuery.add(name); }
            JsonNode paramSchema = resolveSchema(parameter.path("schema"), components, new java.util.HashSet<>(), new int[]{0}, 0);
            if (!"string".equals(paramSchema.path("type").asText())) throw new IllegalArgumentException("string parameter required");
            var combined = JSON.createObjectNode(); var allOf = combined.putArray("allOf");
            allOf.add(paramSchema); allOf.addObject().put("type", "string").put("pattern", "^[A-Za-z0-9_-]{1,128}$");
            (isPath ? pathProperties : queryProperties).set(name, combined);
        }
        for (String name : names) if (!found.contains("path:" + name)) throw new IllegalArgumentException("missing path parameter");
        var root = JSON.createObjectNode(); root.put("type", "object"); root.put("additionalProperties", false);
        var required = root.putArray("required"); required.add("body");
        var fields = root.putObject("properties"); fields.set("body", body);
        if (!names.isEmpty()) {
            required.add("path"); var pathObject = fields.putObject("path"); pathObject.put("type", "object"); pathObject.put("additionalProperties", false);
            var requiredPath = pathObject.putArray("required"); names.forEach(requiredPath::add); pathObject.set("properties", pathProperties);
        }
        if (!query.isEmpty()) {
            required.add("query"); var queryObject = fields.putObject("query"); queryObject.put("type", "object"); queryObject.put("additionalProperties", false);
            var requiredFields = queryObject.putArray("required"); query.stream().filter(requiredQuery::contains).forEach(requiredFields::add);
            queryObject.set("properties", queryProperties);
        }
        return new ParameterPlan(root.toString(), List.copyOf(query), Set.copyOf(requiredQuery));
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

    /** Null means an HTTP bearer scheme, whose in-band requirement carries no OAuth scopes. */
    private static Set<String> securityScopes(JsonNode root, JsonNode operation) {
        JsonNode security = operation.has("security") ? operation.get("security") : root.path("security");
        if (!security.isArray() || security.size() != 1 || !security.get(0).isObject() || security.get(0).size() != 1)
            throw new IllegalArgumentException("exactly one required bearer or OAuth scheme is supported");
        var requirement = security.get(0).fields().next();
        if (!requirement.getKey().matches("[A-Za-z0-9._-]{1,128}") || !requirement.getValue().isArray()
                || requirement.getValue().size() > 64) throw new IllegalArgumentException("invalid security requirement");
        var scopes = new java.util.HashSet<String>();
        for (JsonNode value : requirement.getValue()) {
            if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 256 || !scopes.add(value.textValue()))
                throw new IllegalArgumentException("invalid or duplicate security scope");
        }
        JsonNode schemes = root.path("components").path("securitySchemes");
        JsonNode scheme = resolveObject(schemes.path(requirement.getKey()), schemes, "securitySchemes");
        if ("http".equals(scheme.path("type").asText()) && "bearer".equalsIgnoreCase(scheme.path("scheme").asText())) {
            if (!scopes.isEmpty()) throw new IllegalArgumentException("bearer roles are unsupported");
            return null;
        }
        if (!"oauth2".equals(scheme.path("type").asText()) || scopes.isEmpty())
            throw new IllegalArgumentException("unsupported security scheme or empty OAuth scopes");
        JsonNode flows = scheme.path("flows");
        boolean declared = false;
        for (String name : new String[]{"implicit", "password", "clientCredentials", "authorizationCode"}) {
            JsonNode flow = flows.path(name); JsonNode available = flow.path("scopes");
            if (!flow.isObject() || !available.isObject()) continue;
            boolean all = true;
            for (String scope : scopes) if (!available.path(scope).isTextual()) { all = false; break; }
            if (all) { declared = true; break; }
        }
        if (!declared) throw new IllegalArgumentException("OAuth scopes are not declared in one flow");
        return Set.copyOf(scopes);
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
