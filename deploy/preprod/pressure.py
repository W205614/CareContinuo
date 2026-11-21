"""Prepare and run the repeatable load profile against a verified synthetic stack."""
import argparse
import concurrent.futures
import http.cookiejar
import json
import ssl
import time
import uuid
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, HTTPSHandler, Request, build_opener

from control import docker, psql, verify


def sign_in(origin, password, tls, index):
    cookies = http.cookiejar.CookieJar()
    opener = build_opener(HTTPSHandler(context=tls), HTTPCookieProcessor(cookies))

    def csrf():
        with opener.open(origin + "/api/v1/session/csrf", timeout=10) as response:
            return json.load(response)

    token = csrf()
    form = urlencode({"username": f"load-{index}", "password": password}).encode()
    with opener.open(Request(origin + "/api/v1/session/login", data=form,
                             headers={token["header"]: token["token"]}), timeout=10) as response:
        if response.status != 204:
            raise RuntimeError("Synthetic patient authentication failed")
    token = csrf()
    session = next(cookie for cookie in cookies if cookie.name == "SESSION")
    return {"cookie": session.name + "=" + session.value, "header": token["header"], "csrf": token["token"]}


def data(compose, patients, marker):
    return json.loads(psql(compose, """
        begin;
        with doctor_scope as (
          select d.id, d.department_id from doctor d join app_user u on u.id = d.user_id
          where u.username = 'doctor-a'
        ), slots as (
          insert into schedule_slot(doctor_id, department_id, starts_at, ends_at, capacity, remaining)
          select d.id, d.department_id, statement_timestamp() + interval '3 days' + i * interval '1 hour',
              statement_timestamp() + interval '3 days' + (i+1) * interval '1 hour', 500, 500
          from doctor_scope d cross join generate_series(1, 120) i
          returning id
        ), input as (
          select p.id, substring(p.reference from 5)::int as ordinal,
              owner.id as owner_id, d.department_id, r.id as reviewer_id
          from patient p cross join doctor_scope d
          join app_user owner on owner.username = 'followup-a' and owner.enabled
          join app_user r on r.username = 'reviewer' and r.enabled
          where p.reference ~ '^SYN-[0-9]+$' and substring(p.reference from 5)::int between 1 and :patients
        ), episode as (
          insert into care_episode(patient_id, owner_id, kind, reference, care_date, department_id)
          select i.id, i.owner_id, 'DISCHARGE', :'marker' || '-' || i.ordinal, current_date, i.department_id
          from input i returning id, patient_id
        ), task as (
          insert into followup_task(episode_id, owner_id, reviewer_id, status)
          select e.id, i.owner_id, i.reviewer_id, 'DISPATCHED'
          from episode e join input i on e.patient_id = i.id
          returning id, episode_id, reviewer_id
        ), revision as (
          insert into followup_revision(task_id, version_number, source_version, task_kind,
              title, instructions, due_at, manual_reference, author_id)
          select t.id, 1, 0, 'PENDING_RESULT',
              'Synthetic result', 'Confirm receipt', statement_timestamp() + interval '7 days',
              'Pressure test fixture', t.reviewer_id
          from task t returning id, task_id
        )
        select json_build_object(
          'department', (select department_id from doctor_scope),
          'slots', (select json_agg(id order by id) from slots),
          'tasks', (select json_agg(t.id order by i.ordinal) from task t join episode e on t.episode_id = e.id
            join input i on i.id = e.patient_id),
          'revisions', (select count(*) from revision));
        update followup_task t set published_revision_id = r.id
        from followup_revision r, care_episode e
        where r.task_id = t.id and t.episode_id = e.id and e.reference like :'marker' || '-%';
        commit;
        """, "-v", f"patients={patients}", "-v", f"marker={marker}"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--project", default="carecontinuo-preprod")
    parser.add_argument("--clients", type=int, default=200)
    parser.add_argument("--rate", type=int, default=100)
    parser.add_argument("--duration", default="30m")
    parser.add_argument("--report", default="load-report.json")
    args = parser.parse_args()
    if not 5 <= args.clients <= 300 or not 1 <= args.rate <= 300 or Path(args.report).name != args.report:
        parser.error("invalid synthetic load options")
    if not args.duration or any(char not in "0123456789hms" for char in args.duration):
        parser.error("use a k6 duration such as 30m or 2h")
    compose, origin = verify(args.env_file, args.project)
    directory = args.env_file.resolve().parent
    password = (directory / "patient_password").read_text()
    tls = ssl.create_default_context(cafile=directory / "tls_cert.pem")
    marker = "pressure-" + uuid.uuid4().hex
    fixture = data(compose, args.clients, marker)
    if len(fixture["tasks"]) != args.clients or fixture["revisions"] != args.clients or not fixture["slots"]:
        raise RuntimeError("Run control.py seed with at least the requested client count")
    sessions = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        for first in range(1, args.clients + 1, 8):
            started = time.monotonic()
            sessions.extend(pool.map(lambda i: sign_in(origin, password, tls, i),
                                     range(first, min(args.clients + 1, first + 8))))
            time.sleep(max(0.0, 0.35 - (time.monotonic() - started)))
    fixture.update({"sessions": sessions, "run": marker})
    with (directory / "load.json").open("w") as out:
        json.dump(fixture, out)
    reports = directory / "reports"
    reports.mkdir(exist_ok=True)
    start = psql(compose, "select clock_timestamp();")
    print(f"Load start {start}, {args.clients} authenticated patients, {args.rate} requests/s", flush=True)
    script = Path(__file__).with_name("load.js").resolve()
    load = docker("run", "--rm", "--network", args.project + "_browser", "--user", "0:0",
           "--mount", f"type=bind,source={directory / 'load.json'},target=/load.json,readonly",
           "--mount", f"type=bind,source={reports},target=/evidence",
           "--mount", f"type=bind,source={script},target=/test.js,readonly",
           "-e", f"CARE_LOAD_RPS={args.rate}", "-e", f"CARE_LOAD_DURATION={args.duration}",
           "-e", f"CARE_LOAD_REPORT=/evidence/{args.report}",
           "grafana/k6:2.4.0", "run", "--quiet", "--insecure-skip-tls-verify", "/test.js", check=False)
    report = psql(compose, """
        select json_build_object('delivered', count(*), 'p95Seconds',
          percentile_cont(0.95) within group (order by extract(epoch from (m.created_at - e.available_at))))
        from inbox_message m join outbox_event e on e.id = m.event_id
        where e.available_at >= :'started'::timestamptz and e.kind = 'APPOINTMENT_BOOKED';
        """, "-v", "started=" + start)
    delivery = json.loads(report)
    (reports / (Path(args.report).stem + "-delivery.json")).write_text(report, encoding="utf-8")
    print("Notification delivery " + report)
    if load.returncode != 0:
        raise SystemExit(load.returncode)
    if delivery["delivered"] == 0 or delivery["p95Seconds"] > 10:
        raise SystemExit("Notification delivery threshold not met")


if __name__ == "__main__":
    main()
