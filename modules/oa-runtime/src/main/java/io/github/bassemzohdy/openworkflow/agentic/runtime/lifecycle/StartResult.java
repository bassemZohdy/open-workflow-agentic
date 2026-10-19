package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

/**
 * The outcome of a start request.
 *
 * @param record  the registry row for the request's idempotency key, as stored after the start
 * @param created true when this request created the instance (and ran the engine start); false when it repeated
 *                an earlier request, in which case nothing was started
 */
public record StartResult(InstanceRecord record, boolean created) {
}
