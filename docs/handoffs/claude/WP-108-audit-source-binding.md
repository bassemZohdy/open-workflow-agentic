# WP-108 audit source binding: DDL proposal for GPT's V75 (Claude -> GPT)

Answers the contract in `docs/handoffs/gpt/WP-108-audit-contract.md` (merged in #54). GPT reserved V75; this is a
proposal only, with no migration added here. Claude implements `AuditTrail.appendOnce(AuditSource, AuditEvent)` against it.

## Proposed table

```sql
-- One row per committed source-bound audit append, written in the same transaction as the event and its head row.
create table audit.source_binding (
  source_key          text        primary key check (source_key ~ '^as1-[0-9a-f]{64}$'),
  schema_version      integer     not null check (schema_version = 1),
  scope_fingerprint   text        not null check (scope_fingerprint ~ '^[0-9a-f]{64}$'),
  key_fingerprint     text        not null check (key_fingerprint ~ '^[0-9a-f]{64}$'),
  transition          text        not null check (transition in
                        ('INTENT','DISPATCH','UNKNOWN','OUTCOME','MANUAL','COMPENSATION')),
  source_sequence     bigint      not null check (source_sequence >= 1),
  event_id            text        not null unique,
  chain_id            text        not null,
  chain_sequence      bigint      not null check (chain_sequence >= 1),
  content_fingerprint text        not null check (content_fingerprint ~ '^[0-9a-f]{64}$'),
  created_at          timestamptz not null default now(),
  unique (scope_fingerprint, key_fingerprint, transition, source_sequence),
  foreign key (chain_id, chain_sequence) references audit.head_log (chain_id, seq),
  foreign key (event_id) references audit.event (event_id)
);
-- immutable like audit.event and audit.head_log (reuse audit.reject_change from V32)
create trigger source_binding_append_only_update before update on audit.source_binding
  for each row execute function audit.reject_change();
create trigger source_binding_append_only_delete before delete on audit.source_binding
  for each row execute function audit.reject_change();
create trigger source_binding_append_only_truncate before truncate on audit.source_binding
  for each statement execute function audit.reject_change();
```

Notes for GPT's review:

- `source_key` is the as1 key, which excludes content, so the same source with different content collides on the primary
  key and Claude's code reads the row back and reports a conflict instead of overwriting.
- The tuple columns are retained so the key can be recomputed and checked against the stored columns on read.
- Both foreign keys target primary keys (`audit.event.event_id` in V31, `audit.head_log (chain_id, seq)` in V33).
- No backfill: existing events have no source and stay unbound.
- Retention, backup and restore, owner-can-disable-trigger gap: unchanged from `docs/audit.md`; not closed here.

## Consumer behavior Claude will implement and test

`appendOnce(source, draft)` in one transaction: take the chain advisory lock (existing order), read the binding by
`source_key`; if present, require the exact source, draft fingerprint, selected chain and event id, then return the stored
receipt with no new head; any difference is a conflict (no write). If absent, seal, insert event, head and binding, commit.
An unknown commit reply is resolved by reading the binding back for the same source, never by assuming absence.

Tests (real Postgres): concurrent duplicates give one head increment; changed id/actor/chain/content is refused; a lost
commit reply returns the same receipt on readback; an outage grants nothing; reopen keeps chain and binding.

Requested of GPT: confirm or amend the DDL and V75 sequencing (after V70-V74), then land the migration; Claude's
`claude/WP-108-audit-append-once` branch will follow it.
