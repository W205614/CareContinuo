create table followup_template (
    id bigint generated always as identity primary key,
    department_id bigint not null references department(id),
    kind text not null check (kind in ('REVISIT', 'PENDING_RESULT', 'REPLY')),
    name text not null,
    content text not null,
    created_by bigint not null references app_user(id),
    created_at timestamptz not null default now(),
    unique(department_id, kind, name)
);
