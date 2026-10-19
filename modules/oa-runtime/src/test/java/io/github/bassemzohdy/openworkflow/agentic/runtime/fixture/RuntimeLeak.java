package io.github.bassemzohdy.openworkflow.agentic.runtime.fixture;

import io.github.bassemzohdy.openworkflow.agentic.control.fixture.ControlFixture;

/** Deliberately violates the runtime rule: runtime code depends on control code. Test sources only. */
public final class RuntimeLeak {
    public static String leak() {
        return ControlFixture.name();
    }
}
