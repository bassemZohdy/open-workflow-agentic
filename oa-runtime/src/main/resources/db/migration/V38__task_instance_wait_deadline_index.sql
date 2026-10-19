-- WP-105c: the reconciler finds waits whose deadline has passed (ADR 0019, Deadlines).

create index instance_wait_deadline_idx on task.instance (wait_deadline) where wait_deadline is not null;
