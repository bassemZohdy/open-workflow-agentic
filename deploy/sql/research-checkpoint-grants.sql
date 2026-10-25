-- Apply after V75.1 using distinct fresh non-owner identities with no other powers.
-- Replace identifier placeholders; no credentials or application wiring are changed.
grant usage on schema public to __CHECKPOINT_WRITER_ROLE__, __CHECKPOINT_READER_ROLE__;
grant select, insert, update on public.lg4jthread, public.lg4jcheckpoint to __CHECKPOINT_WRITER_ROLE__;
grant select on public.lg4jthread, public.lg4jcheckpoint to __CHECKPOINT_READER_ROLE__;
