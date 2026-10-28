#!/usr/bin/env python3
"""One E2E entry point; no third-party Python dependencies. Run from any directory."""
import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import time
import traceback
import unittest
import uuid
import xml.etree.ElementTree as ET

import cases
import python_inventory
from support import Api

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent


def catalog():
    entries = json.loads((HERE / "catalog.json").read_text(encoding="utf-8"))["cases"]
    ids, tests = set(), set()
    for entry in entries:
        assert entry["id"] not in ids and entry["test"] not in tests, "duplicate case"
        assert entry["suite"] in ("smoke", "regression", "contract")
        assert entry["owner"] and entry["cap"]
        cls, method = entry["test"].split(".")
        assert callable(getattr(getattr(cases, cls), method))
        ids.add(entry["id"])
        tests.add(entry["test"])
    discovered = {f"{cls.__name__}.{method}" for cls in (cases.Smoke, cases.Regression, cases.CopilotContract)
                  for method in unittest.defaultTestLoader.getTestCaseNames(cls)}
    assert discovered == tests, "every official test must be registered"
    legacy = json.loads((HERE / "legacy.json").read_text(encoding="utf-8"))
    actual = {p.relative_to(ROOT / "tests").as_posix()
              for p in (ROOT / "tests/legacy").rglob("*")
              if p.is_file() and p.suffix in (".py", ".mjs", ".sh", ".ps1")}
    assert actual == set(legacy), "legacy inventory changed: update tests/e2e/legacy.json"
    assert python_inventory.OUT.read_text(encoding="utf-8") == python_inventory.render(), "Python inventory stale; regenerate with python_inventory.py --write"
    return entries


def select_entries(entries, suite):
    selected = [e for e in entries if e["suite"] == suite
                or (suite == "regression" and e["suite"] == "smoke")]
    if not selected:
        raise ValueError("selected suite contains no cases")
    return selected


def stop_tree(proc):
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"], capture_output=True)
    else:
        try:
            os.killpg(proc.pid, signal.SIGTERM)
            proc.wait(timeout=10)
        except ProcessLookupError:
            pass
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid, signal.SIGKILL)
    proc.wait(timeout=15)


@contextmanager
def isolated_app(folder):
    # Refuse occupied ports; never kill somebody else's process.
    port = int(os.environ.get("E2E_PORT", "18120"))
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", port))
    data = folder / "data"
    data.mkdir()
    env = os.environ.copy()
    # Configuration lives outside the repo and explicitly replaces config search paths.
    config = {"server.address": "127.0.0.1", "server.port": port,
              "spring.profiles.active": "e2e", "spring.datasource.driver-class-name": "org.h2.Driver",
              "spring.datasource.url": f"jdbc:h2:file:{data.as_posix()}/db/devmind",
              "spring.datasource.username": "sa", "spring.datasource.password": "",
              "devmind.auth.jwt-secret": uuid.uuid4().hex + uuid.uuid4().hex,
              "devmind.auth.admin-password": "e2e-" + uuid.uuid4().hex,
              "devmind.project.workspace-root": str(data / "repositories"),
              "devmind.worktree.root": str(data / "worktrees"),
              "devmind.attachment.root-dir": str(data / "attachments"),
              "devmind.worklog.repo-root": str(data / "repos"),
              "devmind.worklog.daily-enabled": False, "devmind.worklog.weekly-enabled": False,
              "devmind.worklog.git-import-enabled": False}
    env["SPRING_APPLICATION_JSON"] = json.dumps(config)
    env["SPRING_CONFIG_LOCATION"] = "classpath:/application.yml"
    mvn = shutil.which("mvn")
    if not mvn:
        raise RuntimeError("Maven missing; build first: mvn -q install -DskipTests")
    command = [mvn, "-q", "-pl", "devmind-app", "spring-boot:run", "-Dspring-boot.run.profiles=e2e"]
    with (folder / "app.log").open("w", encoding="utf-8") as log:
        proc = subprocess.Popen(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
                                start_new_session=os.name != "nt")
        try:
            api = Api(f"http://127.0.0.1:{port}/api")
            deadline = time.monotonic() + 180
            while True:
                if proc.poll() is not None:
                    raise RuntimeError("App exited; see app.log")
                try:
                    api.request("GET", "/health")
                    break
                except (OSError, AssertionError):
                    if time.monotonic() > deadline:
                        raise RuntimeError("App startup timeout; see app.log")
                    time.sleep(1)
            api.login("admin", config["devmind.auth.admin-password"])
            yield api
        finally:
            stop_tree(proc)


