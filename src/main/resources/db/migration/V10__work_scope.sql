create view work_item_department as
select w.id, coalesce(ep.department_id, slot.department_id) as department_id
from work_item w
left join outbox_event ev on w.kind = 'NOTIFICATION_FAILED' and ev.id = w.object_id
left join agent_job j on w.kind = 'AGENT_CONFLICT' and j.id = w.object_id
left join followup_task f on w.kind in ('FOLLOWUP_OVERDUE', 'FOLLOWUP_REVIEW', 'PATIENT_FEEDBACK',
    'FOLLOWUP_TRANSFER', 'SOURCE_CHANGED') and f.id = w.object_id
left join care_episode ep on ep.id = coalesce(ev.episode_id, j.episode_id, f.episode_id)
left join appointment a on a.id = case
    when w.kind = 'STOPPED_APPOINTMENT' then w.object_id
    when ev.kind in ('APPOINTMENT_BOOKED', 'APPOINTMENT_STOPPED') then ev.object_id end
left join schedule_slot slot on slot.id = a.slot_id;

create index running_agent_expiry on agent_job(expires_at, id) where status = 'RUNNING';
create index work_item_owner_page on work_item(owner_id, id) where status = 'OPEN';
create index care_department_page on care_episode(department_id, id);
