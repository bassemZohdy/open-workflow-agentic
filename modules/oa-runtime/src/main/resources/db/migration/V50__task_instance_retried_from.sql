-- ADR 0019, amendment 3 (issue #23): a retry of a FAILED instance creates a new instance linked to the old one.
-- FAILED stays terminal; the old row is never reopened.

alter table task.instance
    add column retried_from text null,
    add constraint instance_retried_from_fk foreign key (retried_from) references task.instance (id),
    add constraint instance_retried_from_not_self check (retried_from is null or retried_from <> id);

-- The foreign key and the inspection of "instances created from this one" both look up by retried_from.
create index instance_retried_from_idx on task.instance (retried_from);

-- At most one live (non-terminal) retry per failed instance (docs/lifecycle.md, section 11.3). A second retry while the
-- first is live is refused; once the first is terminal, a new retry may be created.
create unique index instance_one_live_retry_uk on task.instance (retried_from)
    where retried_from is not null and status not in ('CANCELLED', 'COMPLETED', 'FAILED');
