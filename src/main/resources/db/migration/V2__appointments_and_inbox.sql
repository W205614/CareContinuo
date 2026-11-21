create table department (
    id bigint generated always as identity primary key,
    name text not null unique
);

create table doctor (
    id bigint generated always as identity primary key,
    user_id bigint not null unique references app_user(id),
    department_id bigint not null references department(id),
    display_name text not null
);

create table schedule_slot (
    id bigint generated always as identity primary key,
    doctor_id bigint not null references doctor(id),
    starts_at timestamptz not null,
    ends_at timestamptz not null,
    capacity integer not null check (capacity > 0),
    remaining integer not null check (remaining between 0 and capacity),
    stopped_at timestamptz,
    stop_reason text,
    unique (doctor_id, starts_at),
    check (starts_at < ends_at)
);

create table appointment (
    id bigint generated always as identity primary key,
    patient_id bigint not null references patient(id),
    slot_id bigint not null references schedule_slot(id),
    supersedes_id bigint unique references appointment(id),
    status text not null check (status in ('BOOKED', 'CHECKED_IN', 'IN_CONSULT', 'COMPLETED',
        'CANCELLED', 'RESCHEDULED', 'NO_SHOW', 'STOPPED')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create unique index appointment_one_valid_per_slot on appointment(patient_id, slot_id)
    where status in ('BOOKED', 'CHECKED_IN', 'IN_CONSULT', 'COMPLETED');
create index appointment_slot_status on appointment(slot_id, status);
create index appointment_patient on appointment(patient_id, id desc);

create table appointment_action (
    id bigint generated always as identity primary key,
    appointment_id bigint not null references appointment(id),
    actor_id bigint references app_user(id),
    from_status text,
    to_status text not null,
    reason text,
    occurred_at timestamptz not null default now()
);

create table idempotency_request (
    user_id bigint not null references app_user(id),
    operation text not null,
    request_key text not null,
    fingerprint text not null,
    result_appointment_id bigint references appointment(id),
    result_status text,
    primary key (user_id, operation, request_key)
);

create table encounter (
    id bigint generated always as identity primary key,
    appointment_id bigint not null unique references appointment(id),
    doctor_id bigint not null references doctor(id),
    started_at timestamptz not null,
    completed_at timestamptz,
    note text,
    return_plan text
);

create table outbox_event (
    id bigint generated always as identity primary key,
    dedupe_key text not null unique,
    recipient_user_id bigint not null references app_user(id),
    kind text not null,
    object_id bigint not null,
    title text not null,
    available_at timestamptz not null,
    attempts integer not null default 0,
    state text not null default 'PENDING' check (state in ('PENDING', 'SENT', 'FAILED', 'STALE'))
);
create index outbox_ready on outbox_event(available_at, id) where state = 'PENDING';

create table inbox_message (
    id bigint generated always as identity primary key,
    event_id bigint not null references outbox_event(id),
    recipient_user_id bigint not null references app_user(id),
    created_at timestamptz not null default now(),
    read_at timestamptz,
    unique (event_id, recipient_user_id)
);

create table work_item (
    id bigint generated always as identity primary key,
    kind text not null,
    object_id bigint not null,
    role text not null,
    owner_id bigint references app_user(id),
    status text not null default 'OPEN' check (status in ('OPEN', 'DONE')),
    due_at timestamptz not null,
    resolution text,
    resolved_by bigint references app_user(id),
    resolved_at timestamptz,
    unique (kind, object_id)
);

create table audit_log (
    id bigint generated always as identity primary key,
    actor_id bigint references app_user(id),
    object_type text not null,
    object_id bigint not null,
    action text not null,
    occurred_at timestamptz not null default now()
);
