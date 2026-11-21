"""Seed, back up, or restore an isolated synthetic CareContinuo Compose project."""
import argparse
import hashlib
import json
import re
import subprocess
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from secrets import token_hex


def docker(*args, check=True, **options):
    return subprocess.run(["docker", *args], check=check, **options)


def compose_command(env_file, project):
    return ["compose", "-f", "compose.preprod.yaml", "--env-file", str(env_file), "-p", project]


def verify(env_file, project):
    if not re.fullmatch(r"carecontinuo-preprod(?:-[a-z0-9]+)*", project):
        raise ValueError("A dedicated carecontinuo-preprod project name is required")
    values = dict(line.split("=", 1) for line in env_file.read_text().splitlines() if line)
    port = values["CARE_PREPROD_PORT"]
    if values["CARE_PREPROD_ORIGIN"] != f"https://127.0.0.1:{port}":
        raise ValueError("Only a loopback HTTPS test target is allowed")
    compose = compose_command(env_file, project)
    for service in ("postgres", "web"):
        identifier = docker(*compose, "ps", "-q", service, capture_output=True, text=True).stdout.strip()
        if not identifier:
            raise RuntimeError(f"{service} is not running in the selected project")
        state = json.loads(docker("inspect", identifier, capture_output=True, text=True).stdout)[0]
        labels = state["Config"]["Labels"]
        if (labels.get("com.docker.compose.project") != project
                or labels.get("com.docker.compose.service") != service
                or labels.get("app.carecontinuo.preprod") != "synthetic" or not state["State"]["Running"]):
            raise RuntimeError("Expected a running synthetic preproduction stack")
        if service == "web":
            ports = state["NetworkSettings"]["Ports"]["8443/tcp"]
            if not any(bind["HostIp"] == "127.0.0.1" and bind["HostPort"] == port for bind in ports):
                raise RuntimeError("HTTPS port belongs to a different synthetic project")
    return compose, values["CARE_PREPROD_ORIGIN"]


def psql(compose, sql, *args, database="care_preprod"):
    result = docker(*compose, "exec", "-T", "postgres", "psql", "-XqAtv", "ON_ERROR_STOP=1",
                    "-U", "postgres", "-d", database, *args, input=sql.encode(), capture_output=True)
    return result.stdout.decode().strip()


def seed(compose, origin, directory, count):
    people = json.loads((directory / "staff.json").read_text())
    issuer = origin + "/id/realms/care"
    user_args = [
        "-e", "SPRING_PROFILES_ACTIVE=bootstrap",
        "-e", "SPRING_CONFIG_IMPORT=configtree:/run/secrets/",
        "-e", "CARE_BOOTSTRAP_USERNAME=administrator",
        "-e", "CARE_BOOTSTRAP_DISPLAY_NAME=Synthetic Administrator",
        "-e", "CARE_BOOTSTRAP_SUBJECT=" + people["administrator"]["id"],
    ]
    admins = psql(compose, "select count(*) from user_role where role = 'ADMIN';")
    if admins == "0":
        docker(*compose, "run", "--rm", "--no-deps", *user_args, "api",
               "--spring.main.web-application-type=none", "--spring.flyway.enabled=false", "--care.lifecycle.once=true")

    sql = [
        "begin;",
        "insert into department(name) values ('Synthetic A'), ('Synthetic B') on conflict (name) do nothing;",
    ]
    for username, role, department in (
        ("operator-a", "OPERATIONS", "A"), ("operator-b", "OPERATIONS", "B"),
        ("doctor-a", "DOCTOR", "A"), ("reviewer", "REVIEWER", "A"),
        ("followup-a", "FOLLOWUP", "A"), ("followup-b", "FOLLOWUP", "A")
    ):
        subject = str(uuid.UUID(people[username]["id"]))
        sql.append(f"""
            with person as (
              insert into app_user(username, display_name, oidc_issuer, oidc_subject)
              values ('{username}','{username}', :'issuer','{subject}') on conflict(username) do nothing returning id
            ), roles as (insert into user_role select id, '{role}' from person)
            insert into staff_department select person.id, d.id
            from person cross join department d where d.name = 'Synthetic {department}';
        """)
    sql.append(f"""
        insert into doctor(user_id, department_id, display_name)
        select u.id, sd.department_id, u.display_name from app_user u join staff_department sd on sd.user_id = u.id
        where u.username = 'doctor-a' on conflict(user_id) do nothing;
        insert into patient(reference, display_name)
        select 'SYN-' || i, 'Synthetic Patient ' || i from generate_series(1, {count}) i
        on conflict(reference) do nothing;
        with password as materialized (select crypt(:'password', gen_salt('bf', 10)) as hash),
        users as (
          insert into app_user(username, display_name, patient_id, password_hash)
          select 'load-' || substring(reference from 5), display_name, id, hash
          from patient cross join password where reference like 'SYN-%'
          on conflict(username) do nothing returning id
        )
        insert into user_role select id, 'PATIENT' from users;
        commit;
    """)
    psql(compose, chr(10).join(sql), "-v", "issuer=" + issuer,
         "-v", "password=" + (directory / "patient_password").read_text())
    print(f"Synthetic identity fixtures ready, {count} requested patient accounts")


