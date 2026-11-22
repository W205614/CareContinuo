alter table stop_contact
    add column callback_owner_id bigint references app_user(id),
    add column callback_due_at timestamptz,
    add constraint stop_contact_callback_pair check (
        (callback_owner_id is null and callback_due_at is null)
        or (outcome = 'CALL_BACK' and callback_owner_id is not null and callback_due_at is not null)
    );

alter table followup_request add column appointment_id bigint references appointment(id);

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
    when w.kind in ('STOPPED_APPOINTMENT', 'STOP_CALLBACK') then w.object_id
    when ev.kind in ('APPOINTMENT_BOOKED', 'APPOINTMENT_STOPPED') then ev.object_id end
left join schedule_slot slot on slot.id = a.slot_id;
