// WP-105d: the operations API's policy (docs/lifecycle.md, section 11). The resource is operation:<name> and the
// request's task id is the instance id. These are the reference rules bundled with the runtime; the production
// snapshot source is not built yet (WP-304), so a deployment must replace this folder through that source.
//
// Scopes: oa.inspect reads an instance; oa.operate retries and recovers it; cancel and pause also accept oa.recover,
// the same scope that the safe-recovery rule needs when the main policy is unavailable (docs/policy.md, section 6).

@id("operations.inspect-instance")
permit (principal is User, action == Action::"operation", resource == Resource::"operation:inspect")
when { context.scopes.contains("oa.inspect") };

@id("operations.retry-instance")
permit (principal is User, action == Action::"operation", resource == Resource::"operation:retry")
when { context.scopes.contains("oa.operate") };

@id("operations.recover-instance")
permit (principal is User, action == Action::"operation", resource == Resource::"operation:recover")
when { context.scopes.contains("oa.operate") };

@id("operations.cancel-instance")
permit (principal is User, action == Action::"operation", resource == Resource::"operation:cancel")
when { context.scopes.contains("oa.operate") || context.scopes.contains("oa.recover") };

@id("operations.pause-instance")
permit (principal is User, action == Action::"operation", resource == Resource::"operation:pause")
when { context.scopes.contains("oa.operate") || context.scopes.contains("oa.recover") };

// WP-103: a verified caller runs the research agent directly (POST /agents/research). Needs oa.agent.run.
@id("operations.run-research-agent")
permit (principal is User, action == Action::"operation", resource == Resource::"agent:research:run")
when { context.scopes.contains("oa.agent.run") };
