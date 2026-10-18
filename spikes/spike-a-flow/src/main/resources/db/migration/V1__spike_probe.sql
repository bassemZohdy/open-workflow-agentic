-- Spike-owned observation table. Not part of any product schema.
-- Flow tables are NOT created here: quarkus-flow-jpa maps Hibernate entities (see findings, Q06).
create table if not exists spike_probe (
  id bigserial primary key,
  tag text not null,
  instance_id text,
  task_id text,
  correlation text,
  detail text,
  created_at timestamp with time zone not null default now()
);