def business_counts(compose, database="care_preprod"):
    return psql(compose, """
        select json_build_object('patients', (select count(*) from patient),
          'appointments', (select count(*) from appointment), 'followups', (select count(*) from followup_task),
          'versions', (select count(*) from document_version), 'messages', (select count(*) from inbox_message),
          'audit', (select count(*) from audit_log), 'old_schema', (select max(installed_rank) from flyway_schema_history));
        """, database=database)


def backup(compose, path):
    before = datetime.now(timezone.utc)
    with path.open("xb") as output:
        docker(*compose, "exec", "-T", "postgres", "pg_dump", "-U", "postgres", "-d", "care_preprod", "-Fc",
               "--exclude-table-data=spring_session*", stdout=output)
    with path.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    metadata = {"sha256": digest, "before": before.isoformat(), "completed": datetime.now(timezone.utc).isoformat()}
    with path.with_suffix(".json").open("x") as output:
        json.dump(metadata, output)
    print(json.dumps(metadata))


def restore(compose, path):
    metadata = json.loads(path.with_suffix(".json").read_text())
    with path.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    if digest != metadata["sha256"]:
        raise RuntimeError("Backup checksum does not match")
    started = time.monotonic()
    target = "care_restore_" + token_hex(4)
    docker(*compose, "exec", "-T", "postgres", "createdb", "-U", "postgres", target)
    with path.open("rb") as source:
        docker(*compose, "exec", "-T", "postgres", "pg_restore", "--exit-on-error",
               "--single-transaction", "-U", "postgres", "-d", target, stdin=source)
    rows = json.loads(business_counts(compose, target))
    lost_window = datetime.now(timezone.utc) - datetime.fromisoformat(metadata["before"])
    print(json.dumps({"database": target, "restoreSeconds": round(time.monotonic() - started, 3),
                      "backupAgeSeconds": round(lost_window.total_seconds(), 3), "counts": rows}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--project", default="carecontinuo-preprod")
    commands = parser.add_subparsers(dest="operation", required=True)
    commands.add_parser("verify")
    seed_command = commands.add_parser("seed")
    seed_command.add_argument("--patients", type=int, default=320)
    for operation in ("backup", "restore"):
        command = commands.add_parser(operation)
        command.add_argument("archive", type=Path)
    args = parser.parse_args()
    compose, origin = verify(args.env_file, args.project)
    if args.operation == "seed":
        if not 1 <= args.patients <= 5000:
            parser.error("patients must be between 1 and 5000")
        seed(compose, origin, args.env_file.parent, args.patients)
    elif args.operation == "backup":
        backup(compose, args.archive)
    elif args.operation == "restore":
        restore(compose, args.archive)


if __name__ == "__main__":
    main()
