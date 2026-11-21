create table agent_job (
    id bigint generated always as identity primary key,
    episode_id bigint not null references care_episode(id),
    created_by bigint not null references app_user(id),
    source_version integer not null,
    status text not null check (status in ('QUEUED', 'RUNNING', 'READY', 'CONFLICT', 'FAILED', 'STALE')),
    started_at timestamptz,
    expires_at timestamptz,
    outcome jsonb,
    failure_code text,
    created_at timestamptz not null default now()
);
create index agent_job_ready on agent_job(id) where status = 'QUEUED';
create index agent_job_episode on agent_job(episode_id, id desc);
create index agent_current_conflicts on agent_job(episode_id, source_version) where status = 'CONFLICT';
