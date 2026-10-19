-- WP-105d: idempotency of the operations API (docs/lifecycle.md, section 11). One row per accepted mutating request
-- (cancel, retry, recover), keyed by tenant and Idempotency-Key. The row is PENDING while the operation runs and DONE
-- with the response it returned; a repeat of the same request gets that stored response, and the same key for a
-- different request is a conflict. A PENDING row left by a crash blocks its key until an operator removes it.

create table task.operation_request (
    tenant           text         not null check (length(tenant) between 1 and 256),
    idempotency_key  text         not null check (idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    operation        text         not null check (operation in ('CANCEL', 'RETRY', 'RECOVER')),
    instance_id      text         not null check (length(instance_id) = 26),
    operator_kind    text         not null check (operator_kind in ('USER', 'AGENT', 'WORKFLOW', 'SERVICE')),
    operator_id      text         not null check (length(operator_id) between 1 and 256),
    fingerprint      text         not null check (length(fingerprint) = 64),
    state            text         not null check (state in ('PENDING', 'DONE')),
    response_status  integer      null check (response_status between 100 and 599),
    response_body    jsonb        null,
    created_at       timestamptz  not null default now(),
    completed_at     timestamptz  null,
    primary key (tenant, idempotency_key),
    constraint operation_request_done_ck check (
        (state = 'PENDING' and response_status is null and response_body is null and completed_at is null)
        or (state = 'DONE' and response_status is not null and response_body is not null
            and completed_at is not null))
);

create index operation_request_instance_idx on task.operation_request (instance_id, created_at);
