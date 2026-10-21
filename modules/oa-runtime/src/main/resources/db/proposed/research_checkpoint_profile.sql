-- PROPOSAL ONLY: outside Flyway. No migration version is allocated by this file.
-- Own the pinned langgraph4j-postgres-saver:1.9.5 v1.1 profile without rewriting history.
-- Activation, application DDL removal, roles, retention and execution authority need separate review.
do $gpt_checkpoint_profile$
declare
    expected_schema constant text := 'public';
    namespace_oid oid;
    thread_rel oid;
    checkpoint_rel oid;
    relation record;
    wanted record;
    actual record;
    thread_key smallint;
    thread_name_key smallint;
    checkpoint_key smallint;
    checkpoint_thread_key smallint;
    saved_at_key smallint;
    btree_oid oid;
begin
    namespace_oid := pg_catalog.to_regnamespace(expected_schema);
    if namespace_oid is null or pg_catalog.current_schema() is distinct from expected_schema then
        raise exception 'checkpoint profile namespace is not the configured target' using errcode='check_violation';
    end if;
    thread_rel := pg_catalog.to_regclass(pg_catalog.format('%I.lg4jthread',expected_schema));
    checkpoint_rel := pg_catalog.to_regclass(pg_catalog.format('%I.lg4jcheckpoint',expected_schema));
    if pg_catalog.to_regclass('lg4jthread') is distinct from thread_rel
       or pg_catalog.to_regclass('lg4jcheckpoint') is distinct from checkpoint_rel then
        raise exception 'checkpoint profile must not shadow another namespace' using errcode='check_violation';
    end if;
    if (thread_rel is null) <> (checkpoint_rel is null) then
        raise exception 'checkpoint profile is partial' using errcode='check_violation';
    end if;
    if exists(select 1 from pg_catalog.pg_class where oid in (thread_rel,checkpoint_rel)
              and (relkind<>'r' or relpersistence<>'p' or relnamespace<>namespace_oid)) then
        -- Reject views/foreign/temporary relations before LOCK can follow their dependencies.
        raise exception 'checkpoint relation is outside the pinned profile' using errcode='check_violation';
    end if;

    if thread_rel is null then
        execute pg_catalog.format('create table %I.lg4jthread (
            thread_id uuid primary key, thread_name varchar(255),
            is_released boolean default false not null)',expected_schema);
        execute pg_catalog.format('create table %I.lg4jcheckpoint (
            checkpoint_id uuid primary key, parent_checkpoint_id uuid,
            thread_id uuid not null, node_id varchar(255), next_node_id varchar(255),
            state_data text not null, state_content_type varchar(100) not null,
            saved_at timestamp with time zone default current_timestamp,
            constraint fk_thread foreign key(thread_id) references %I.lg4jthread(thread_id) on delete cascade)',
            expected_schema,expected_schema);
        execute pg_catalog.format('create index idx_lg4jcheckpoint_thread_id on %I.lg4jcheckpoint(thread_id)',expected_schema);
        execute pg_catalog.format('create index idx_lg4jcheckpoint_thread_id_saved_at_desc on %I.lg4jcheckpoint(thread_id,saved_at desc)',expected_schema);
        execute pg_catalog.format('create unique index idx_unique_lg4jthread_thread_name_unreleased on %I.lg4jthread(thread_name) where is_released=false',expected_schema);
        thread_rel := pg_catalog.to_regclass(pg_catalog.format('%I.lg4jthread',expected_schema));
        checkpoint_rel := pg_catalog.to_regclass(pg_catalog.format('%I.lg4jcheckpoint',expected_schema));
    else
        -- Refuse a live writer immediately. Never wait indefinitely or modify a working graph's history.
        execute pg_catalog.format('lock table %I.lg4jthread,%I.lg4jcheckpoint in access exclusive mode nowait',
            expected_schema,expected_schema);
    end if;
    if pg_catalog.to_regclass(pg_catalog.format('%I.lg4jthread',expected_schema)) is distinct from thread_rel
       or pg_catalog.to_regclass(pg_catalog.format('%I.lg4jcheckpoint',expected_schema)) is distinct from checkpoint_rel then
        raise exception 'checkpoint relation changed during adoption' using errcode='check_violation';
    end if;

    for relation in select * from pg_catalog.pg_class where oid in (thread_rel,checkpoint_rel) loop
        if relation.relkind <> 'r' or relation.relpersistence <> 'p'
           or relation.relnamespace <> namespace_oid or relation.relrowsecurity or relation.relforcerowsecurity
           or relation.relhasrules then
            raise exception 'checkpoint relation is outside the pinned profile' using errcode='check_violation';
        end if;
    end loop;
    if exists(select 1 from pg_catalog.pg_trigger where tgrelid in (thread_rel,checkpoint_rel) and not tgisinternal)
       or exists(select 1 from pg_catalog.pg_inherits where inhrelid in (thread_rel,checkpoint_rel)
                                                      or inhparent in (thread_rel,checkpoint_rel))
       or exists(select 1 from pg_catalog.pg_constraint where conrelid in (thread_rel,checkpoint_rel) and contype not in ('p','f')) then
        raise exception 'checkpoint relation has unsupported inheritance, constraints or triggers' using errcode='check_violation';
    end if;

    for wanted in select * from (values
        ('lg4jthread','thread_id','uuid',true,null::text),
        ('lg4jthread','thread_name','character varying(255)',false,null),
        ('lg4jthread','is_released','boolean',true,'false'),
        ('lg4jcheckpoint','checkpoint_id','uuid',true,null),
        ('lg4jcheckpoint','parent_checkpoint_id','uuid',false,null),
        ('lg4jcheckpoint','thread_id','uuid',true,null),
        ('lg4jcheckpoint','node_id','character varying(255)',false,null),
        ('lg4jcheckpoint','next_node_id','character varying(255)',false,null),
        ('lg4jcheckpoint','state_data','text',true,null),
        ('lg4jcheckpoint','state_content_type','character varying(100)',true,null),
        ('lg4jcheckpoint','saved_at','timestamp with time zone',false,'CURRENT_TIMESTAMP')
    ) as profile(table_name,column_name,data_type,not_null,default_value) loop
        select pg_catalog.format_type(a.atttypid,a.atttypmod) as data_type,a.attnotnull as not_null,
               a.attgenerated,a.attidentity,pg_catalog.pg_get_expr(d.adbin,d.adrelid) as default_value
          into actual from pg_catalog.pg_attribute a
          left join pg_catalog.pg_attrdef d on d.adrelid=a.attrelid and d.adnum=a.attnum
          where a.attrelid=case when wanted.table_name='lg4jthread' then thread_rel else checkpoint_rel end
            and a.attname=wanted.column_name and a.attnum>0 and not a.attisdropped;
        if not found or actual.data_type is distinct from wanted.data_type
           or actual.not_null is distinct from wanted.not_null or actual.default_value is distinct from wanted.default_value
           or actual.attgenerated<>'' or actual.attidentity<>'' then
            raise exception 'checkpoint column is outside the pinned profile' using errcode='check_violation';
        end if;
    end loop;
    if (select count(*) from pg_catalog.pg_attribute where attrelid=thread_rel and attnum>0 and not attisdropped)<>3
       or (select count(*) from pg_catalog.pg_attribute where attrelid=checkpoint_rel and attnum>0 and not attisdropped)<>8 then
        raise exception 'checkpoint columns are outside the pinned profile' using errcode='check_violation';
    end if;

    select attnum into thread_key from pg_catalog.pg_attribute where attrelid=thread_rel and attname='thread_id';
    select attnum into thread_name_key from pg_catalog.pg_attribute where attrelid=thread_rel and attname='thread_name';
    select attnum into checkpoint_key from pg_catalog.pg_attribute where attrelid=checkpoint_rel and attname='checkpoint_id';
    select attnum into checkpoint_thread_key from pg_catalog.pg_attribute where attrelid=checkpoint_rel and attname='thread_id';
    select attnum into saved_at_key from pg_catalog.pg_attribute where attrelid=checkpoint_rel and attname='saved_at';
    if not exists(select 1 from pg_catalog.pg_constraint where conrelid=thread_rel and contype='p'
                   and conkey=array[thread_key] and not condeferrable and not condeferred and convalidated)
       or not exists(select 1 from pg_catalog.pg_constraint where conrelid=checkpoint_rel and contype='p'
                   and conkey=array[checkpoint_key] and not condeferrable and not condeferred and convalidated)
       or (select count(*) from pg_catalog.pg_constraint where conrelid in (thread_rel,checkpoint_rel) and contype='f')<>1
       or not exists(select 1 from pg_catalog.pg_constraint where conrelid=checkpoint_rel and contype='f'
                   and confrelid=thread_rel and conkey=array[checkpoint_thread_key] and confkey=array[thread_key]
                   and confdeltype='c' and confupdtype='a' and confmatchtype='s'
                   and not condeferrable and not condeferred and convalidated) then
        raise exception 'checkpoint keys are outside the pinned profile' using errcode='check_violation';
    end if;

    select oid into btree_oid from pg_catalog.pg_am where amname='btree';
    for wanted in select * from (values
        (checkpoint_rel,'idx_lg4jcheckpoint_thread_id',false,checkpoint_thread_key::text,1,'0',null::text,'uuid_ops',null::text),
        (checkpoint_rel,'idx_lg4jcheckpoint_thread_id_saved_at_desc',false,checkpoint_thread_key::text||' '||saved_at_key::text,2,'0 3',null,'uuid_ops','timestamptz_ops'),
        (thread_rel,'idx_unique_lg4jthread_thread_name_unreleased',true,thread_name_key::text,1,'0','(is_released = false)','text_ops',null)
    ) as profile(relation_oid,index_name,is_unique,keys,key_count,sort_options,predicate,first_opclass,second_opclass) loop
        select i.*,x.relam,pg_catalog.pg_get_expr(i.indpred,i.indrelid) as predicate
          into actual from pg_catalog.pg_index i join pg_catalog.pg_class x on x.oid=i.indexrelid
          where i.indrelid=wanted.relation_oid and x.relname=wanted.index_name and x.relnamespace=namespace_oid;
        if not found or actual.relam<>btree_oid or not actual.indisvalid or not actual.indisready
           or not actual.indimmediate or actual.indisprimary or actual.indisunique<>wanted.is_unique
           or actual.indnkeyatts<>wanted.key_count or actual.indnatts<>wanted.key_count
           or actual.indkey::text<>wanted.keys or actual.indoption::text<>wanted.sort_options
           or actual.indexprs is not null or actual.predicate is distinct from wanted.predicate then
            raise exception 'checkpoint index is outside the pinned profile' using errcode='check_violation';
        end if;
        if not exists(select 1 from pg_catalog.pg_opclass where oid=actual.indclass[0]
                      and opcnamespace='pg_catalog'::regnamespace and opcmethod=btree_oid and opcname=wanted.first_opclass)
           or actual.indcollation[0] is distinct from (select attcollation from pg_catalog.pg_attribute
                      where attrelid=wanted.relation_oid and attnum=(case when wanted.relation_oid=thread_rel then thread_name_key else checkpoint_thread_key end))
           or (wanted.key_count=2 and (not exists(select 1 from pg_catalog.pg_opclass where oid=actual.indclass[1]
                      and opcnamespace='pg_catalog'::regnamespace and opcmethod=btree_oid and opcname=wanted.second_opclass)
                      or actual.indcollation[1]<>0)) then
            raise exception 'checkpoint index operators are outside the pinned profile' using errcode='check_violation';
        end if;
    end loop;
    if (select count(*) from pg_catalog.pg_index where indrelid=thread_rel and indisunique)<>2
       or (select count(*) from pg_catalog.pg_index where indrelid=checkpoint_rel and indisunique)<>1 then
        raise exception 'checkpoint uniqueness is outside the pinned profile' using errcode='check_violation';
    end if;
end;
$gpt_checkpoint_profile$;
