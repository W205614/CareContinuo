alter table agent_job drop constraint agent_job_status_check;
alter table agent_job add constraint agent_job_status_check
    check (status in ('QUEUED', 'RUNNING', 'READY', 'CONFLICT', 'FAILED', 'STALE', 'RESOLVED'));

-- Restore messages that are still current and were discarded while the conflict was unresolved.
update outbox_event o set state = 'PENDING'
from followup_task t join care_episode e on e.id = t.episode_id
join followup_revision r on r.id = t.published_revision_id
where o.state = 'STALE' and o.episode_id = e.id and o.object_id = t.id and o.revision_id = r.id
  and not t.needs_review and r.source_version = e.source_version
  and (t.status in ('DISPATCHED', 'PATIENT_REPLIED')
    or (o.kind <> 'FOLLOWUP_DUE' and t.status in ('VERIFIED', 'CLOSED')))
  and exists(select 1 from agent_job j where j.episode_id = e.id
    and j.source_version = e.source_version and j.status = 'CONFLICT');
