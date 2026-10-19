-- WP-105c: resume epoch of a registry row. It counts the restarts that the reconciler performed for the instance
-- (ADR 0019, Resume after restart). A task that runs again after a restart sees a higher epoch, so the effect
-- ledger (WP-108) can key its idempotency keys on it without building that ledger here.

alter table task.instance
    add column resume_epoch bigint not null default 0 check (resume_epoch >= 0);
