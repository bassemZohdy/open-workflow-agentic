package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

/**
 * An optimistic-lock conflict: the stored row changed since the caller read it. The caller reloads the row and
 * decides again. It extends {@link IllegalStateException}, as {@code TaskStore.replace} documents for the same case.
 */
public final class StaleVersionException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public StaleVersionException(String what, long expected, long actual) {
        super(what + " changed: expected version " + expected + " but the stored version is " + actual);
    }
}
