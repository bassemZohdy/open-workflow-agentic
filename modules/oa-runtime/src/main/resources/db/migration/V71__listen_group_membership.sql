-- Centrally sequenced after V70. Retained selection/order, NOT callback permission or consumption proof.
create table inbox.listen_group (
    tenant varchar(256) not null,
    instance_id text not null,
    task_digest varchar(64) not null,
    group_digest varchar(64) not null check(group_digest ~ '^[0-9a-f]{64}$'),
    spec_json text not null check(octet_length(spec_json)<=131072 and jsonb_typeof(spec_json::jsonb)='object'),
    configuration_json text not null check(octet_length(configuration_json)<=262144 and jsonb_typeof(configuration_json::jsonb)='object'),
    slots_json jsonb not null check(jsonb_typeof(slots_json)='array' and jsonb_array_length(slots_json) between 1 and 128),
    timeout_millis bigint not null check(timeout_millis between 1 and 2592000000),
    registered_at timestamptz not null default clock_timestamp(),
    deadline timestamptz not null,
    last_sequence bigint not null default 0 check(last_sequence>=0),
    capture_complete boolean not null default false,
    write_owner varchar(128) not null,
    write_epoch bigint not null check(write_epoch>0),
    primary key(tenant,instance_id,task_digest),
    unique(group_digest),
    foreign key(tenant,instance_id,task_digest) references inbox.task_claim(tenant,instance_id,task_digest),
    check(deadline=registered_at+timeout_millis*interval '1 millisecond')
);
create table inbox.listen_item (
    tenant varchar(256) not null,
    instance_id text not null,
    task_digest varchar(64) not null,
    sequence bigint not null check(sequence>0),
    role varchar(8) not null check(role in ('DATA','UNTIL')),
    source varchar(1024) not null,
    event_id varchar(256) not null,
    receipt_fingerprint varchar(64) not null check(receipt_fingerprint ~ '^[0-9a-f]{64}$'),
    matched_slots jsonb not null check(jsonb_typeof(matched_slots)='array' and jsonb_array_length(matched_slots) between 1 and 128),
    selecting_owner varchar(128) not null,
    selecting_epoch bigint not null check(selecting_epoch>0),
    selected_at timestamptz not null default clock_timestamp(),
    primary key(tenant,instance_id,task_digest,sequence),
    unique(tenant,instance_id,task_digest,source,event_id),
    unique(tenant,instance_id,task_digest,sequence,role),
    foreign key(tenant,instance_id,task_digest) references inbox.listen_group(tenant,instance_id,task_digest),
    foreign key(tenant,source,event_id) references inbox.message(tenant,source,event_id)
);
create table inbox.listen_member (
    tenant varchar(256) not null,
    instance_id text not null,
    task_digest varchar(64) not null,
    sequence bigint not null,
    role varchar(8) not null,
    ordinal integer not null check(ordinal between 0 and 127),
    filter_fingerprint varchar(64) not null check(filter_fingerprint ~ '^[0-9a-f]{64}$'),
    exclusive_slot boolean not null,
    primary key(tenant,instance_id,task_digest,sequence,role,ordinal),
    foreign key(tenant,instance_id,task_digest,sequence,role) references inbox.listen_item(tenant,instance_id,task_digest,sequence,role)
);
create unique index listen_exclusive_slot on inbox.listen_member(tenant,instance_id,task_digest,role,ordinal) where exclusive_slot;

create function inbox.require_group_owner(t text,i text,d text,o text,e bigint) returns void language plpgsql as $$
declare registry record; claim record;
begin
    select status,wait_deadline into registry from task.instance where tenant=t and id=i for update;
    if not found or registry.status not in ('WAITING','RUNNING') or registry.wait_deadline<=clock_timestamp() then
        raise exception 'listen group lost active registry authority' using errcode='insufficient_privilege';
    end if;
    select owner,epoch,state,expires_at into claim from inbox.task_claim where tenant=t and instance_id=i and task_digest=d for update;
    if not found or claim.owner<>o or claim.epoch<>e or claim.state<>'ACTIVE' or claim.expires_at<=clock_timestamp() then
        raise exception 'listen group lost task ownership' using errcode='insufficient_privilege';
    end if;
