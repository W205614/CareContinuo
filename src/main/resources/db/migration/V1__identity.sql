create table patient (
    id bigint generated always as identity primary key,
    reference text not null unique,
    display_name text not null
);

create table app_user (
    id bigint generated always as identity primary key,
    username text not null unique,
    password_hash text not null,
    display_name text not null,
    patient_id bigint unique references patient(id),
    enabled boolean not null default true
);

create table user_role (
    user_id bigint not null references app_user(id),
    role text not null check (role in ('PATIENT', 'DOCTOR', 'FOLLOWUP', 'OPERATIONS', 'REVIEWER')),
    primary key (user_id, role)
);
