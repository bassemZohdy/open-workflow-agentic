-- Entry audit is a distinct source kind; existing effect source identities remain unchanged.
alter table audit.source_binding drop constraint source_binding_transition_check;
alter table audit.source_binding add constraint source_binding_transition_check
    check (transition in ('INTENT','DISPATCH','UNKNOWN','OUTCOME','MANUAL','COMPENSATION','RESOURCE_ENTRY'));

-- Only an entry producer in the transaction which inserted the entry may create this record.
-- Historical entries are deliberately not backfilled. The writing role must be restricted to that producer.
create table task.resource_execution_audit_source (
    entry_key text primary key references task.resource_execution(entry_key),
    scope_fingerprint text not null check (scope_fingerprint ~ '^[0-9a-f]{64}$'),
    key_fingerprint text not null check (key_fingerprint ~ '^[0-9a-f]{64}$'),
    source_key text not null unique check (source_key ~ '^as1-[0-9a-f]{64}$'),
    draft_id text not null unique,
    content_fingerprint text not null check (content_fingerprint ~ '^[0-9a-f]{64}$'),
    check (source_key = audit.source_identity_key(1, scope_fingerprint, key_fingerprint, 'RESOURCE_ENTRY', 1))
);
create trigger resource_execution_audit_source_no_change before update or delete on task.resource_execution_audit_source
    for each row execute function audit.reject_change();
create trigger resource_execution_audit_source_no_truncate before truncate on task.resource_execution_audit_source
    for each statement execute function audit.reject_change();
