-- Audit trail (WP-111, M15). Schema `audit` holds the hash-chained event log and its head log.
-- Append-only enforcement is in V32 and V33. Payloads stored by reference live in V34 and may be deleted.
create schema if not exists audit;
