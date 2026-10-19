-- WP-102 slice d/f: the identity an instance runs under, kept as identifiers only.
--
-- One row per registry instance: the subject (user) who started it, the delegation grant it runs under (or none), the
-- correlation id of the start request, and whether the grant must be re-authorized before the instance may continue.
-- No token, no secret and no credential is stored here or anywhere else in workflow state (docs/identity.md section 7).
-- The length limits, the character set and the refusal of a value that starts like a JWT header ("eyJ") are a second
-- line of defence: a token does not fit them.
--
-- The row is written in the same transaction as the instance row, so an instance started under a grant always has its
-- authority row. An instance without a row (started before this version) has no delegated authority.
CREATE TABLE identity.instance_authority (
    instance_id    text        PRIMARY KEY CHECK (instance_id ~ '^[0-9A-Z]{26}$'),
    tenant         text        NOT NULL CHECK (char_length(tenant) BETWEEN 1 AND 256),
    subject_kind   text        NOT NULL CHECK (subject_kind IN ('USER', 'AGENT', 'WORKFLOW', 'SERVICE')),
    subject_id     text        NOT NULL CHECK (char_length(btrim(subject_id)) BETWEEN 1 AND 256
                                             AND subject_id NOT LIKE 'eyJ%'),
    grant_id       text        CHECK (grant_id ~ '^[0-9A-Z]{26}$'),
    correlation_id text        CHECK (correlation_id ~ '^[A-Za-z0-9._:-]{1,128}$'
                                             AND correlation_id NOT LIKE 'eyJ%'),
    state          text        NOT NULL DEFAULT 'AUTHORIZED' CHECK (state IN ('AUTHORIZED', 'REAUTHORIZATION_REQUIRED')),
    state_reason   text        CHECK (state_reason ~ '^[a-z_.:-]{1,64}$'),
    state_since    timestamptz NOT NULL,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    CHECK (state = 'AUTHORIZED' OR state_reason IS NOT NULL),
    CHECK (state = 'AUTHORIZED' OR grant_id IS NOT NULL)
);

CREATE INDEX instance_authority_state_idx ON identity.instance_authority (tenant, state);

-- Identity never changes after the start; only the grant binding and the state do.
CREATE FUNCTION identity.guard_instance_authority() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'instance authority rows are not deleted';
    END IF;
    IF NEW.instance_id <> OLD.instance_id OR NEW.tenant <> OLD.tenant OR NEW.subject_kind <> OLD.subject_kind
       OR NEW.subject_id <> OLD.subject_id OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'the subject, tenant and correlation id of an instance do not change';
    END IF;
    IF OLD.grant_id IS NULL AND NEW.grant_id IS NOT NULL THEN
        RAISE EXCEPTION 'an instance without a grant cannot gain one';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER instance_authority_guard BEFORE UPDATE OR DELETE ON identity.instance_authority
    FOR EACH ROW EXECUTE FUNCTION identity.guard_instance_authority();
