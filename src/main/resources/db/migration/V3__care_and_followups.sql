create table care_episode (
    id bigint generated always as identity primary key,
    patient_id bigint not null references patient(id),
    owner_id bigint not null references app_user(id),
    encounter_id bigint unique references encounter(id),
    kind text not null check (kind in ('DISCHARGE', 'OUTPATIENT')),
    reference text not null unique,
    source_version integer not null default 0,
    care_date date not null,
    created_at timestamptz not null default now(),
    check ((kind = 'OUTPATIENT') = (encounter_id is not null))
);

create table source_document (
    id bigint generated always as identity primary key,
    episode_id bigint not null references care_episode(id),
    title text not null
);
create index source_document_episode on source_document(episode_id);

create table document_version (
    id bigint generated always as identity primary key,
    document_id bigint not null references source_document(id),
    version_number integer not null,
    filename text not null,
    media_type text not null,
    content bytea not null,
    author_id bigint not null references app_user(id),
    created_at timestamptz not null default now(),
    unique (document_id, version_number)
);

create table document_segment (
    id bigint generated always as identity primary key,
    version_id bigint not null references document_version(id),
    page_number integer,
    line_number integer not null,
    excerpt text not null
);
create index document_segment_version on document_segment(version_id, id);

create table followup_task (
    id bigint generated always as identity primary key,
    episode_id bigint not null references care_episode(id),
    owner_id bigint not null references app_user(id),
    reviewer_id bigint not null references app_user(id),
    status text not null check (status in ('DRAFT', 'IN_REVIEW', 'APPROVED', 'DISPATCHED',
        'PATIENT_REPLIED', 'VERIFIED', 'CLOSED')),
    version_number integer not null default 1,
    published_revision_id bigint,
    needs_review boolean not null default false,
    created_at timestamptz not null default now()
);
create index followup_task_episode on followup_task(episode_id);
create index followup_task_owner on followup_task(owner_id, id desc);

create table followup_revision (
    id bigint generated always as identity primary key,
    task_id bigint not null references followup_task(id),
    version_number integer not null,
    source_version integer not null,
    task_kind text not null check (task_kind in ('REVISIT', 'PENDING_RESULT')),
    title text not null,
    instructions text not null,
    due_at timestamptz not null,
    manual_reference text,
    author_id bigint not null references app_user(id),
    created_at timestamptz not null default now(),
    unique (task_id, version_number)
);
alter table followup_task add foreign key (published_revision_id) references followup_revision(id);

create table followup_revision_source (
    revision_id bigint not null references followup_revision(id),
    segment_id bigint not null references document_segment(id),
    primary key (revision_id, segment_id)
);

create table followup_review (
    id bigint generated always as identity primary key,
    revision_id bigint not null references followup_revision(id),
    actor_id bigint not null references app_user(id),
    decision text not null check (decision in ('APPROVED', 'RETURNED')),
    reason text,
    created_at timestamptz not null default now()
);

create table followup_feedback (
    id bigint generated always as identity primary key,
    task_id bigint not null references followup_task(id),
    actor_id bigint not null references app_user(id),
    kind text not null check (kind in ('PATIENT', 'CONTACT', 'VERIFICATION', 'CLOSURE')),
    detail text not null,
    created_at timestamptz not null default now()
);
create index followup_feedback_task on followup_feedback(task_id, id);

alter table outbox_event add column episode_id bigint references care_episode(id);
alter table outbox_event add column revision_id bigint references followup_revision(id);
alter table inbox_message add column corrected_at timestamptz;
