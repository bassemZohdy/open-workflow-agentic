-- WP-105a: history is append-only. Updates, deletes and truncation are refused by the database, whatever role
-- the application uses. Corrections are new rows, never edits.

create function task.reject_history_change() returns trigger
    language plpgsql as $$
begin
    raise exception 'task history is append-only: % on %', tg_op, tg_table_name
        using errcode = 'insufficient_privilege';
end;
$$;

create trigger instance_event_append_only
    before update or delete on task.instance_event
    for each row execute function task.reject_history_change();

create trigger instance_event_no_truncate
    before truncate on task.instance_event
    for each statement execute function task.reject_history_change();

create trigger agent_task_event_append_only
    before update or delete on task.agent_task_event
    for each row execute function task.reject_history_change();

create trigger agent_task_event_no_truncate
    before truncate on task.agent_task_event
    for each statement execute function task.reject_history_change();
