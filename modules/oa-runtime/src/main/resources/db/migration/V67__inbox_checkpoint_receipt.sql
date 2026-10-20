-- Atomic receipt proof: the SDK checkpoint adapter inserts this in the SAME JTA transaction as task completion.
-- V64 receipt -> V65 authority binding -> V66 dispatch protocol -> V67 proof; integrate in numerical order.
create table inbox.checkpoint_receipt (
    tenant varchar(256) not null,
    instance_id text not null,
    wait_key varchar(256) not null,
    identity_digest varchar(64) not null check (identity_digest ~ '^[0-9a-f]{64}$'),
    receipt_fingerprint varchar(64) not null check (receipt_fingerprint ~ '^[0-9a-f]{64}$'),
    dispatch_attempt integer not null check (dispatch_attempt > 0),
    application_id varchar(256) not null,
    engine_instance_id varchar(256) not null,
    task_position varchar(1024) not null,
    iteration integer not null check (iteration > 0),
    checkpoint_at timestamptz not null default clock_timestamp(),
    primary key (tenant,instance_id,wait_key),
    foreign key (tenant,instance_id,wait_key) references inbox.delivery(tenant,instance_id,wait_key)
);

create function inbox.guard_checkpoint_receipt() returns trigger language plpgsql as $$
begin
    raise exception 'checkpoint receipt proof is retained and immutable: % is refused', tg_op
        using errcode = 'insufficient_privilege';
end;
$$;
create trigger inbox_checkpoint_receipt_immutable before update or delete on inbox.checkpoint_receipt
    for each row execute function inbox.guard_checkpoint_receipt();
create trigger inbox_checkpoint_receipt_no_truncate before truncate on inbox.checkpoint_receipt
    for each statement execute function inbox.guard_checkpoint_receipt();
