-- Apply after V77.9 as the migration owner. Replace the role identifier placeholder.
-- Read-only resource-call provenance for the opt-in manual effect resolver.
grant usage on schema task to __MANUAL_PROVENANCE_READER__;
grant select on task.resource_call to __MANUAL_PROVENANCE_READER__;
grant select (call_key,occurrence_key) on task.resource_occurrence to __MANUAL_PROVENANCE_READER__;
