alter table outbox_event add column manual_retry_at timestamptz;

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
    when ev.kind in ('APPOINTMENT_BOOKED', 'APPOINTMENT_STOPPED', 'APPOINTMENT_CALLBACK_UPDATED') then ev.object_id end
left join schedule_slot slot on slot.id = a.slot_id;
