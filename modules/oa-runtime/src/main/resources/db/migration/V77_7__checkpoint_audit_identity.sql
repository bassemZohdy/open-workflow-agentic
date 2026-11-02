-- Stable draft identity for one actual checkpoint observation. This is retained data, not delivery authority.
create table inbox.checkpoint_audit_source (
    tenant varchar(256) not null,
    instance_id text not null,
    wait_key varchar(256) not null,
    identity_digest varchar(64) not null check (identity_digest ~ '^[0-9a-f]{64}$'),
    receipt_fingerprint varchar(64) not null check (receipt_fingerprint ~ '^[0-9a-f]{64}$'),
    dispatch_attempt integer not null check (dispatch_attempt > 0),
    event_id char(26) not null unique check (event_id ~ '^[0-7][0-9A-HJKMNP-TV-Z]{25}$'),
    occurred_at timestamptz not null,
    primary key (tenant,instance_id,wait_key),
    foreign key (tenant,instance_id,wait_key) references inbox.checkpoint_receipt(tenant,instance_id,wait_key)
);
create function inbox.guard_checkpoint_audit_source() returns trigger language plpgsql as $$
begin
    if tg_op <> 'INSERT' then
        raise exception 'checkpoint audit source is immutable' using errcode='insufficient_privilege';
    end if;
    if not exists (select 1 from inbox.checkpoint_receipt p
        where p.tenant=new.tenant and p.instance_id=new.instance_id and p.wait_key=new.wait_key
          and p.identity_digest=new.identity_digest and p.receipt_fingerprint=new.receipt_fingerprint
          and p.dispatch_attempt=new.dispatch_attempt) then
        raise exception 'checkpoint audit source does not match retained proof' using errcode='check_violation';
    end if;
    return new;
end $$;
create trigger checkpoint_audit_source_insert before insert on inbox.checkpoint_audit_source
    for each row execute function inbox.guard_checkpoint_audit_source();
create trigger checkpoint_audit_source_immutable before update or delete on inbox.checkpoint_audit_source
    for each row execute function inbox.guard_checkpoint_audit_source();
create trigger checkpoint_audit_source_no_truncate before truncate on inbox.checkpoint_audit_source
    for each statement execute function inbox.guard_checkpoint_audit_source();
