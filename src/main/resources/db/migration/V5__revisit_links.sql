create table followup_appointment (
    task_id bigint not null references followup_task(id),
    appointment_id bigint not null unique references appointment(id),
    linked_at timestamptz not null default now(),
    primary key (task_id, appointment_id)
);

alter table followup_feedback add column source_encounter_id bigint references encounter(id);
