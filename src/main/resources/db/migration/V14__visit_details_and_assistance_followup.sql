alter table schedule_slot
    add column visit_location text,
    add column preparation text;

alter table followup_request
    add column response_due_at timestamptz,
    add column next_update_at timestamptz;

update followup_request r
set response_due_at = coalesce(
    (select w.due_at from work_item w where w.kind = 'FOLLOWUP_REQUEST' and w.object_id = r.id),
    r.created_at + interval '24 hours'
);
alter table followup_request
    alter column response_due_at set default (now() + interval '24 hours'),
    alter column response_due_at set not null;

create table followup_request_reply (
    id bigint generated always as identity primary key,
    request_id bigint not null references followup_request(id),
    outcome text not null,
    public_reply text not null,
    next_update_at timestamptz,
    created_at timestamptz not null default now()
);
create index followup_reply_history on followup_request_reply(request_id, id);

insert into followup_request_reply(request_id, outcome, public_reply, created_at)
select id, outcome, public_reply, coalesce(resolved_at, created_at)
from followup_request where public_reply is not null and outcome is not null;

create table revisit_watch (
    id bigint generated always as identity primary key,
    task_id bigint not null references followup_task(id),
    actor_id bigint not null references app_user(id),
    appointment_id bigint references appointment(id),
    status text not null default 'WAITING' check (status in ('WAITING', 'NOTIFIED', 'CANCELLED')),
    checked_at timestamptz,
    created_at timestamptz not null default now(),
    notified_at timestamptz
);
create unique index revisit_watch_waiting on revisit_watch(task_id) where status = 'WAITING';
create index revisit_watch_poll on revisit_watch(checked_at nulls first, id) where status = 'WAITING';
create index revisit_watch_history on revisit_watch(task_id, id desc);
