# WP-103: what a failing research node leaves (ResearchRunInspector)

Companion to the execution-adapter proposal (branch claude/WP-103-execution-proposal).


Measured with the real graph, a scripted model and the real checkpoint tables (`ResearchRunInspectorTest`):

- A node that throws leaves its thread **open** (unreleased) with the checkpoint written *before* that node: a failure in `plan` leaves one checkpoint (`__START__` → `plan`); a failure in `draft` leaves two (last `plan` → `draft`). Neither is complete.
- A completed run leaves one released thread whose last checkpoint is `review` → `__END__`; running the same name again adds a second thread row.
- So `inspect` reports NONE (no row), PARTIAL (one thread, last checkpoint not leading to `__END__`), COMPLETE (one thread ending at `__END__`, with a digest of that checkpoint's id and stored bytes), REPEATED (more than one thread row). A store that cannot be read is an error, never NONE.
- It is read-only: no graph call, no model call, no deserialization. PARTIAL is never resumed or re-run automatically; it stays UNKNOWN until the manual-resolution operation GPT defines.
