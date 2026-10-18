-- Payloads kept outside the event log for the STORE_REFERENCE_ONLY retention form. Deliberately NOT append-only:
-- retention may delete a payload. The event keeps only the reference and the digest, so the chain still verifies.
create table if not exists audit.payload_blob (
  ref_id    text        primary key check (ref_id ~ '^[0-7][0-9A-HJKMNP-TV-Z]{25}$'),
  content   jsonb       not null check (jsonb_typeof(content) = 'object'),
  digest    text        not null check (digest ~ '^[0-9a-f]{64}$'),
  stored_at timestamptz not null default now()
);
