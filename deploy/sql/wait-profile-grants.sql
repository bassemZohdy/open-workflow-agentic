-- Apply after V77.8 as migration owner. Replace both role identifier placeholders.
-- Additive profile permissions only; registry and delivery privileges are managed separately.
grant usage on schema inbox to __WAIT_PROFILE_WRITER__, __WAIT_PROFILE_READER__;
grant select, insert on inbox.wait_profile to __WAIT_PROFILE_WRITER__;
grant select on inbox.wait_profile to __WAIT_PROFILE_READER__;
