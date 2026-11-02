package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

/** No new instance was created because this process has reached its start capacity. */
public final class StartCapacityException extends RuntimeException {
    public StartCapacityException() { super("workflow start capacity is temporarily full"); }
}
