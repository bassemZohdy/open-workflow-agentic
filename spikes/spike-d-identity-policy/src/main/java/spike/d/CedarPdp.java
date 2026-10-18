package spike.d;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.AuthorizationRequest;
import com.cedarpolicy.model.AuthorizationResponse;
import com.cedarpolicy.model.AuthorizationSuccessResponse;
import com.cedarpolicy.model.entity.Entities;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.value.CedarList;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.PrimBool;
import com.cedarpolicy.value.PrimLong;
import com.cedarpolicy.value.PrimString;
import com.cedarpolicy.value.Value;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Cedar (cedar-java, in process) behind the Pdp port. Fail-closed by construction:
 * no policy set loaded, a parse error, an engine exception or a response without a decision all deny.
 */
@ApplicationScoped
public class CedarPdp implements Pdp {

    /** Tool catalogue the policies read. In WP-006's real design this would come from the inventory (M18). */
    public record Tool(String requiredScope, String risk) {
    }

    public static final Map<String, Tool> CATALOGUE = Map.of(
            "search", new Tool("cases:read", "low"),
            "close_case", new Tool("cases:write", "high"));

    private final BasicAuthorizationEngine engine = new BasicAuthorizationEngine();
    private volatile PolicySet policies; // null means "nothing loaded": every request is denied
    private volatile String loadError = "policy not loaded";

    @ConfigProperty(name = "spike.policy.resource", defaultValue = "/policies/tool-calls.cedar")
    String policyResource;

    public CedarPdp() {
    }

    @PostConstruct
    void loadConfiguredPolicies() {
        try (InputStream in = CedarPdp.class.getResourceAsStream(policyResource)) {
            if (in == null) {
                fail("missing resource " + policyResource);
                return;
            }
            load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            fail(t.toString());
        }
    }

    /** Parses and installs a policy set. A parse error leaves the engine in deny-all mode. */
    public void load(String cedarText) {
        try {
            policies = PolicySet.parsePolicies(cedarText);
            loadError = null;
        } catch (Throwable t) {
            fail(t.toString());
        }
    }

    private void fail(String why) {
        policies = null;
        loadError = why;
    }

    @Override
    public Decision decide(Request r) {
        PolicySet ps = policies;
        if (ps == null) {
            return Decision.deny("fail closed: " + loadError);
        }
        try {
            Set<Entity> entities = new HashSet<>();
            entities.add(new Entity(uid("Agent", r.agentId())));
            Tool tool = CATALOGUE.get(r.tool());
            if (tool != null) {
                Map<String, Value> attrs = Map.<String, Value>of(
                        "requiredScope", new PrimString(tool.requiredScope()),
                        "risk", new PrimString(tool.risk()));
                entities.add(new Entity(uid("Tool", r.tool()), attrs, Set.<EntityUID>of()));
            }
            Map<String, Value> context = Map.<String, Value>of(
                    "scopes", new CedarList(r.scopes().stream().<Value>map(PrimString::new).toList()),
                    "budgetRemaining", new PrimLong(r.budgetRemaining()),
                    "humanApproved", new PrimBool(r.humanApproved()));
            AuthorizationRequest request = new AuthorizationRequest(
                    uid("Agent", r.agentId()), uid("Action", "call_tool"), uid("Tool", r.tool()), context);

            AuthorizationResponse response = engine.isAuthorized(request, ps, new Entities(entities));
            AuthorizationSuccessResponse success = response.success.orElse(null);
            if (success == null) {
                return Decision.deny("no decision from engine: " + response.errors);
            }
            String detail = success.getErrors().isEmpty() ? "" : " policyErrors=" + success.getErrors();
            return new Decision(success.isAllowed(), success.getReason() + detail);
        } catch (Exception e) {
            return Decision.deny("fail closed, engine error: " + e);
        }
    }

    static EntityUID uid(String type, String id) {
        if (id.contains("\"")) {
            throw new IllegalArgumentException("entity id must not contain quotes: " + id);
        }
        return EntityUID.parse(type + "::\"" + id + "\"")
                .orElseThrow(() -> new IllegalArgumentException("bad entity " + type + " " + id));
    }
}
