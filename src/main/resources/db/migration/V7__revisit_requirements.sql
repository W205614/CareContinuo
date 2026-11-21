alter table followup_revision
    add column revisit_from date,
    add column revisit_to date,
    add column revisit_department_id bigint references department(id),
    add constraint followup_revision_revisit_window check (
        (revisit_from is null and revisit_to is null and revisit_department_id is null)
        or (task_kind = 'REVISIT' and revisit_from is not null and revisit_to is not null
            and revisit_department_id is not null and revisit_from <= revisit_to)
    );
