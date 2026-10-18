package tool

import rego.v1

# Deliberately malformed: the sidecar must not start with this bundle.
allow if {
	input.action ==
}
