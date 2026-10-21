package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

/**
 * The closed set of actions a person can take on a human task (WP-106). The scope names are proposed and need the
 * product owner's approval; until then they are the only ones the enforcement point checks.
 */
public enum HumanAction {
    /** Records the person's decision on a task they hold. */
    DECIDE("decide"),
    /** Reassigns or redirects the task. */
    ROUTE("route"),
    /** Withdraws the task. */
    CANCEL("cancel");

    private final String verb;

    HumanAction(String verb) {
        this.verb = verb;
    }

    /** The verb used in the request resource, {@code human-task:<id>:<verb>}. */
    public String verb() {
        return verb;
    }

    /** The scope the actor must hold, {@code human-task:<verb>}. */
    public String requiredScope() {
        return "human-task:" + verb;
    }
}
