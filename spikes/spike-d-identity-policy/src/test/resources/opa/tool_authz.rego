package tool

import rego.v1

# WP-006 spike: same rules as policies/tool-calls.cedar, expressed in Rego for the OPA sidecar.
default allow := false

allow if {
	input.action == "call_tool"
	input.required_scope in input.scopes
	input.budget_remaining > 0
	not high_risk_without_approval
}

high_risk_without_approval if {
	input.risk == "high"
	not input.human_approved
}
