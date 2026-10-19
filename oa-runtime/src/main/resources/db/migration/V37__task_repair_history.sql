-- WP-105c: every repair the reconciler makes, and every condition it finds but cannot repair, is one row here
-- (docs/lifecycle.md, Reconciler). Append-only, like the instance and task history: the same trigger refuses
-- updates, deletes and truncation for every role.

create table task.repair_event (
    id          bigint      generated always as identity primary key,
    occurred_at timestamptz not null default now(),
    kind        text        not null check (length(kind) between 1 and 64),
    subject     text        not null check (length(subject) between 1 and 128),
    outcome     text        not null check (length(outcome) between 1 and 64),
    detail      text        null check (length(detail) <= 512),
    actor_id    text        not null check (length(actor_id) between 1 and 256)
);

create index repair_event_lookup_idx on task.repair_event (kind, subject, occurred_at);

create trigger repair_event_append_only
    before update or delete on task.repair_event
    for each row execute function task.reject_history_change();

create trigger repair_event_no_truncate
    before truncate on task.repair_event
    for each statement execute function task.reject_history_change();
