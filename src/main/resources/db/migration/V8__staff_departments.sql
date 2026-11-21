alter table department add column enabled boolean not null default true;
alter table app_user add column auth_version bigint not null default 0;
alter table app_user alter column password_hash drop not null;
alter table app_user add column oidc_issuer text;
alter table app_user add column oidc_subject text;
alter table app_user add constraint user_oidc_pair check ((oidc_issuer is null) = (oidc_subject is null));
alter table app_user add constraint user_oidc_identity unique (oidc_issuer, oidc_subject);

alter table user_role drop constraint user_role_role_check;
alter table user_role add constraint user_role_role_check
    check (role in ('PATIENT', 'DOCTOR', 'FOLLOWUP', 'OPERATIONS', 'REVIEWER', 'ADMIN'));

create table staff_department (
    user_id bigint not null references app_user(id),
    department_id bigint not null references department(id),
    primary key (user_id, department_id)
);

insert into department(name) values ('全科门诊') on conflict (name) do nothing;
insert into staff_department(user_id, department_id)
select user_id, department_id from doctor
union
select r.user_id, d.id from user_role r cross join department d
where r.role in ('FOLLOWUP', 'REVIEWER', 'OPERATIONS') and d.name = '全科门诊'
on conflict do nothing;

alter table schedule_slot add column department_id bigint references department(id);
update schedule_slot s set department_id = d.department_id from doctor d where s.doctor_id = d.id;
alter table schedule_slot alter column department_id set not null;
create index schedule_department_page on schedule_slot(department_id, id);
create index schedule_open_time on schedule_slot(starts_at, id) where stopped_at is null;

alter table care_episode add column department_id bigint references department(id);
update care_episode e set department_id = s.department_id
from encounter v join appointment a on v.appointment_id = a.id join schedule_slot s on a.slot_id = s.id
where e.encounter_id = v.id;
update care_episode set department_id = (select id from department where name = '全科门诊') where department_id is null;
alter table care_episode alter column department_id set not null;

create table staff_admin_guard (id integer primary key check (id = 1));
insert into staff_admin_guard values (1);