end;
$$;
create function inbox.guard_listen_group() returns trigger language plpgsql as $$
declare spec jsonb; role_name text; needed integer; observed integer; expected_complete boolean;
begin
    if tg_op in ('DELETE','TRUNCATE') then
        raise exception 'listen group history is retained' using errcode='insufficient_privilege';
    end if;
    perform inbox.require_group_owner(new.tenant,new.instance_id,new.task_digest,new.write_owner,new.write_epoch);
    if tg_op='INSERT' then
        if new.last_sequence<>0 or new.capture_complete then
            raise exception 'listen group starts without selected input' using errcode='insufficient_privilege';
        end if;
    else
        if row(new.tenant,new.instance_id,new.task_digest,new.group_digest,new.spec_json,new.configuration_json,new.slots_json,
               new.timeout_millis,new.registered_at,new.deadline)
           is distinct from row(old.tenant,old.instance_id,old.task_digest,old.group_digest,old.spec_json,old.configuration_json,old.slots_json,
               old.timeout_millis,old.registered_at,old.deadline)
           or old.capture_complete or new.last_sequence<>old.last_sequence+1 or new.deadline<=clock_timestamp()
           or not exists(select 1 from inbox.listen_item where tenant=new.tenant and instance_id=new.instance_id
                          and task_digest=new.task_digest and sequence=new.last_sequence
                          and selecting_owner=new.write_owner and selecting_epoch=new.write_epoch)
           or not exists(select 1 from inbox.listen_member where tenant=new.tenant and instance_id=new.instance_id
                          and task_digest=new.task_digest and sequence=new.last_sequence) then
            raise exception 'invalid listen group advancement' using errcode='insufficient_privilege';
        end if;
        spec := new.spec_json::jsonb;
        if spec->>'stop'='EXPRESSION' then
            expected_complete := false; -- No processed-item/predicate proof is supplied by selection storage.
        else
            role_name := case when spec->>'stop'='NONE' then 'DATA' else 'UNTIL' end;
            needed := case
                when spec->>'stop'='NONE' and spec->>'strategy' in ('ONE','ALL') then greatest(1,jsonb_array_length(spec->'dataFilters'))
                when spec->>'stop'='ALL' then jsonb_array_length(spec->'stopFilters')
                else 1 end;
            select count(distinct ordinal) into observed from inbox.listen_member where tenant=new.tenant
              and instance_id=new.instance_id and task_digest=new.task_digest and role=role_name;
            expected_complete := observed>=needed;
        end if;
        if new.capture_complete is distinct from expected_complete then
            raise exception 'capture status does not identify selected memberships' using errcode='insufficient_privilege';
        end if;
    end if;
    return new;
end;
$$;
create trigger listen_group_guard before insert or update or delete on inbox.listen_group for each row execute function inbox.guard_listen_group();
create trigger listen_group_no_truncate before truncate on inbox.listen_group for each statement execute function inbox.guard_listen_group();

create function inbox.guard_listen_item() returns trigger language plpgsql as $$
declare g record; m record;
begin
    perform inbox.require_group_owner(new.tenant,new.instance_id,new.task_digest,new.selecting_owner,new.selecting_epoch);
    select last_sequence,capture_complete,deadline into g from inbox.listen_group
      where tenant=new.tenant and instance_id=new.instance_id and task_digest=new.task_digest for update;
    if not found or g.capture_complete or g.deadline<=clock_timestamp() or new.sequence<>g.last_sequence+1 then
        raise exception 'listen item has no open ordered group' using errcode='insufficient_privilege';
    end if;
    select instance_id,fingerprint,retain_until into m from inbox.message where tenant=new.tenant and source=new.source and event_id=new.event_id for share;
    if not found or m.instance_id<>new.instance_id or m.fingerprint<>new.receipt_fingerprint or m.retain_until<=clock_timestamp() then
        raise exception 'listen item does not identify retained scoped input' using errcode='insufficient_privilege';
    end if;
    return new;
end;
$$;
create trigger listen_item_guard before insert on inbox.listen_item for each row execute function inbox.guard_listen_item();
create function inbox.guard_listen_member() returns trigger language plpgsql as $$
declare g record;
begin
    select spec_json::jsonb as spec,slots_json into g from inbox.listen_group
      where tenant=new.tenant and instance_id=new.instance_id and task_digest=new.task_digest;
    if not found or not exists(select 1 from jsonb_array_elements(g.slots_json) s
        where s->>'role'=new.role and (s->>'ordinal')::integer=new.ordinal and s->>'filterFingerprint'=new.filter_fingerprint) then
        raise exception 'membership does not identify a pinned registration' using errcode='insufficient_privilege';
    end if;
    new.exclusive_slot := new.role='UNTIL' or g.spec->>'strategy' in ('ONE','ALL');
    return new;
end;
$$;
create trigger listen_member_guard before insert on inbox.listen_member for each row execute function inbox.guard_listen_member();
create function inbox.retain_listen_history() returns trigger language plpgsql as $$
begin
    raise exception 'listen selection and membership history is immutable' using errcode='insufficient_privilege';
end;
$$;
create trigger listen_item_immutable before update or delete on inbox.listen_item for each row execute function inbox.retain_listen_history();
create trigger listen_item_no_truncate before truncate on inbox.listen_item for each statement execute function inbox.retain_listen_history();
create trigger listen_member_immutable before update or delete on inbox.listen_member for each row execute function inbox.retain_listen_history();
create trigger listen_member_no_truncate before truncate on inbox.listen_member for each statement execute function inbox.retain_listen_history();
