-- WP-108: preserve V63 and add storage guards to the trusted ledger API.
-- These are integrity controls, not caller authorization or central audit delivery.
create function effect.guard_ledger() returns trigger language plpgsql as $$
begin
    if TG_OP = 'DELETE' then
        raise exception 'effect intents are retained' using errcode = '23514';
    end if;
    if TG_OP = 'INSERT' then
        if NEW.state <> 'INTENDED' or NEW.attempted then
            raise exception 'an effect starts as an unattempted intent' using errcode = '23514';
        end if;
        return NEW;
    end if;
    if row(NEW.thread_name, NEW.node_id, NEW.idempotency_key, NEW.target,
           NEW.request_fingerprint, NEW.purpose, NEW.created_at, NEW.replay_until)
       is distinct from
       row(OLD.thread_name, OLD.node_id, OLD.idempotency_key, OLD.target,
           OLD.request_fingerprint, OLD.purpose, OLD.created_at, OLD.replay_until) then
        raise exception 'effect intent identity and replay contract are immutable' using errcode = '23514';
    end if;
    if (OLD.attempted and not NEW.attempted)
       or (NEW.attempted is distinct from OLD.attempted
           and (OLD.state <> 'INTENDED' or NEW.state <> 'INTENDED')) then
        raise exception 'dispatch attempt is irreversible and precedes an outcome' using errcode = '23514';
    end if;
    if OLD.state = NEW.state then
        if row(NEW.detail, NEW.recorded_at) is distinct from row(OLD.detail, OLD.recorded_at) then
            raise exception 'a repeated outcome does not rewrite its evidence' using errcode = '23514';
        end if;
    elsif not ((OLD.state = 'INTENDED' and NEW.state in ('CONFIRMED','FAILED'))
            or (OLD.state in ('CONFIRMED','FAILED') and NEW.state = 'COMPENSATED')) then
        raise exception 'illegal effect transition' using errcode = '23514';
    end if;
    return NEW;
end;
$$;
create trigger effect_ledger_guard before insert or update or delete on effect.ledger
    for each row execute function effect.guard_ledger();

create function effect.retain_evidence() returns trigger language plpgsql as $$
begin
    raise exception 'effect evidence is retained and immutable' using errcode = '23514';
end;
$$;
create trigger effect_event_immutable before update or delete on effect.event
    for each row execute function effect.retain_evidence();
create trigger effect_compensation_immutable before update or delete on effect.compensation
    for each row execute function effect.retain_evidence();
