-- Full pre-classification matches are required for newly retained selections. Historical rows remain
-- nullable and cannot be retried through the new path, because their complete sets cannot be recovered.
alter table inbox.listen_item add column full_match_fingerprint varchar(64);
alter table inbox.listen_item add constraint listen_item_full_match_fingerprint_check
    check (full_match_fingerprint is not null and full_match_fingerprint ~ '^[0-9a-f]{64}$') not valid;

