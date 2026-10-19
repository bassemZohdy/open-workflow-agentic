package io.github.bassemzohdy.openworkflow.agentic.runtime.outbox;

import java.util.List;

/**
 * One page of {@link TaskViewRow}s.
 *
 * @param items      the rows of this page, in instance id order
 * @param nextCursor the cursor of the next page, or null when this is the last page
 */
public record TaskViewPage(List<TaskViewRow> items, String nextCursor) {
}
