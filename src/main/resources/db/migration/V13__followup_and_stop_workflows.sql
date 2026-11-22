create table followup_request (
    id bigint generated always as identity primary key,
    task_id bigint not null references followup_task(id),
    category text not null check (category in ('REVISIT_ASSISTANCE', 'COMPLETED', 'DIFFICULTY', 'QUESTION')),
    patient_note text,
    status text not null default 'SUBMITTED' check (status in ('SUBMITTED', 'IN_PROGRESS', 'RESOLVED')),
    outcome text check (outcome in ('SLOTS_AVAILABLE', 'NO_SLOTS', 'REVIEW_NEEDED', 'BOOKED', 'TASK_ENDED', 'REPLIED')),
    public_reply text,
    created_at timestamptz not null default now(),
    started_at timestamptz,
    resolved_at timestamptz,
    resolved_by bigint references app_user(id)
);
create index followup_request_task on followup_request(task_id, id desc);
create unique index one_open_revisit_request on followup_request(task_id)
    where category = 'REVISIT_ASSISTANCE' and status <> 'RESOLVED';

create table followup_result_update (
    id bigint generated always as identity primary key,
    revision_id bigint not null references followup_revision(id),
    actor_id bigint not null references app_user(id),
    stage text not null check (stage in ('CHECKING', 'PLAN_AVAILABLE')),
    patient_message text,
    created_at timestamptz not null default now()
);
create index followup_result_revision on followup_result_update(revision_id, id desc);

create table stop_contact (
    id bigint generated always as identity primary key,
    appointment_id bigint not null references appointment(id),
    operator_id bigint not null references app_user(id),
    outcome text not null check (outcome in ('REACHED', 'NO_ANSWER', 'CALL_BACK')),
    note text,
    contacted_at timestamptz not null default now()
);
create index stop_contact_appointment on stop_contact(appointment_id, id desc);

create or replace view work_item_department as
select w.id, coalesce(ep.department_id, slot.department_id) as department_id
from work_item w
left join outbox_event ev on w.kind = 'NOTIFICATION_FAILED' and ev.id = w.object_id
left join agent_job j on w.kind = 'AGENT_CONFLICT' and j.id = w.object_id
left join followup_request req on w.kind = 'FOLLOWUP_REQUEST' and req.id = w.object_id
left join followup_task f on f.id = case when w.kind in ('FOLLOWUP_OVERDUE', 'FOLLOWUP_REVIEW',
    'PATIENT_FEEDBACK', 'FOLLOWUP_TRANSFER', 'SOURCE_CHANGED') then w.object_id else req.task_id end
left join care_episode ep on ep.id = coalesce(ev.episode_id, j.episode_id, f.episode_id)
left join appointment a on a.id = case
    when w.kind = 'STOPPED_APPOINTMENT' then w.object_id
    when ev.kind in ('APPOINTMENT_BOOKED', 'APPOINTMENT_STOPPED') then ev.object_id end
left join schedule_slot slot on slot.id = a.slot_id;
