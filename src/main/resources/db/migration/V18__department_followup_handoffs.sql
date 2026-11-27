alter table followup_task add column owner_department_id bigint references department(id);
update followup_task t set owner_department_id = e.department_id from care_episode e where e.id = t.episode_id;
alter table followup_task alter column owner_department_id set not null;

create table followup_handoff (
    id bigint generated always as identity primary key,
    task_id bigint not null references followup_task(id),
    revision_id bigint not null references followup_revision(id),
    sender_id bigint not null references app_user(id),
    recipient_id bigint not null references app_user(id),
    from_department_id bigint not null references department(id),
    to_department_id bigint not null references department(id),
    created_at timestamptz not null,
    due_at timestamptz not null,
    status text not null default 'PENDING' check (status in ('PENDING', 'ACCEPTED', 'RETURNED', 'TIMED_OUT', 'SUPERSEDED')),
    resolved_at timestamptz,
    return_reason text,
    check (from_department_id <> to_department_id and sender_id <> recipient_id),
    check ((status = 'PENDING') = (resolved_at is null)),
    check ((status = 'RETURNED') = (return_reason is not null))
);

create unique index followup_handoff_pending on followup_handoff(task_id) where status = 'PENDING';
create index followup_handoff_due on followup_handoff(due_at, id) where status = 'PENDING';
create index followup_handoff_sender on followup_handoff(sender_id, id desc);
create index followup_handoff_recipient on followup_handoff(recipient_id, id desc);

create or replace view work_item_department as
select w.id, case
    when h.id is not null then h.to_department_id
    when f.id is not null and w.kind <> 'FOLLOWUP_REVIEW' then f.owner_department_id
    else coalesce(ep.department_id, slot.department_id) end as department_id,
    h.id is not null or coalesce(w.kind <> 'FOLLOWUP_REVIEW' and f.owner_department_id <> ep.department_id, false) as strict_department
from work_item w
left join followup_handoff h on w.kind = 'FOLLOWUP_HANDOFF' and h.id = w.object_id
left join outbox_event ev on w.kind = 'NOTIFICATION_FAILED' and ev.id = w.object_id
left join agent_job j on w.kind = 'AGENT_CONFLICT' and j.id = w.object_id
left join followup_request req on w.kind = 'FOLLOWUP_REQUEST' and req.id = w.object_id
left join followup_task f on f.id = case
    when w.kind in ('FOLLOWUP_OVERDUE', 'FOLLOWUP_REVIEW', 'PATIENT_FEEDBACK', 'FOLLOWUP_TRANSFER', 'SOURCE_CHANGED') then w.object_id
    when ev.episode_id is not null then ev.object_id
    else req.task_id end
left join care_episode ep on ep.id = coalesce(ev.episode_id, j.episode_id, f.episode_id)
left join appointment a on a.id = case
    when w.kind in ('STOPPED_APPOINTMENT', 'STOP_CALLBACK') then w.object_id
    when ev.kind in ('APPOINTMENT_BOOKED', 'APPOINTMENT_STOPPED', 'APPOINTMENT_CALLBACK_UPDATED') then ev.object_id end
left join schedule_slot slot on slot.id = a.slot_id;
