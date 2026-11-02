-- Test-only companion. A real registered wait must remain active through the decision commit.
-- This checks a registration, not an SDK dispatch lease or checkpoint receipt.
create function human_proposal.registered_wait_live() returns trigger language plpgsql as $$
begin
  if new.wait_key !~ '^[A-Za-z0-9._:-]{1,96}~[0-9a-f]{64}$' or not exists (
    select 1 from task.instance i join inbox.delivery d on d.tenant=i.tenant and d.instance_id=i.id
    where i.tenant=new.tenant and i.id=new.instance_id and i.status in ('RUNNING','WAITING')
      and (i.wait_deadline is null or i.wait_deadline > clock_timestamp())
      and d.wait_key=new.wait_key and d.identity_digest=split_part(new.wait_key,'~',2)
      and d.state='REGISTERED' and d.deadline > clock_timestamp()
  ) then raise exception 'registered human wait is no longer active' using errcode='check_violation'; end if;
  return null;
end $$;
create constraint trigger human_registered_wait_live after insert on human_proposal.decision
  deferrable initially deferred for each row execute function human_proposal.registered_wait_live();
