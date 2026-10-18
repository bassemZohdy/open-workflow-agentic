// WP-006 spike: tool calls by an agent acting for a user.
// Default is deny: allowed only when a permit matches and no forbid matches.

@id("permit-scoped-budgeted-call")
permit (
    principal is Agent,
    action == Action::"call_tool",
    resource is Tool
)
when {
    context.scopes.contains(resource.requiredScope) &&
    context.budgetRemaining > 0
};

@id("forbid-high-risk-without-approval")
forbid (
    principal,
    action == Action::"call_tool",
    resource is Tool
)
when {
    resource.risk == "high" && !context.humanApproved
};
