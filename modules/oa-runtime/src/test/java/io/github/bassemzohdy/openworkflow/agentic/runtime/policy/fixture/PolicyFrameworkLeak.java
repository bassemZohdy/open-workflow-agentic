package io.github.bassemzohdy.openworkflow.agentic.runtime.policy.fixture;

import jakarta.inject.Inject;

/** Deliberate violation: a policy-package class that depends on a framework. Used only by PolicyArchitectureTest. */
public class PolicyFrameworkLeak {

    @Inject
    String injected;
}
