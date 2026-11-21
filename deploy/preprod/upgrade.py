"""Verify a V7 data upgrade in a fresh database inside a verified synthetic stack."""
import argparse
from pathlib import Path
from secrets import token_hex

from control import docker, psql, verify


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", required=True, type=Path)
    parser.add_argument("--project", default="carecontinuo-preprod")
    args = parser.parse_args()
    compose, _ = verify(args.env_file, args.project)
    target = "care_upgrade_" + token_hex(4)
    docker(*compose, "exec", "-T", "postgres", "createdb", "-U", "postgres", target)
    psql(compose, """
        alter schema public owner to care_migrator;
        grant usage on schema public to care_app;
        alter default privileges for role care_migrator in schema public
          grant select, insert, update, delete on tables to care_app;
        alter default privileges for role care_migrator in schema public
          grant usage, select on sequences to care_app;
        """, database=target)
    migration = [
        "run", "--rm", "--no-deps", "-e", "CARE_DB_URL=jdbc:postgresql://postgres:5432/" + target,
        "migrate", "--spring.main.web-application-type=none", "--care.lifecycle.once=true",
    ]
    docker(*compose, *migration, "--spring.flyway.target=7")
    psql(compose, """
        insert into department(name) values ('Upgrade Clinic');
        insert into patient(reference, display_name) values ('LEGACY-P1','Synthetic Patient');
        insert into app_user(username, password_hash, display_name)
          values ('legacy-doctor','locked-test-fixture','Synthetic Doctor'), ('legacy-owner','locked-test-fixture','Synthetic Owner');
        insert into user_role select id, 'DOCTOR' from app_user where username = 'legacy-doctor';
        insert into user_role select id, 'FOLLOWUP' from app_user where username = 'legacy-owner';
        insert into doctor(user_id, department_id, display_name)
          select u.id, d.id, u.display_name from app_user u cross join department d where u.username = 'legacy-doctor';
        insert into schedule_slot(doctor_id, starts_at, ends_at, capacity, remaining)
          select id, now() + interval '1 day', now() + interval '25 hours', 2, 1 from doctor;
        insert into appointment(patient_id, slot_id, status)
          select p.id, s.id, 'BOOKED' from patient p cross join schedule_slot s;
        insert into care_episode(patient_id, owner_id, kind, reference, care_date)
          select p.id, u.id, 'DISCHARGE', 'LEGACY-D1', current_date
          from patient p cross join app_user u where u.username = 'legacy-owner';
        """, database=target)
    docker(*compose, *migration)
    result = psql(compose, """
        select a.status || ':' || s.remaining || ':' || d.name
          from appointment a join schedule_slot s on s.id = a.slot_id join department d on d.id = s.department_id;
        select count(*) from care_episode where department_id is not null and reference = 'LEGACY-D1';
        select count(*) from staff_department;
        """, database=target).splitlines()
    if result != ["BOOKED:1:Upgrade Clinic", "1", "2"]:
        raise RuntimeError("V7 data or department backfill differs from the fixture contract")
    print("V7 upgrade preserved the reservation, remaining seats, discharge and staff membership")


if __name__ == "__main__":
    main()