class Result(unittest.TextTestResult):
    def __init__(self, *args):
        super().__init__(*args)
        self.records = []

    def startTest(self, test):
        self.started = time.monotonic()
        self.outcome, self.detail = "pass", ""
        super().startTest(test)

    def addFailure(self, test, err):
        self.outcome, self.detail = "failure", self._exc_info_to_string(err, test)
        super().addFailure(test, err)

    def addError(self, test, err):
        self.outcome, self.detail = "error", self._exc_info_to_string(err, test)
        super().addError(test, err)

    def addSkip(self, test, reason):
        self.outcome, self.detail = "skipped", reason
        super().addSkip(test, reason)

    def stopTest(self, test):
        self.records.append((test.id(), self.outcome, self.detail, time.monotonic() - self.started))
        super().stopTest(test)


def write_report(path, records):
    counts = {key: sum(r[1] == key for r in records) for key in ("failure", "error", "skipped")}
    xml = ET.Element("testsuite", name="devmind-e2e", tests=str(len(records)),
                     failures=str(counts["failure"]), errors=str(counts["error"]),
                     skipped=str(counts["skipped"]), time=str(sum(r[3] for r in records)))
    for name, outcome, detail, elapsed in records:
        cls, _, method = name.rpartition(".")
        case = ET.SubElement(xml, "testcase", classname=cls, name=method, time=f"{elapsed:.3f}")
        if outcome != "pass":
            ET.SubElement(case, outcome, message=detail.splitlines()[-1] if detail else outcome).text = detail
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(xml).write(path, encoding="utf-8", xml_declaration=True)


def execute(api, entries, folder):
    cases.Smoke.api = cases.Regression.api = cases.CopilotContract.api = api
    cases.Regression.workspace = cases.CopilotContract.workspace = folder
    suite = unittest.TestSuite()
    for entry in entries:
        cls, method = entry["test"].split(".")
        suite.addTest(getattr(cases, cls)(method))
    result = unittest.TextTestRunner(verbosity=2, resultclass=Result).run(suite)
    return result.records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", choices=["smoke", "regression", "contract"], default="smoke")
    parser.add_argument("--isolated", action="store_true", help="start a private H2 app (requires built modules)")
    parser.add_argument("--base", default=os.environ.get("E2E_BASE"))
    parser.add_argument("--insecure", action="store_true", help="explicitly allow a self-signed HTTPS certificate")
    parser.add_argument("--check", action="store_true", help="validate case registration and legacy inventory")
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--junit", default="tmp/e2e/junit.xml")
    args = parser.parse_args()
    report = (ROOT / args.junit).resolve()
    if not report.is_relative_to(ROOT / "tmp"):
        parser.error("reports must be inside repository tmp/")
    if args.check or args.list:
        entries = catalog()
        print("\n".join(f'{e["id"]} [{e["owner"]}] {e["cap"]}' for e in entries) if args.list else "catalog OK")
        return 0
    records = []
    try:
        entries = select_entries(catalog(), args.suite)
        if args.suite == "regression" and not args.isolated:
            raise ValueError("regression requires --isolated; shared targets are smoke-only")
        if args.isolated and args.base:
            raise ValueError("--isolated cannot be combined with --base / E2E_BASE")
        folder = ROOT / "tmp/e2e" / (time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8])
        folder.mkdir(parents=True)
        if args.isolated:
            with isolated_app(folder) as api:
                records = execute(api, entries, folder)
        else:
            if not args.base:
                raise ValueError("set --base / E2E_BASE or use --isolated; no implicit shared target")
            api = Api(args.base, os.environ.get("E2E_TOKEN", ""), args.insecure)
            if not api.token:
                password = os.environ.get("E2E_PASSWORD")
                if not password:
                    raise ValueError("set E2E_TOKEN or E2E_USER + E2E_PASSWORD")
                api.login(os.environ.get("E2E_USER", "admin"), password)
            records = execute(api, entries, folder)
    except (Exception, KeyboardInterrupt):
        records.append(("e2e.infrastructure", "error", traceback.format_exc(), 0))
    finally:
        write_report(report, records)
        print(f"JUnit: {report}")
    return 1 if not records or any(r[1] in ("failure", "error") for r in records) else 0


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
