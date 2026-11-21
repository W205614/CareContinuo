"""Scan the tracked source snapshot and the three locally built application images."""
import argparse
import json
import subprocess
import tarfile
from pathlib import Path

from control import docker


def scan(command, name, directory):
    report = directory / (name + ".json")
    docker("run", "--rm", "-v", "carecontinuo-trivy-cache:/root/.cache/trivy",
           "--mount", f"type=bind,source={directory},target=/reports",
           "aquasec/trivy:0.72.0", *command,
           "--severity", "HIGH,CRITICAL", "--format", "json", "--output", "/reports/" + report.name)
    data = json.loads(report.read_text(encoding="utf-8"))
    counts = {"high": 0, "critical": 0, "secrets": 0}
    for result in data.get("Results", []):
        for vulnerability in result.get("Vulnerabilities") or []:
            counts[vulnerability["Severity"].lower()] += 1
        counts["secrets"] += len(result.get("Secrets") or [])
    print(name, counts, flush=True)
    return sum(counts.values())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    directory = args.directory.resolve()
    directory.mkdir(parents=True, exist_ok=True)
    source = directory / "source"
    source.mkdir()
    archive = directory / "source.tar"
    subprocess.run(["git", "archive", "--format=tar", "-o", str(archive), "HEAD"], check=True)
    with tarfile.open(archive) as staged:
        staged.extractall(source, filter="data")
    findings = scan(["fs", "--scanners", "vuln,secret", "/reports/source"], "repository", directory)
    for service in ("api", "web", "agent"):
        image = "carecontinuo-preprod-" + service + ":local"
        archive = directory / (service + ".tar")
        docker("image", "save", "-o", str(archive), image)
        findings += scan(["image", "--scanners", "vuln,secret", "--input", "/reports/" + archive.name],
                         service, directory)
    if findings:
        raise SystemExit(f"{findings} high/critical or secret findings require triage")


if __name__ == "__main__":
    main()
