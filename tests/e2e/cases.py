"""Official cases: independent fixtures, explicit assertions, cleanup registered first."""
import io
import os
import shutil
import signal
import unittest
import urllib.parse
import uuid
import subprocess
import json
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class Smoke(unittest.TestCase):
    api = None

    def test_health(self):
        self.assertEqual("UP", self.api.request("GET", "/health")["status"])

    def test_anonymous_rejected(self):
        self.api.request("GET", "/auth/me", expected=401, authenticated=False)

    def test_identity(self):
        self.assertTrue(self.api.request("GET", "/auth/me")["username"])

    def test_projects(self):
        self.assertIsInstance(self.api.request("GET", "/projects"), list)

    def test_changelog(self):
        page = self.api.request("GET", "/changelog?page=0&size=1")
        self.assertIsInstance(page["items"], list)


class Regression(unittest.TestCase):
    api = None
    workspace = None

    def test_menu_grants_drive_visible_menu(self):
        from support import Api
        catalog = self.api.request("GET", "/auth/menus/catalog")
        keys = {item["menuKey"] for item in catalog}
        self.assertIn("admin.users", keys)
        self.assertIn("project.tests", keys)
        original = self.api.request("GET", "/auth/menus/grants")["grants"]["DEVELOPER"]
        self.addCleanup(self.api.request, "PUT", "/auth/menus/grants",
                        {"role": "DEVELOPER", "menuKeys": original})
        username = "e2e-menu-" + uuid.uuid4().hex[:10]
        password = "e2e-" + uuid.uuid4().hex
        user = self.api.request("POST", "/auth/users", {"username": username, "password": password,
                                                        "role": "DEVELOPER"})
        self.addCleanup(self.api.request, "DELETE", "/auth/users/" + user["id"])
        client = Api(self.api.base)
        client.login(username, password)

        visible = client.request("GET", "/auth/menus")
        initial = {m["menuKey"] for m in visible["items"]}
        self.assertIn("top.home", initial)
        self.assertNotIn("admin.dashboard", initial)
        self.assertIn("/home", visible["knownPaths"])
        client.request("GET", "/auth/menus/catalog", expected=403)
        client.request("PUT", "/auth/menus/grants", {"role": "VIEWER", "menuKeys": []}, expected=403)

        # 子节点授权隐含祖先（CAP-103），撤下父节点必须先撤掉其全部后代
        updated = [k for k in original
                   if k != "top.home" and not k.startswith("top.home.")
                   and k != "project.tests" and not k.startswith("project.tests.")]
        updated.append("admin.dashboard")
        self.api.request("PUT", "/auth/menus/grants", {"role": "DEVELOPER", "menuKeys": updated})
        after = {m["menuKey"] for m in client.request("GET", "/auth/menus")["items"]}
        self.assertNotIn("top.home", after)
        self.assertNotIn("top.home.tab.overview", after)
        self.assertNotIn("project.tests", after)
        self.assertNotIn("project.tests.tab.suites", after)
        self.assertIn("admin.dashboard", after)
        self.assertIn("admin.dashboard",
                      self.api.request("GET", "/auth/menus/grants")["grants"]["DEVELOPER"])
        admin_keys = {m["menuKey"] for m in self.api.request("GET", "/auth/menus")["items"]}
        self.assertIn("admin.users", admin_keys)
        self.assertNotIn("admin.menus", admin_keys)

        self.api.request("PUT", "/auth/menus/grants", {"role": "ADMIN", "menuKeys": []}, expected=400)
        self.api.request("PUT", "/auth/menus/grants", {"role": "DEVELOPER", "menuKeys": ["not.exists"]},
                         expected=400)

    def _ensure_user_absent(self, username):
        for user in self.api.request("GET", "/auth/users"):
            if user["username"] == username:
                self.api.request("DELETE", "/auth/users/" + user["id"])

    def _ensure_role_absent(self, code):
        if code in {role["code"] for role in self.api.request("GET", "/auth/roles")}:
            self.api.request("DELETE", "/auth/roles/" + code)

    def test_role_management_drives_user_permissions(self):
        from support import Api
        code = "E2E_" + uuid.uuid4().hex[:8].upper()
        name = "E2E 只读角色"
        role = self.api.request("POST", "/auth/roles", {"code": code.lower(), "name": name,
                                                       "description": "临时角色", "baseRole": "VIEWER"})
        self.addCleanup(self._ensure_role_absent, code)
        self.assertEqual(code, role["code"])
        self.assertFalse(role["builtin"])
        self.assertEqual("VIEWER", role["baseRole"])
        self.assertEqual(0, role["userCount"])
        self.assertIn("top.home", role["menuKeys"])
        self.assertIn("project.tests", role["menuKeys"])
        self.assertFalse([key for key in role["menuKeys"] if key.startswith("admin.")])

        self.api.request("POST", "/auth/roles", {"code": code, "name": name, "baseRole": "VIEWER"},
                         expected=409)
        self.api.request("POST", "/auth/roles", {"code": "E2E_BAD_" + uuid.uuid4().hex[:6].upper(),
                                                 "name": "x", "baseRole": "ADMIN"}, expected=400)

        username = "e2e-role-" + uuid.uuid4().hex[:10]
        password = "e2e-" + uuid.uuid4().hex
        user = self.api.request("POST", "/auth/users", {"username": username, "password": password,
                                                        "role": code})
        self.addCleanup(self._ensure_user_absent, username)
        self.assertEqual(code, user["roleCode"])
        self.assertEqual(name, user["roleName"])
        self.assertEqual("VIEWER", user["role"])

        client = Api(self.api.base)
        client.login(username, password)
        client.request("GET", "/auth/roles", expected=403)
        client.request("GET", "/auth/users", expected=403)
        client.request("GET", "/auth/menus/catalog", expected=403)
        client.request("PUT", "/auth/menus/grants", {"role": code, "menuKeys": []}, expected=403)

        initial = {m["menuKey"] for m in client.request("GET", "/auth/menus")["items"]}
        self.assertIn("top.home", initial)
        self.assertNotIn("admin.dashboard", initial)

        self.api.request("PUT", "/auth/menus/grants",
                         {"role": code, "menuKeys": ["top.home", "admin.dashboard"]})
        after = {m["menuKey"] for m in client.request("GET", "/auth/menus")["items"]}
        self.assertEqual({"top.home", "admin.dashboard"}, after)

        updated = self.api.request("PUT", "/auth/roles/" + code,
                                   {"name": name, "baseRole": "DEVELOPER"})
        self.assertEqual("DEVELOPER", updated["baseRole"])
        self.assertEqual(1, updated["userCount"])

        client.login(username, password)
        me = client.request("GET", "/auth/me")
        self.assertEqual("DEVELOPER", me["role"])
        self.assertEqual(code, me["roleCode"])
        self.assertEqual(name, me["roleName"])

        self.api.request("DELETE", "/auth/roles/" + code, expected=400)
        self.api.request("DELETE", "/auth/users/" + user["id"])
        self.api.request("DELETE", "/auth/roles/" + code)
        self.assertNotIn(code, {role["code"] for role in self.api.request("GET", "/auth/roles")})
        self.assertNotIn(code, self.api.request("GET", "/auth/menus/grants")["roles"])

        self.api.request("PUT", "/auth/roles/ADMIN", {"name": "x"}, expected=400)
        self.api.request("DELETE", "/auth/roles/ADMIN", expected=400)

    def test_fine_grained_menu_tab_action_permissions(self):
        from support import Api
        catalog = {item["menuKey"]: item for item in self.api.request("GET", "/auth/menus/catalog")}
        self.assertEqual("MENU", catalog["top.bookmarks"]["nodeType"])
        self.assertEqual("TAB", catalog["top.bookmarks.tab.tags"]["nodeType"])
        self.assertEqual("top.bookmarks", catalog["top.bookmarks.tab.tags"]["parentKey"])
        self.assertEqual("ACTION", catalog["top.bookmarks.action.manage-bookmark"]["nodeType"])
        self.assertTrue(catalog["top.bookmarks.action.manage-bookmark"]["writeAction"])

        code = "E2E_" + uuid.uuid4().hex[:8].upper()
        role = self.api.request("POST", "/auth/roles", {"code": code.lower(), "name": "E2E 细粒度权限",
                                                       "baseRole": "DEVELOPER"})
        self.addCleanup(self._ensure_role_absent, code)
        self.assertEqual("DEVELOPER", role["baseRole"])
        username = "e2e-perm-" + uuid.uuid4().hex[:10]
        password = "e2e-" + uuid.uuid4().hex
        self.api.request("POST", "/auth/users", {"username": username, "password": password, "role": code})
        self.addCleanup(self._ensure_user_absent, username)
        client = Api(self.api.base)
        client.login(username, password)

        # 只授予一个页签：祖先菜单自动补齐，兄弟页签与按钮均不可见
        self.api.request("PUT", "/auth/menus/grants",
                         {"role": code, "menuKeys": ["top.bookmarks.tab.tags"]})
        visible = {m["menuKey"]: m for m in client.request("GET", "/auth/menus")["items"]}
        self.assertEqual({"top.bookmarks", "top.bookmarks.tab.tags"}, set(visible))
        self.assertEqual("TAB", visible["top.bookmarks.tab.tags"]["nodeType"])
        self.assertNotIn("top.bookmarks.tab.shares", visible)
        self.assertNotIn("top.bookmarks.action.manage-bookmark", visible)

        # 未授予按钮权限：写接口被后端拦截
        client.request("POST", "/bookmark-groups", {"name": "e2e-denied"}, expected=403)
        client.request("POST", "/bookmark-tags", {"name": "e2e-denied"}, expected=403)

        # 授予 manage-group 后通过权限层（空体落到参数校验 400），兄弟按钮仍被拦截
        self.api.request("PUT", "/auth/menus/grants",
                         {"role": code, "menuKeys": ["top.bookmarks.tab.tags",
                                                     "top.bookmarks.action.manage-group"]})
        visible = {m["menuKey"] for m in client.request("GET", "/auth/menus")["items"]}
        self.assertEqual({"top.bookmarks", "top.bookmarks.tab.tags",
                          "top.bookmarks.action.manage-group"}, visible)
        client.request("POST", "/bookmark-groups", {}, expected=400)
        client.request("POST", "/bookmark-tags", {"name": "e2e-denied"}, expected=403)

    def test_project_lifecycle(self):
        path = self.workspace / ("project-" + uuid.uuid4().hex)
        path.mkdir()
        subprocess.run(["git", "init", "--initial-branch=master", str(path)], check=True, capture_output=True)
        body = {"name": "e2e-project", "path": path.as_posix(), "defaultBranch": "master",
                "tags": ["e2e"], "description": "temporary fixture", "status": "ACTIVE"}
        project = self.api.request("POST", "/projects", body)
        route = "/projects/" + str(project["id"])
        self.addCleanup(self.api.request, "DELETE", route)
        self.assertEqual(body["name"], self.api.request("GET", route)["name"])
        self.api.request("POST", "/projects", body, expected=409)
        body["name"] = "e2e-project-updated"
        self.assertEqual(body["name"], self.api.request("PUT", route, body)["name"])

    # ---------------- CAP-104 测试平台 MVP ----------------

    def _cap104_project(self):
        path = self.workspace / ("project-" + uuid.uuid4().hex)
        path.mkdir()
        subprocess.run(["git", "init", "--initial-branch=master", str(path)],
                       check=True, capture_output=True)
        project = self.api.request("POST", "/projects", {
            "name": "e2e-test-" + uuid.uuid4().hex[:8], "path": path.as_posix(),
            "defaultBranch": "master", "tags": ["e2e"], "status": "ACTIVE"})
        self.addCleanup(self.api.request, "DELETE", "/projects/" + project["id"])
        return project["id"]

    def _cap104_suite(self, project_id):
        module = self.api.request("POST", f"/projects/{project_id}/test-modules", {
            "name": "e2e-module-" + uuid.uuid4().hex[:8], "description": "e2e",
            "tags": "e2e", "owner": "e2e", "sortNo": 1})
        self.addCleanup(self.api.request, "DELETE", "/test-modules/" + str(module["id"]))
        suite = self.api.request("POST", f"/projects/{project_id}/test-suites", {
            "name": "e2e-suite-" + uuid.uuid4().hex[:8], "kind": "api",
            "moduleId": module["id"], "owner": "e2e", "tags": "api"})
        self.addCleanup(self.api.request, "DELETE", "/test-suites/" + str(suite["id"]))
        return module, suite

    def _cap104_http_spec(self, case_key, expected_status="200"):
        return {"caseKey": case_key, "title": "健康检查", "type": "HTTP", "priority": "P1",
                "tags": ["e2e"],
                "assertions": [
                    {"source": "HTTP_STATUS", "op": "EQUALS", "expected": expected_status},
                    {"source": "JSON_PATH", "selector": "$.status", "op": "EQUALS", "expected": "UP"}],
                "executor": {"location": "SERVER", "timeoutSec": 20},
                "http": {"method": "GET", "path": "/health"},
                "source": {"kind": "MANUAL", "note": "e2e"}}

    def _cap104_wait_run(self, run_id):
        deadline = time.monotonic() + 60
        while True:
            run = self.api.request("GET", f"/test-runs/{run_id}")
            if run["status"] not in ("QUEUED", "RUNNING"):
                return run
            self.assertLess(time.monotonic(), deadline, "CAP-104 run did not finish in 60s")
            time.sleep(1)

    def _cap104_approved_case(self, suite_path, spec):
        saved = self.api.request("POST", suite_path + "/test-cases",
                                 {"changeNote": "e2e", "spec": spec})
        case = saved["testCase"]
        self.addCleanup(self.api.request, "DELETE", "/test-cases/" + str(case["id"]))
        self.api.request("POST", f"/test-cases/{case['id']}/review",
                         {"reviewStatus": "APPROVED", "note": "e2e approve"})
        return case

    def _cap104_environment(self, project_id, node_ids=None, name="TEST"):
        environment = self.api.request("POST", f"/projects/{project_id}/environments", {
            "name": name, "nodeIds": node_ids or [],
            "variables": {"baseUrl": self.api.base}, "productVersion": "1.0.0",
            "precheckJson": json.dumps({"config": {"expectedVersion": "1.0.0"}})})
        self.addCleanup(self.api.request, "DELETE",
                        f"/projects/{project_id}/environments/{environment['id']}")
        return environment

    def _cap104_register_plan(self, plan):
        state = {"deleted": False}

        def cleanup():
            if not state["deleted"]:
                self.api.request("DELETE", f"/test-plans/{plan['id']}")
                state["deleted"] = True

        self.addCleanup(cleanup)
        return plan, state

    def _cap104_plan(self, project_id, body):
        return self._cap104_register_plan(
            self.api.request("POST", f"/projects/{project_id}/test-plans", body))

    def _cap104_copy_plan(self, plan_id):
        return self._cap104_register_plan(
            self.api.request("POST", f"/test-plans/{plan_id}/copy", {}))

    def _cap104_delete_plan(self, plan_id, state):
        if state["deleted"]:
            return
        self.api.request("DELETE", f"/test-plans/{plan_id}")
        state["deleted"] = True

    def _cap104_baseline(self, plan_run_id, name):
        baseline = self.api.request("POST", f"/test-plan-runs/{plan_run_id}/baselines",
                                    {"name": name, "productVersion": "1.0.0"})
        state = {"deleted": False}

        def cleanup():
            if not state["deleted"]:
                self.api.request("DELETE", f"/test-baselines/{baseline['id']}")
                state["deleted"] = True

        self.addCleanup(cleanup)
        return baseline, state

    def _cap104_wait_plan_run(self, plan_run_id, timeout=240):
        deadline = time.monotonic() + timeout
        while True:
            run = self.api.request("GET", f"/test-plan-runs/{plan_run_id}")
            if run["status"] not in ("QUEUED", "RUNNING"):
                return run
            self.assertLess(time.monotonic(), deadline, "CAP-104 plan run did not finish in 240s")
            time.sleep(1)

    @staticmethod
    def _cap104_java_hash(value):
        h = 0
        for ch in value:
            h = (31 * h + ord(ch)) & 0xFFFFFFFF
        return h - 0x100000000 if h >= 0x80000000 else h

    @staticmethod
    def _cap104_zip_entries(blob):
        with zipfile.ZipFile(io.BytesIO(blob)) as archive:
            if archive.testzip() is not None:
                raise AssertionError("evidence zip contains a corrupt entry")
            return {name: archive.read(name) for name in archive.namelist()}

    def _cap104_issue_node(self, name):
        issued = self.api.request("POST", "/agent-nodes", {"name": name, "labels": "e2e"})
        node_id = str(issued["node"]["id"])
        state = {"deleted": False}

        def cleanup():
            if not state["deleted"]:
                self.api.request("DELETE", "/agent-nodes/" + node_id)
                state["deleted"] = True

        self.addCleanup(cleanup)
        return node_id, issued["token"]

    def _cap104_create_node(self, name):
        node_id, _ = self._cap104_issue_node(name)
        return node_id

    def _cap104_start_runner(self, name):
        node_id, token = self._cap104_issue_node(name)
        folder = self.workspace / ("runner-" + uuid.uuid4().hex[:8])
        folder.mkdir()
        jar_source = ROOT / "devmind-agent-runner" / "target" / "devmind-agent-runner.jar"
        self.assertTrue(jar_source.is_file(),
                        "runner jar missing; build with mvn -q install -DskipTests")
        jar = folder / "devmind-agent-runner.jar"
        shutil.copy2(jar_source, jar)
        java = shutil.which("java")
        bash = shutil.which("bash")
        self.assertTrue(java, "java not found on PATH")
        self.assertTrue(bash, "bash not found on PATH")
        parsed = urllib.parse.urlsplit(self.api.base)
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        ws_scheme = "wss" if parsed.scheme == "https" else "ws"
        config = folder / "agent.properties"
        config.write_text("\n".join([
            f"serverUrl={ws_scheme}://{parsed.hostname}:{port}/ws/agent",
            f"token={token}",
            "executor=fake",
            "execAllowlist=bash",
            f"execShell={Path(bash).as_posix()}",
            f"workDir={(folder / 'work').as_posix()}",
            f"workspaceRoot={(folder / 'workspaces').as_posix()}",
            f"worklogRoot={(folder / 'worklog').as_posix()}",
            "gitProxyEnabled=false",
            "maxConcurrent=4",
            "gcInitialDelayMinutes=600",
            "gcIntervalMinutes=600",
            "partialMessages=false",
        ]) + "\n", encoding="utf-8")
        env = os.environ.copy()
        env["PATH"] = str(Path(bash).parent) + os.pathsep + env.get("PATH", "")
        log = (folder / "runner.log").open("w", encoding="utf-8")
        proc = subprocess.Popen([java, "-jar", str(jar), str(config)], cwd=folder, env=env,
                                stdout=log, stderr=subprocess.STDOUT,
                                start_new_session=os.name != "nt")
        stopped = {"value": False}

        def stop():
            if stopped["value"]:
                return
            stopped["value"] = True
            try:
                if os.name == "nt":
                    subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"],
                                   capture_output=True)
                    proc.wait(timeout=15)
                else:
                    try:
                        os.killpg(proc.pid, signal.SIGTERM)
                        proc.wait(timeout=10)
                    except ProcessLookupError:
                        pass
                    except subprocess.TimeoutExpired:
                        os.killpg(proc.pid, signal.SIGKILL)
                        proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                proc.kill()
            finally:
                log.close()

        self.addCleanup(stop)
        deadline = time.monotonic() + 90
        while True:
            nodes = self.api.request("GET", "/agent-nodes")
            node = next((n for n in nodes if str(n["id"]) == node_id), None)
            if node and node.get("status") == "ONLINE" and (node.get("protocolVersion") or 0) >= 24:
                return node_id
            self.assertIsNone(proc.poll(), "runner exited before ONLINE; see tmp runner.log")
            self.assertLess(time.monotonic(), deadline, "runner did not reach ONLINE within 90s")
            time.sleep(1)

    @staticmethod
    def _cap104_program_zip():
        manifest = {"contractVersion": "1", "name": "e2e-program", "version": "1.0.0",
                    "runtime": "SHELL", "entrypoint": "run.sh",
                    "targetOs": "ANY", "targetArch": "ANY", "description": "e2e program"}
        script = """#!/usr/bin/env bash
set -uo pipefail
test -n "${DEVMIND_INPUT:-}" || exit 3
test -n "${DEVMIND_RESULT:-}" || exit 4
test -n "${DEVMIND_ARTIFACTS:-}" || exit 5
if grep -q '"apiToken"' "$DEVMIND_INPUT"; then exit 9; fi
if ! grep -q '"visibleParam":"e2e"' "$DEVMIND_INPUT"; then exit 7; fi
if [ -z "${apiToken:-}" ]; then exit 8; fi
echo "e2e program ran"
mkdir -p "$DEVMIND_ARTIFACTS"
printf 'artifact-ok' > "$DEVMIND_ARTIFACTS/report.txt"
printf '%s' '{"contractVersion":"1","status":"pass","message":"e2e program pass","metrics":{"checked":2},"assertions":[{"name":"input-filtered","pass":true,"expected":"no apiToken","actual":"no apiToken"}]}' > "$DEVMIND_RESULT"
"""
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr(".devmind-program.json", json.dumps(manifest))
            archive.writestr("run.sh", script)
        return buffer.getvalue()

    @staticmethod
    def _cap104_program_spec(case_key, asset_id):
        return {"caseKey": case_key, "title": "程序型用例 " + case_key, "type": "COMMAND",
                "priority": "P1", "tags": ["e2e", "program"],
                "assertions": [{"source": "EXIT_CODE", "op": "EQUALS", "expected": "0"}],
                "executor": {"location": "NODE", "timeoutSec": 120},
                "command": {"command": "bash run.sh", "contractVersion": "1"},
                "source": {"kind": "ASSET", "assetId": asset_id, "note": "e2e program"}}

    def test_case_version_review_run_and_evidence(self):
        from support import Api
        project_id = self._cap104_project()
        module, suite = self._cap104_suite(project_id)
        suite_path = "/test-suites/" + str(suite["id"])

        updated = self.api.request("PUT", "/test-modules/" + str(module["id"]), {
            "name": module["name"] + "-upd", "description": "updated", "tags": "e2e2",
            "owner": "qa", "sortNo": 2})
        self.assertEqual(module["name"] + "-upd", updated["name"])
        self.assertIn(module["id"], [m["id"] for m in
                                     self.api.request("GET", f"/projects/{project_id}/test-modules")])

        meta = self.api.request("PUT", suite_path, {
            "moduleId": module["id"], "owner": "qa", "tags": "api,smoke"})
        self.assertEqual("qa", meta["owner"])
        self.assertEqual(module["id"], meta["moduleId"])
        self.assertEqual("APPROVED", self.api.request(
            "POST", suite_path + "/review", {"reviewStatus": "APPROVED", "note": "e2e"})["reviewStatus"])

        case_key = "health-" + uuid.uuid4().hex[:8]
        saved = self.api.request("POST", suite_path + "/test-cases", {
            "changeNote": "initial", "spec": self._cap104_http_spec(case_key)})
        case = saved["testCase"]
        self.addCleanup(self.api.request, "DELETE", "/test-cases/" + str(case["id"]))
        self.assertEqual(case_key, case["caseKey"])
        self.assertEqual(1, case["caseVersion"])
        self.assertEqual("DRAFT", case["reviewStatus"])
        self.assertEqual("HTTP", case["type"])
        self.assertEqual([], saved["duplicates"])

        duplicate = self.api.request("POST", suite_path + "/test-cases", {
            "changeNote": "same shape", "spec": self._cap104_http_spec(case_key + "-dup")})
        self.addCleanup(self.api.request, "DELETE", "/test-cases/" + str(duplicate["testCase"]["id"]))
        self.assertIn(case["id"], [c["id"] for c in duplicate["duplicates"]])

        self.api.request("POST", suite_path + "/test-cases",
                         {"changeNote": "bad", "spec": {"title": "", "type": "HTTP"}}, expected=400)
        listed = self.api.request("GET", f"/test-cases?projectId={project_id}"
                                         f"&moduleId={module['id']}&type=HTTP&reviewStatus=DRAFT")
        self.assertIn(case["id"], [c["id"] for c in listed])

        reviewed = self.api.request("POST", f"/test-cases/{case['id']}/review",
                                    {"reviewStatus": "APPROVED", "note": "e2e approve"})
        self.assertEqual("APPROVED", reviewed["reviewStatus"])
        versions = self.api.request("GET", f"/test-cases/{case['id']}/versions")
        self.assertEqual("APPROVED", versions[0]["reviewStatus"])
        self.assertEqual("e2e approve", versions[0]["changeNote"])

        environment = self.api.request("POST", f"/projects/{project_id}/environments", {
            "name": "TEST", "nodeIds": [], "variables": {"baseUrl": self.api.base},
            "productVersion": "1.0.0",
            "precheckJson": json.dumps({"config": {"expectedVersion": "1.0.0"}})})
        self.addCleanup(self.api.request, "DELETE",
                        f"/projects/{project_id}/environments/{environment['id']}")

        username = "e2e-cap104-" + uuid.uuid4().hex[:10]
        password = "e2e-" + uuid.uuid4().hex
        self.api.request("POST", "/auth/users",
                         {"username": username, "password": password, "role": "VIEWER"})
        self.addCleanup(self._ensure_user_absent, username)
        viewer = Api(self.api.base)
        viewer.login(username, password)
        viewer.request("GET", f"/test-cases?projectId={project_id}")
        viewer.request("POST", suite_path + "/test-cases",
                       {"changeNote": "x", "spec": self._cap104_http_spec("denied-key")}, expected=403)
        viewer.request("PUT", suite_path, {"owner": "denied"}, expected=403)
        viewer.request("POST", f"/test-cases/{case['id']}/review",
                       {"reviewStatus": "APPROVED"}, expected=403)
        viewer.request("POST", f"/test-cases/{case['id']}/run", {}, expected=403)
        viewer.request("POST", f"/projects/{project_id}/environments/{environment['id']}/precheck",
                       expected=403)

        precheck = self.api.request(
            "POST", f"/projects/{project_id}/environments/{environment['id']}/precheck")
        self.assertTrue(precheck["passed"])
        names = {item["name"] for item in precheck["items"]}
        self.assertIn("HTTP", names)
        self.assertIn("VERSION_MATCH", names)

        created = self.api.request("POST", f"/test-cases/{case['id']}/run",
                                   {"environmentId": environment["id"]})
        self.addCleanup(self.api.request, "DELETE", "/test-runs/" + str(created["id"]))
        run = self._cap104_wait_run(created["id"])
        self.assertEqual("SUCCESS", run["status"])
        self.assertEqual("CASE", run["runScope"])
        self.assertEqual("PASS", run["conclusion"])
        self.assertEqual(1, len(run["results"]))
        result = run["results"][0]
        self.assertEqual("pass", result["status"])
        self.assertTrue(result["assertions"])
        self.assertTrue(all(a["pass"] for a in result["assertions"]))

        evidence = self.api.request("GET", f"/test-runs/{run['id']}/evidence")
        self.assertTrue({"QUEUE_LOG", "STDOUT", "RESULT_JSON"} <= {e["kind"] for e in evidence})
        self.assertEqual(list(range(1, len(evidence) + 1)), [e["seqNo"] for e in evidence])
        verified = self.api.request("GET", f"/test-runs/{run['id']}/evidence/verify")
        self.assertTrue(verified["valid"])
        self.assertEqual(len(evidence), verified["count"])
        self.assertTrue(self.api.download(f"/test-evidence/{evidence[0]['id']}/download"))

        reviewed_result = self.api.request(
            "PUT", f"/test-runs/{run['id']}/results/{result['id']}/review",
            {"review": "CONFIRMED", "note": "e2e confirm"})
        self.assertEqual("CONFIRMED", reviewed_result["manualReview"])
        report = self.api.request("GET", f"/test-runs/{run['id']}/report.json")
        self.assertEqual(run["id"], report["run"]["runId"])
        self.assertEqual("PASS", report["run"]["conclusion"])
        self.assertEqual(1, len(report["results"]))

        # CAP-10 旧链路不被 CAP-104 改动破坏：无 spec 的 legacy 套件仍可整体保存并运行
        legacy_suite = self.api.request("POST", f"/projects/{project_id}/test-suites", {
            "name": "e2e-legacy-" + uuid.uuid4().hex[:8], "kind": "api"})
        legacy_path = "/test-suites/" + str(legacy_suite["id"])
        self.addCleanup(self.api.request, "DELETE", legacy_path)
        self.api.request("PUT", legacy_path + "/cases", [{
            "name": "legacy health", "kind": "http", "method": "GET", "path": "/health",
            "expected": {"status": 200, "contains": "UP"}, "enabled": True}])
        legacy_created = self.api.request("POST", "/tests/runs", {
            "projectId": project_id, "suiteIds": [legacy_suite["id"]], "baseUrl": self.api.base})
        self.addCleanup(self.api.request, "DELETE", "/test-runs/" + str(legacy_created["id"]))
        legacy_run = self._cap104_wait_run(legacy_created["id"])
        self.assertEqual("SUCCESS", legacy_run["status"])
        self.assertEqual("pass", legacy_run["results"][0]["status"])
        self.assertEqual("legacy health", legacy_run["results"][0]["name"])

    def test_test_asset_review_gate_and_deletion(self):
        project_id = self._cap104_project()
        module, suite = self._cap104_suite(project_id)

        self.api.request("POST", f"/projects/{project_id}/test-assets/git", {
            "name": "e2e-git-asset", "repoUrl": "https://example.com/e2e.git",
            "branch": "main", "commitHash": "not-a-hash"}, expected=400)

        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("spec/health.json", "{}")
            archive.writestr("cases/health.md", "# health")
        asset = self.api.upload(f"/projects/{project_id}/test-assets/upload", "asset.zip",
                                buffer.getvalue(),
                                {"name": "e2e-asset-" + uuid.uuid4().hex[:8], "note": "e2e"})
        asset_path = "/test-assets/" + str(asset["id"])
        state = {"deleted": False}

        def detach_and_delete():
            if state["deleted"]:
                return
            self.api.request("PUT", "/test-suites/" + str(suite["id"]), {
                "moduleId": module["id"], "owner": "e2e", "tags": "api", "sourceAssetId": None})
            self.api.request("DELETE", asset_path)

        self.addCleanup(detach_and_delete)
        self.assertEqual("ZIP", asset["kind"])
        self.assertEqual(2, asset["fileCount"])
        self.assertEqual("DRAFT", asset["reviewStatus"])

        suite_body = {"moduleId": module["id"], "owner": "e2e", "tags": "api",
                      "sourceAssetId": asset["id"]}
        self.api.request("PUT", "/test-suites/" + str(suite["id"]), suite_body, expected=409)
        self.api.request("POST", asset_path + "/review", {"reviewStatus": "APPROVED", "note": "e2e"})
        approved = self.api.request("GET", f"/projects/{project_id}/test-assets?reviewStatus=APPROVED")
        self.assertIn(asset["id"], [a["id"] for a in approved])
        self.assertEqual(asset["id"], self.api.request(
            "PUT", "/test-suites/" + str(suite["id"]), suite_body)["sourceAssetId"])
        self.api.request("DELETE", asset_path, expected=409)

        self.api.request("PUT", "/test-suites/" + str(suite["id"]), {
            "moduleId": module["id"], "owner": "e2e", "tags": "api", "sourceAssetId": None})
        self.api.request("DELETE", asset_path)
        state["deleted"] = True
        self.assertNotIn(asset["id"], [a["id"] for a in
                                       self.api.request("GET", f"/projects/{project_id}/test-assets")])

    def test_plan_orchestration_baseline_stats_and_evidence(self):
        from support import Api
        project_id = self._cap104_project()
        module, suite = self._cap104_suite(project_id)
        suite_path = "/test-suites/" + str(suite["id"])
        pass_case = self._cap104_approved_case(
            suite_path, self._cap104_http_spec("e2e-pass-" + uuid.uuid4().hex[:8]))
        fail_case = self._cap104_approved_case(
            suite_path, self._cap104_http_spec("e2e-fail-" + uuid.uuid4().hex[:8],
                                               expected_status="500"))
        environment = self._cap104_environment(project_id)
        secret = "e2e-secret-" + uuid.uuid4().hex
        plan, plan_state = self._cap104_plan(project_id, {
            "name": "e2e-plan-" + uuid.uuid4().hex[:8], "description": "e2e orchestration",
            "failStrategy": "CONTINUE", "concurrency": 1,
            "parameters": {"apiToken": secret, "visibleParam": "alpha"},
            "items": [{"refType": "SUITE", "refId": suite["id"], "seq": 1, "enabled": True}]})
        self.assertEqual(1, plan["planVersion"])
        self.assertEqual("******", plan["parameters"]["apiToken"])
        self.assertEqual("alpha", plan["parameters"]["visibleParam"])

        first = self._cap104_wait_plan_run(self.api.request(
            "POST", f"/test-plans/{plan['id']}/run", {"environmentId": environment["id"]})["id"])
        self.assertEqual("FAILED", first["status"])
        self.assertEqual("PARTIAL", first["conclusion"])
        self.assertEqual(1, first["summary"]["items"])
        self.assertEqual(2, first["summary"]["total"])
        self.assertEqual(1, first["summary"]["casePassed"])
        self.assertEqual(1, first["summary"]["caseFailed"])
        self.assertEqual(50.0, first["summary"]["passRate"])
        self.assertEqual(1, len(first["children"]))
        first_cases = self.api.request("GET", f"/test-plan-runs/{first['id']}/cases")
        self.assertEqual(2, len(first_cases))
        self.assertEqual({"pass", "fail"}, {c["status"] for c in first_cases})
        report = self.api.request("GET", f"/test-plan-runs/{first['id']}/report.json")
        self.assertEqual(first["id"], report["planRun"]["id"])
        self.assertEqual(2, len(report["cases"]))
        self.assertNotIn(secret, json.dumps(report, ensure_ascii=False))

        # 方案更新：视图回传掩码，服务端按旧值还原；直接提交掩码（无旧值）必须拒绝
        current = self.api.request("GET", f"/test-plans/{plan['id']}")
        updated = self.api.request("PUT", f"/test-plans/{plan['id']}", {
            "name": current["name"] + "-v2", "description": "updated",
            "environmentId": current["environmentId"],
            "failStrategy": current["failStrategy"], "concurrency": current["concurrency"],
            "parameters": current["parameters"], "requirementId": current["requirementId"],
            "isTemplate": current["isTemplate"],
            "items": [{"refType": "SUITE", "refId": suite["id"], "seq": 1, "enabled": True}]})
        self.assertEqual(2, updated["planVersion"])
        self.assertEqual("******", updated["parameters"]["apiToken"])
        self.assertEqual("alpha", updated["parameters"]["visibleParam"])
        self.api.request("POST", f"/projects/{project_id}/test-plans", {
            "name": "e2e-masked-" + uuid.uuid4().hex[:8],
            "parameters": {"apiToken": "******"},
            "items": [{"refType": "CASE", "refId": pass_case["id"], "seq": 1}]}, expected=400)

        copied, copied_state = self._cap104_copy_plan(plan["id"])
        self.assertEqual(updated["name"] + " 副本", copied["name"])
        self.assertEqual(1, copied["planVersion"])
        self.assertEqual("******", copied["parameters"]["apiToken"])
        copied_current = self.api.request("GET", f"/test-plans/{copied['id']}")
        copied_updated = self.api.request("PUT", f"/test-plans/{copied['id']}", {
            "name": copied_current["name"], "parameters": copied_current["parameters"],
            "failStrategy": copied_current["failStrategy"],
            "concurrency": copied_current["concurrency"],
            "items": [{"refType": "SUITE", "refId": suite["id"], "seq": 1}]})
        self.assertEqual("******", copied_updated["parameters"]["apiToken"])
        self._cap104_delete_plan(copied["id"], copied_state)

        template, template_state = self._cap104_plan(project_id, {
            "name": "e2e-template-" + uuid.uuid4().hex[:8], "isTemplate": True,
            "items": [{"refType": "SUITE", "refId": suite["id"], "seq": 1, "enabled": True}]})
        self.api.request("POST", f"/test-plans/{template['id']}/run", {}, expected=409)
        templates = self.api.request(
            "GET", f"/projects/{project_id}/test-plans?templateOnly=true")
        self.assertIn(template["id"], [p["id"] for p in templates])
        template_copy, template_copy_state = self._cap104_copy_plan(template["id"])
        self.assertFalse(template_copy["isTemplate"])
        self._cap104_delete_plan(template_copy["id"], template_copy_state)
        self._cap104_delete_plan(template["id"], template_state)

        rerun = self._cap104_wait_plan_run(self.api.request(
            "POST", f"/test-plan-runs/{first['id']}/rerun", {})["id"])
        self.assertEqual(first["planName"], rerun["planName"])
        self.assertEqual(1, rerun["planVersion"])
        self.assertEqual("PARTIAL", rerun["conclusion"])
        history = self.api.request(
            "GET", f"/test-plan-runs?projectId={project_id}&planId={plan['id']}")
        self.assertEqual({first["id"], rerun["id"]}, {r["id"] for r in history})

        baseline, baseline_state = self._cap104_baseline(
            rerun["id"], "e2e-baseline-" + uuid.uuid4().hex[:8])
        self.assertEqual(2, baseline["caseCount"])
        comparison = self.api.request(
            "GET", f"/test-baselines/{baseline['id']}/compare?planRunId={rerun['id']}")
        self.assertEqual(1, len(comparison["persistentFailures"]))
        self.assertEqual(0, len(comparison["newFailures"]))
        self.assertEqual(0, len(comparison["fixed"]))
        self.assertEqual(0.0, comparison["passRateDelta"])
        baselines = self.api.request(
            "GET", f"/projects/{project_id}/test-baselines?planId={plan['id']}")
        self.assertIn(baseline["id"], [b["id"] for b in baselines])
        self.api.request("DELETE", f"/test-baselines/{baseline['id']}")
        baseline_state["deleted"] = True
        self.api.request("GET", f"/test-baselines/{baseline['id']}", expected=404)

        stats = self.api.request(
            "GET", f"/projects/{project_id}/test-stats?days=7&granularity=DAY&planId={plan['id']}")
        overview = stats["overview"]
        self.assertEqual(2, overview["runs"])
        self.assertEqual(4, overview["cases"])
        self.assertEqual(2, overview["passed"])
        self.assertEqual(2, overview["failed"])
        self.assertEqual(50.0, overview["passRate"])
        self.assertEqual(1, len(stats["topFailures"]))
        self.assertEqual(fail_case["id"], stats["topFailures"][0]["caseId"])
        self.assertEqual(2, stats["topFailures"][0]["failures"])

        entries = self._cap104_zip_entries(
            self.api.download(f"/test-plan-runs/{first['id']}/evidence.zip"))
        manifest = json.loads(entries["manifest.json"])
        self.assertEqual("PLAN_RUN", manifest["scope"])
        self.assertEqual(1, len(manifest["children"]))
        self.assertTrue(all(chain["valid"] for chain in manifest["chains"]))
        self.assertEqual("******", manifest["planSnapshot"]["parameters"]["apiToken"])
        self.assertIn("redaction", manifest)
        self.assertIn("report.md", entries)
        self.assertIn("report.json", entries)
        self.assertTrue(any(name.startswith("children/") and name.endswith("/report.md")
                            for name in entries))
        self.assertTrue(any(name.startswith("evidence/run-") for name in entries))
        for name, content in entries.items():
            self.assertNotIn(secret.encode(), content, f"secret leaked in evidence zip entry {name}")
        exported = json.loads(entries["report.json"])
        self.assertNotIn(secret, json.dumps(exported, ensure_ascii=False))

        with self.subTest("evidence export permission"):
            code = "E2E_" + uuid.uuid4().hex[:8].upper()
            username = "e2e-zip-" + uuid.uuid4().hex[:10]
            password = "e2e-" + uuid.uuid4().hex
            self.api.request("POST", "/auth/roles",
                             {"code": code.lower(), "name": "E2E 证据导出", "baseRole": "DEVELOPER"})
            self.addCleanup(self._ensure_role_absent, code)
            self.api.request("POST", "/auth/users",
                             {"username": username, "password": password, "role": code})
            self.addCleanup(self._ensure_user_absent, username)
            self.api.request("PUT", "/auth/menus/grants",
                             {"role": code, "menuKeys": ["project.tests"]})
            restricted = Api(self.api.base)
            restricted.login(username, password)
            restricted.download(f"/test-plan-runs/{first['id']}/evidence.zip", expected=403)
            self.assertTrue(self.api.download(f"/test-plan-runs/{first['id']}/evidence.zip"))

        self._cap104_delete_plan(plan["id"], plan_state)
        self.api.request("GET", f"/test-plans/{plan['id']}", expected=404)

    def test_distributed_matrix_and_shard(self):
        project_id = self._cap104_project()
        module, suite = self._cap104_suite(project_id)
        suite_path = "/test-suites/" + str(suite["id"])
        even_keys, odd_keys = [], []
        index = 0
        while len(even_keys) < 2 or len(odd_keys) < 2:
            key = "e2e-shard-" + str(index)
            bucket = even_keys if self._cap104_java_hash(key) & 1 == 0 else odd_keys
            if len(bucket) < 2:
                bucket.append(key)
            index += 1
            self.assertLess(index, 1000, "unable to pick shard keys")
        keys = even_keys + odd_keys
        for key in keys:
            self._cap104_approved_case(suite_path, self._cap104_http_spec(key))

        node_a = self._cap104_start_runner("e2e-runner-a-" + uuid.uuid4().hex[:8])
        node_b = self._cap104_start_runner("e2e-runner-b-" + uuid.uuid4().hex[:8])
        plan, _ = self._cap104_plan(project_id, {
            "name": "e2e-distributed-" + uuid.uuid4().hex[:8],
            "failStrategy": "CONTINUE", "concurrency": 2,
            "items": [{"refType": "SUITE", "refId": suite["id"], "seq": 1, "enabled": True}]})

        matrix = self._cap104_wait_plan_run(self.api.request("POST", f"/test-plans/{plan['id']}/run", {
            "mode": "MATRIX", "nodeIds": [node_a, node_b], "baseUrl": self.api.base})["id"])
        self.assertEqual("SUCCESS", matrix["status"])
        self.assertEqual("PASS", matrix["conclusion"])
        self.assertEqual(2, matrix["summary"]["items"])
        self.assertEqual(8, matrix["summary"]["total"])
        self.assertEqual(8, matrix["summary"]["casePassed"])
        self.assertEqual(100.0, matrix["summary"]["passRate"])
        self.assertEqual(2, len(matrix["children"]))
        self.assertEqual({node_a, node_b},
                         {str(child["agentNodeId"]) for child in matrix["children"]})
        matrix_cases = self.api.request("GET", f"/test-plan-runs/{matrix['id']}/cases")
        self.assertEqual(8, len(matrix_cases))
        for node_id in (node_a, node_b):
            mine = [c for c in matrix_cases if c["nodeId"] == node_id]
            self.assertEqual(4, len(mine))
            self.assertEqual(set(keys), {c["caseKey"] for c in mine})
            self.assertTrue(all(c["status"] == "pass" for c in mine))
            self.assertEqual({node_id}, {c["shardKey"] for c in mine})

        shard = self._cap104_wait_plan_run(self.api.request("POST", f"/test-plans/{plan['id']}/run", {
            "mode": "SHARD", "nodeIds": [node_a, node_b], "baseUrl": self.api.base})["id"])
        self.assertEqual("SUCCESS", shard["status"])
        self.assertEqual("PASS", shard["conclusion"])
        self.assertEqual(2, shard["summary"]["items"])
        self.assertEqual(4, shard["summary"]["total"])
        self.assertEqual(4, shard["summary"]["casePassed"])
        self.assertEqual(2, len(shard["children"]))
        self.assertEqual([2, 2], sorted(child["summary"]["total"] for child in shard["children"]))
        shard_cases = self.api.request("GET", f"/test-plan-runs/{shard['id']}/cases")
        self.assertEqual(4, len(shard_cases))
        for node_id, expected in ((node_a, set(even_keys)), (node_b, set(odd_keys))):
            mine = [c for c in shard_cases if c["nodeId"] == node_id]
            self.assertEqual(2, len(mine))
            self.assertEqual(expected, {c["caseKey"] for c in mine})
            self.assertEqual({node_id}, {c["shardKey"] for c in mine})
            self.assertTrue(all(c["status"] == "pass" for c in mine))

        self.api.request("POST", f"/test-plans/{plan['id']}/run",
                         {"mode": "SEQUENTIAL", "nodeIds": [node_a]}, expected=400)
        self.api.request("POST", f"/test-plans/{plan['id']}/run", {
            "mode": "MATRIX", "nodeIds": ["e2e-missing-" + uuid.uuid4().hex]}, expected=400)
        self.api.request("POST", f"/test-plans/{plan['id']}/run", {
            "mode": "MATRIX", "labels": ["e2e-missing-" + uuid.uuid4().hex]}, expected=400)

    def test_runner_partial_failure_and_program_case(self):
        project_id = self._cap104_project()
        module, suite = self._cap104_suite(project_id)
        suite_path = "/test-suites/" + str(suite["id"])
        keys = ["e2e-blocked-" + uuid.uuid4().hex[:8] for _ in range(4)]
        for key in keys:
            self._cap104_approved_case(suite_path, self._cap104_http_spec(key))

        online = self._cap104_start_runner("e2e-runner-online-" + uuid.uuid4().hex[:8])
        offline = self._cap104_create_node("e2e-runner-offline-" + uuid.uuid4().hex[:8])
        environment = self._cap104_environment(project_id, [online, offline])
        plan, _ = self._cap104_plan(project_id, {
            "name": "e2e-partial-" + uuid.uuid4().hex[:8],
            "failStrategy": "CONTINUE", "concurrency": 2,
            "items": [{"refType": "SUITE", "refId": suite["id"], "seq": 1, "enabled": True}]})
        partial = self._cap104_wait_plan_run(self.api.request("POST", f"/test-plans/{plan['id']}/run", {
            "mode": "MATRIX", "nodeIds": [online, offline],
            "environmentId": environment["id"]})["id"])
        self.assertEqual("FAILED", partial["status"])
        self.assertEqual("PARTIAL", partial["conclusion"])
        self.assertEqual(2, partial["summary"]["items"])
        self.assertEqual(1, partial["summary"]["passed"])
        self.assertEqual(1, partial["summary"]["blocked"])
        self.assertEqual(8, partial["summary"]["total"])
        self.assertEqual(4, partial["summary"]["casePassed"])
        self.assertEqual(4, partial["summary"]["caseBlocked"])
        self.assertEqual(0, partial["summary"]["caseFailed"])
        self.assertEqual(100.0, partial["summary"]["passRate"])
        self.assertEqual(1, len(partial["children"]))
        self.assertEqual(online, str(partial["children"][0]["agentNodeId"]))
        items = {str(item["shardKey"]): item for item in partial["items"]}
        self.assertEqual("PASS", items[online]["status"])
        self.assertEqual("BLOCKED", items[offline]["status"])
        self.assertEqual(4, items[offline]["blocked"])

        secret = "e2e-secret-" + uuid.uuid4().hex
        asset = self.api.upload(
            f"/projects/{project_id}/test-assets/upload", "e2e-program.zip",
            self._cap104_program_zip(),
            {"name": "e2e-program-" + uuid.uuid4().hex[:8], "note": "e2e", "assetRole": "PROGRAM"})
        asset_id = asset["id"]
        asset_state = {"deleted": False}

        def delete_asset():
            if not asset_state["deleted"]:
                self.api.request("DELETE", f"/test-assets/{asset_id}")
                asset_state["deleted"] = True

        self.addCleanup(delete_asset)
        self.assertEqual("PROGRAM", asset["assetRole"])
        self.assertEqual("DRAFT", asset["reviewStatus"])
        self.assertEqual("ZIP", asset["kind"])
        manifest_text = self.api.download(f"/test-assets/{asset_id}/manifest")
        self.assertIn(b"e2e-program", manifest_text)
        program_key = "e2e-program-" + uuid.uuid4().hex[:8]
        program_spec = self._cap104_program_spec(program_key, asset_id)
        self.api.request("POST", suite_path + "/test-cases",
                         {"changeNote": "e2e", "spec": program_spec}, expected=409)
        self.api.request("POST", f"/test-assets/{asset_id}/review",
                         {"reviewStatus": "APPROVED", "note": "e2e approve"})
        program_case = self._cap104_approved_case(suite_path, program_spec)

        program_plan, _ = self._cap104_plan(project_id, {
            "name": "e2e-program-plan-" + uuid.uuid4().hex[:8],
            "failStrategy": "CONTINUE", "concurrency": 1,
            "parameters": {"apiToken": secret, "visibleParam": "e2e"},
            "items": [{"refType": "CASE", "refId": program_case["id"], "seq": 1, "enabled": True}]})
        program_run = self._cap104_wait_plan_run(self.api.request(
            "POST", f"/test-plans/{program_plan['id']}/run",
            {"mode": "MATRIX", "nodeIds": [online]})["id"])
        self.assertEqual("SUCCESS", program_run["status"])
        self.assertEqual("PASS", program_run["conclusion"])
        self.assertEqual(1, program_run["summary"]["total"])
        self.assertEqual(1, program_run["summary"]["casePassed"])
        self.assertEqual(100.0, program_run["summary"]["passRate"])
        self.assertEqual(1, len(program_run["children"]))
        child_id = program_run["children"][0]["id"]
        child = self.api.request("GET", f"/test-runs/{child_id}")
        result = child["results"][0]
        self.assertEqual("pass", result["status"])
        program = result["program"]
        self.assertEqual("pass", program["status"])
        self.assertEqual({"checked": 2}, program["metrics"])
        self.assertEqual(1, len(program["artifacts"]))
        self.assertEqual("report.txt", program["artifacts"][0]["path"])
        self.assertEqual(11, program["artifacts"][0]["sizeBytes"])
        self.assertTrue(all(a["pass"] for a in result["assertions"]))
        self.assertTrue(any(a["source"] == "CUSTOM" and a["selector"] == "input-filtered"
                            for a in result["assertions"]))

        evidence = self.api.request("GET", f"/test-runs/{child_id}/evidence")
        kinds = {e["kind"] for e in evidence}
        self.assertLessEqual({"PROGRAM_MANIFEST", "INPUT_JSON", "RESULT_JSON", "STDOUT",
                              "ARTIFACT", "PROGRAM_SUMMARY"}, kinds)
        self.assertEqual(list(range(1, len(evidence) + 1)), [e["seqNo"] for e in evidence])
        verified = self.api.request("GET", f"/test-runs/{child_id}/evidence/verify")
        self.assertTrue(verified["valid"])
        self.assertEqual(len(evidence), verified["count"])
        input_evidence = next(e for e in evidence if e["kind"] == "INPUT_JSON")
        input_text = self.api.download(
            f"/test-evidence/{input_evidence['id']}/download").decode("utf-8")
        self.assertNotIn("apiToken", input_text)
        self.assertIn('"visibleParam":"e2e"', input_text)
        artifact_evidence = next(e for e in evidence if e["kind"] == "ARTIFACT")
        self.assertEqual(b"artifact-ok",
                         self.api.download(f"/test-evidence/{artifact_evidence['id']}/download"))
        for entry in evidence:
            self.assertNotIn(secret.encode(),
                             self.api.download(f"/test-evidence/{entry['id']}/download"),
                             f"secret leaked in evidence {entry['kind']}")

        entries = self._cap104_zip_entries(
            self.api.download(f"/test-plan-runs/{program_run['id']}/evidence.zip"))
        self.assertEqual("PLAN_RUN", json.loads(entries["manifest.json"])["scope"])
        for name, content in entries.items():
            self.assertNotIn(secret.encode(), content, f"secret leaked in evidence zip entry {name}")

        self.api.request("DELETE", f"/test-assets/{asset_id}", expected=409)

    def test_requirement_ai_expand(self):
        # CAP-105：关键点 → one-shot 同步扩展。fake runner 作平台默认节点；one-shot 的唯一产出
        # 通道是 EOF 时的 result 帧，fake 用 __FAKE_EOF_RESULT__ 指令行产出契约 JSON。
        path = self.workspace / ("project-" + uuid.uuid4().hex)
        path.mkdir()
        subprocess.run(["git", "init", "--initial-branch=master", str(path)],
                       check=True, capture_output=True)
        project = self.api.request("POST", "/projects", {
            "name": "e2e-ai-expand", "path": path.as_posix(), "defaultBranch": "master",
            "tags": ["e2e"], "description": "temporary fixture", "status": "ACTIVE"})
        project_id = project["id"]
        self.addCleanup(self.api.request, "DELETE", "/projects/" + project_id)

        node_id = self._cap104_start_runner("e2e-expand-" + uuid.uuid4().hex[:8])
        self.api.request("POST", f"/agent-nodes/{node_id}/default")
        self.addCleanup(self.api.request, "POST", f"/agent-nodes/{node_id}/unset-default")

        canned = {"title": "用户登录支持扫码", "type": "FEATURE",
                  "description": "## 背景与目标\n扫码登录。\n\n## 需求描述\n- pc 端\n\n## 验收标准\n- 可扫码"}
        directive = "__FAKE_EOF_RESULT__=" + json.dumps(canned, ensure_ascii=False)
        resp = self.api.request("POST", f"/projects/{project_id}/requirements/ai-expand",
                                {"points": "做一个扫码登录\n" + directive,
                                 "title": "旧标题", "description": "旧描述"})
        self.assertEqual(canned["title"], resp["title"])
        self.assertEqual(canned["type"], resp["type"])
        self.assertEqual(canned["description"], resp["description"])
        self.assertTrue(resp["sessionId"])
        # 执行痕迹可回溯：one-shot 会话在会话域里查得到
        session = self.api.request("GET", "/sessions/" + resp["sessionId"])
        self.assertEqual(resp["sessionId"], session["id"])

        self.api.request("POST", f"/projects/{project_id}/requirements/ai-expand",
                         {"points": "   "}, expected=400)
        self.api.request("POST", "/projects/ghost-nope/requirements/ai-expand",
                         {"points": "x"}, expected=404)

    def test_core_authorization_boundaries(self):
        # CAP-108 M1：应用 Service 边界统一授权——ACTION 表达式 / VIEWER 只读底线 /
        # 项目成员写范围（CAP-62）/ OWNER_ONLY 在引擎一处判定，不再各自为政
        from support import Api
        code = "E2E_" + uuid.uuid4().hex[:8].upper()
        role = self.api.request("POST", "/auth/roles", {"code": code.lower(), "name": "E2E 核心授权",
                                                       "description": "临时角色", "baseRole": "DEVELOPER"})
        self.assertEqual(code, role["code"])
        self.addCleanup(self._ensure_role_absent, code)
        username = "e2e-authz-" + uuid.uuid4().hex[:10]
        password = "e2e-" + uuid.uuid4().hex
        self.api.request("POST", "/auth/users", {"username": username, "password": password,
                                                 "role": code})
        self.addCleanup(self._ensure_user_absent, username)
        client = Api(self.api.base)
        client.login(username, password)

        # 建项目走 CAP-103 既有过滤器（M1 未纳入操作目录）：预授 create-project
        self.api.request("PUT", "/auth/menus/grants",
                         {"role": code, "menuKeys": ["admin.projects.action.create-project"]})

        # 自建项目：创建者自动落 OWNER 成员行（CAP-62 FR-01）
        path = self.workspace / ("project-" + uuid.uuid4().hex)
        path.mkdir()
        subprocess.run(["git", "init", "--initial-branch=master", str(path)],
                       check=True, capture_output=True)
        own = client.request("POST", "/projects", {
            "name": "e2e-authz-own", "path": path.as_posix(), "defaultBranch": "master",
            "tags": ["e2e"], "status": "ACTIVE"})
        self.addCleanup(self.api.request, "DELETE", "/projects/" + own["id"])
        own_reqs = f"/projects/{own['id']}/requirements"

        # ACTION 未授予：即使项目 OWNER / 本人归属也被核心授权拒绝（403）
        client.request("POST", own_reqs, {"title": "未授权创建"}, expected=403)
        client.request("POST", "/worklog/entries", {
            "workDate": "2026-11-04", "title": "未授权条目", "entryType": "DEV",
            "hours": 1.0}, expected=403)
        # 授予 requirement.create / worklog manage-entry 的 ACTION 后放行（create-project 保留）
        self.api.request("PUT", "/auth/menus/grants",
                         {"role": code, "menuKeys": ["admin.projects.action.create-project",
                                                     "project.requirements.action.create",
                                                     "top.worklog.action.manage-entry"]})
        req = client.request("POST", own_reqs, {"title": "授权后创建"})
        self.assertEqual("REQ-1", req["code"])

        # CAP-62 资源范围：PUBLIC 项目非成员可读不可写（SCOPE_DENIED 403）
        admin_path = self.workspace / ("project-" + uuid.uuid4().hex)
        admin_path.mkdir()
        subprocess.run(["git", "init", "--initial-branch=master", str(admin_path)],
                       check=True, capture_output=True)
        other = self.api.request("POST", "/projects", {
            "name": "e2e-authz-other", "path": admin_path.as_posix(), "defaultBranch": "master",
            "tags": ["e2e"], "status": "ACTIVE"})
        self.addCleanup(self.api.request, "DELETE", "/projects/" + other["id"])
        client.request("POST", f"/projects/{other['id']}/requirements",
                       {"title": "非成员写"}, expected=403)
        self.assertIn("items", client.request("GET", f"/projects/{other['id']}/requirements"))

        # OWNER_ONLY：本人工作日志可建（归属=服务端身份）
        entry = client.request("POST", "/worklog/entries", {
            "workDate": "2026-11-04", "title": "e2e 条目", "entryType": "DEV", "hours": 1.0})
        self.assertTrue(entry["id"])

        # VIEWER 只读底线：baseRole 降级后，即使有 ACTION + 项目 OWNER 也写不动
        self.api.request("PUT", "/auth/roles/" + code, {"name": "E2E 核心授权", "baseRole": "VIEWER"})
        client.login(username, password)
        client.request("POST", own_reqs, {"title": "viewer 写"}, expected=403)
        client.request("POST", "/worklog/entries", {
            "workDate": "2026-11-04", "title": "viewer 条目", "entryType": "DEV",
            "hours": 1.0}, expected=403)
        self.assertIn("items", client.request("GET", own_reqs))
    # ---------------- CAP-66 安全扫描（mock 引擎 + 真 runner + 真上传链路） ----------------

    def _sec_start_runner(self, name, project_id, work_dir, bin_dirs):
        """起真 runner：项目工作区映射 + scan.<engine>.path 注入 mock 工具目录 + exec 白名单。"""
        node_id, token = self._cap104_issue_node(name)
        folder = self.workspace / ("runner-" + uuid.uuid4().hex[:8])
        folder.mkdir()
        jar_source = ROOT / "devmind-agent-runner" / "target" / "devmind-agent-runner.jar"
        self.assertTrue(jar_source.is_file(),
                        "runner jar missing; build with mvn -q install -DskipTests")
        jar = folder / "devmind-agent-runner.jar"
        shutil.copy2(jar_source, jar)
        java = shutil.which("java")
        bash = shutil.which("bash")
        curl = shutil.which("curl")
        self.assertTrue(java and bash and curl, "java/bash/curl must be on PATH")
        parsed = urllib.parse.urlsplit(self.api.base)
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        ws_scheme = "wss" if parsed.scheme == "https" else "ws"
        config = folder / "agent.properties"
        config.write_text("\n".join([
            f"serverUrl={ws_scheme}://{parsed.hostname}:{port}/ws/agent",
            f"token={token}",
            "executor=fake",
            "execAllowlist=dependency-check,curl,mvn,gradle,semgrep,mkdir,gitleaks",
            f"execShell={Path(bash).as_posix()}",
            f"workDir={(folder / 'work').as_posix()}",
            f"project.{project_id}={work_dir.as_posix()}",
            f"scan.sca.path={bin_dirs['sca'].as_posix()}",
            f"scan.sast.path={bin_dirs['sast'].as_posix()}",
            f"scan.secrets.path={bin_dirs['secrets'].as_posix()}",
            f"workspaceRoot={(folder / 'workspaces').as_posix()}",
            f"worklogRoot={(folder / 'worklog').as_posix()}",
            "gitProxyEnabled=false",
            "maxConcurrent=4",
            "gcInitialDelayMinutes=600",
            "gcIntervalMinutes=600",
            "partialMessages=false",
        ]) + "\n", encoding="utf-8")
        env = os.environ.copy()
        env["PATH"] = str(Path(bash).parent) + os.pathsep + env.get("PATH", "")
        log = (folder / "runner.log").open("w", encoding="utf-8")
        proc = subprocess.Popen([java, "-jar", str(jar), str(config)], cwd=folder, env=env,
                                stdout=log, stderr=subprocess.STDOUT,
                                start_new_session=os.name != "nt")
        stopped = {"value": False}

        def stop():
            if stopped["value"]:
                return
            stopped["value"] = True
            try:
                if os.name == "nt":
                    subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"],
                                   capture_output=True)
                    proc.wait(timeout=15)
                else:
                    try:
                        os.killpg(proc.pid, signal.SIGTERM)
                        proc.wait(timeout=10)
                    except ProcessLookupError:
                        pass
                    except subprocess.TimeoutExpired:
                        os.killpg(proc.pid, signal.SIGKILL)
                        proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                proc.kill()
            finally:
                log.close()

        self.addCleanup(stop)
        deadline = time.monotonic() + 90
        while True:
            nodes = self.api.request("GET", "/agent-nodes")
            node = next((n for n in nodes if str(n["id"]) == node_id), None)
            if node and node.get("status") == "ONLINE" and (node.get("protocolVersion") or 0) >= 24:
                return node_id
            self.assertIsNone(proc.poll(), "runner exited before ONLINE; see tmp runner.log")
            self.assertLess(time.monotonic(), deadline, "runner did not reach ONLINE within 90s")
            time.sleep(1)

    @staticmethod
    def _sec_write_tool(directory, name, body):
        path = directory / name
        path.write_text(body, encoding="utf-8", newline="\n")
        os.chmod(path, 0o755)

    def _sec_wait_scan(self, scan_id, timeout=240):
        deadline = time.monotonic() + timeout
        while True:
            detail = self.api.request("GET", f"/security/scans/{scan_id}")
            if detail["scan"]["status"] in ("SUCCESS", "FAILED", "CANCELED"):
                return detail
            self.assertLess(time.monotonic(), deadline,
                            f"scan {scan_id} not finished in {timeout}s: {detail['scan']}")
            time.sleep(2)

    @staticmethod
    def _sec_sca_report(with_junit, with_log4j):
        deps = [
            {"fileName": "internal-lib-1.0.0.jar",
             "packages": [{"id": "pkg:maven/com.apusic/internal-lib@1.0.0"}],
             "vulnerabilities": [{"name": "CVE-2023-00001", "source": "NVD",
                                  "cvssv3": {"baseScore": 7.5, "attackVector": "NETWORK"},
                                  "description": "internal noise", "cwes": ["CWE-89"]}]},
            {"fileName": "commons-text-1.9.jar", "scope": "compile",
             "packages": [{"id": "pkg:maven/org.apache.commons/commons-text@1.9"}],
             "vulnerabilities": [{"name": "CVE-2022-42889", "source": "NVD",
                                  "cvssv3": {"baseScore": 9.8, "attackVector": "NETWORK"},
                                  "description": "Text4Shell", "cwes": ["CWE-94"],
                                  "vulnerableSoftware": [{"software": {
                                      "id": "cpe:2.3:a:apache:commons-text:*:*:*:*:*:*:*:*",
                                      "versionEndExcluding": "1.10.0"}}]}]},
            {"fileName": "okio-2.8.0.jar",
             "packages": [{"id": "pkg:maven/com.squareup.okio/okio@2.8.0"}],
             "vulnerabilities": [{"name": "CVE-2023-3635", "source": "NVD",
                                  "cvssv3": {"baseScore": 5.9},
                                  "description": "GzipSource DoS", "cwes": []}]},
        ]
        if with_junit:
            deps.append({"fileName": "junit-4.12.jar", "scope": "test",
                         "packages": [{"id": "pkg:maven/junit/junit@4.12"}],
                         "vulnerabilities": [{"name": "CVE-2020-15250", "source": "NVD",
                                              "cvssv3": {"baseScore": 4.4},
                                              "description": "TemporaryFolder 信息泄露", "cwes": []}]})
        if with_log4j:
            deps.append({"fileName": "log4j-core-2.14.1.jar",
                         "packages": [{"id": "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1"}],
                         "vulnerabilities": [{"name": "CVE-2021-44228", "source": "NVD",
                                              "cvssv3": {"baseScore": 10.0, "attackVector": "NETWORK"},
                                              "description": "Log4Shell", "cwes": ["CWE-502"],
                                              "vulnerableSoftware": [{"software": {
                                                  "id": "cpe:2.3:a:apache:log4j:*:*:*:*:*:*:*:*",
                                                  "versionStartIncluding": "2.0",
                                                  "versionEndExcluding": "2.15.0"}}]}]})
        return json.dumps({"dependencies": deps})

    def test_security_scan_pipeline(self):
        # CAP-66 §9：mock 引擎（读工作区 .devmind-mock/ 的罐装报告）+ 真 runner + 真上传/归一化链路。
        # 覆盖：白名单过滤计数、引入链回填（direct/transitive/depPath）、test scope 默认不进列表、
        # 密钥脱敏、修复建议（UPGRADE_DIRECT）、二次扫描 diff（SUPPRESSED/EXISTING/FIXED/NEW）、
        # 报告附件与导出、引擎子状态、toolcheck。
        secret = "AKIAIOSFODNN7EXAMPLE"
        sast_report = json.dumps({"version": "2.1.0", "runs": [
            {"tool": {"driver": {"name": "semgrep", "rules": [
                {"id": "java.lang.security.audit.sqli"},
                {"id": "java.lang.security.weak-crypto"}]}},
             "results": [
                 {"ruleId": "java.lang.security.audit.sqli", "level": "error",
                  "message": {"text": "SQL 注入风险"},
                  "locations": [{"physicalLocation": {
                      "artifactLocation": {"uri": "src/main/java/com/example/App.java"},
                      "region": {"startLine": 42, "snippet": {"text": "stmt.executeQuery(sql)"}}}}]},
                 {"ruleId": "java.lang.security.weak-crypto", "level": "warning",
                  "message": {"text": "弱哈希 MD5"},
                  "locations": [{"physicalLocation": {
                      "artifactLocation": {"uri": "src/main/java/com/example/Crypto.java"},
                      "region": {"startLine": 10, "snippet": {"text": "md5.digest()"}}}}]}]}]})
        secrets_report = json.dumps({"version": "2.1.0", "runs": [
            {"tool": {"driver": {"name": "gitleaks", "rules": [{"id": "aws-access-token"}]}},
             "results": [
                 {"ruleId": "aws-access-token", "level": "error",
                  "message": {"text": "AWS Access Key detected"},
                  "locations": [{"physicalLocation": {
                      "artifactLocation": {"uri": "config/keys.txt"},
                      "region": {"startLine": 3, "snippet": {"text": secret}}}}]}]}]})
        dep_tree = "\n".join([
            "com.example:app:jar:1.0.0",
            "+- org.apache.commons:commons-text:jar:1.9:compile",
            "|  +- com.squareup.okio:okio:jar:2.8.0:compile",
            "+- junit:junit:jar:4.12:test",
            "\\- org.apache.logging.log4j:log4j-core:jar:2.14.1:compile",
        ])

        # 项目（本地 git 库，CAP-02 建项目自动注册主库）
        proj_path = self.workspace / ("sec-proj-" + uuid.uuid4().hex[:8])
        proj_path.mkdir()
        subprocess.run(["git", "init", "--initial-branch=master", str(proj_path)],
                       check=True, capture_output=True)
        project = self.api.request("POST", "/projects", {
            "name": "e2e-security-" + uuid.uuid4().hex[:8], "path": proj_path.as_posix(),
            "defaultBranch": "master", "tags": ["e2e"], "status": "ACTIVE"})
        project_id = project["id"]
        self.addCleanup(self.api.request, "DELETE", "/projects/" + project_id)

        # runner 项目工作区：pom.xml 触发 SCA 依赖树采集分支；.devmind-mock/ 为罐装报告源
        work = self.workspace / ("sec-work-" + uuid.uuid4().hex[:8])
        mock_src = work / ".devmind-mock"
        mock_src.mkdir(parents=True)
        (work / "pom.xml").write_text("<project/>", encoding="utf-8")
        (mock_src / "sca-report.json").write_text(
            self._sec_sca_report(with_junit=True, with_log4j=False), encoding="utf-8")
        (mock_src / "sast-report.sarif").write_text(sast_report, encoding="utf-8")
        (mock_src / "secrets-report.sarif").write_text(secrets_report, encoding="utf-8")
        (mock_src / "dep-tree.txt").write_text(dep_tree, encoding="utf-8")

        # mock 引擎（SCA 目录附带 mock mvn 产出依赖树）
        bin_sca = self.workspace / ("bin-sca-" + uuid.uuid4().hex[:8])
        bin_sast = self.workspace / ("bin-sast-" + uuid.uuid4().hex[:8])
        bin_secrets = self.workspace / ("bin-secrets-" + uuid.uuid4().hex[:8])
        for d in (bin_sca, bin_sast, bin_secrets):
            d.mkdir()
        self._sec_write_tool(bin_sca, "dependency-check", """#!/usr/bin/env bash
if [ "$1" = "--version" ]; then echo "Dependency-Check version 0.0.0-e2e"; exit 0; fi
out=""
prev=""
for a in "$@"; do
  if [ "$prev" = "--out" ]; then out="$a"; fi
  prev="$a"
done
mkdir -p "$out"
cp .devmind-mock/sca-report.json "$out/dependency-check-report.json"
echo "<html>e2e</html>" > "$out/dependency-check-report.html"
""")
        self._sec_write_tool(bin_sca, "mvn", """#!/usr/bin/env bash
for a in "$@"; do
  case "$a" in
    -DoutputFile=*) cp .devmind-mock/dep-tree.txt "${a#-DoutputFile=}" ;;
  esac
done
exit 0
""")
        self._sec_write_tool(bin_sast, "semgrep", """#!/usr/bin/env bash
if [ "$1" = "--version" ]; then echo "0.0.0-e2e"; exit 0; fi
out=""
prev=""
for a in "$@"; do
  if [ "$prev" = "--output" ]; then out="$a"; fi
  prev="$a"
done
cp .devmind-mock/sast-report.sarif "$out"
exit 1
""")
        self._sec_write_tool(bin_secrets, "gitleaks", """#!/usr/bin/env bash
if [ "$1" = "version" ]; then echo "0.0.0-e2e"; exit 0; fi
out=""
prev=""
for a in "$@"; do
  if [ "$prev" = "--report-path" ]; then out="$a"; fi
  prev="$a"
done
cp .devmind-mock/secrets-report.sarif "$out"
exit 1
""")
        node_id = self._sec_start_runner("e2e-security-" + uuid.uuid4().hex[:8],
                                         project_id, work,
                                         {"sca": bin_sca, "sast": bin_sast, "secrets": bin_secrets})

        # 工具自检（ADMIN）：三引擎 --version 真跑
        checks = self.api.request("POST", f"/security/nodes/{node_id}/toolcheck")
        self.assertEqual(3, len(checks))
        for item in checks:
            self.assertTrue(item["ok"], f"toolcheck {item['engine']}: {item['output']}")

        # 项目白名单：内部 groupId 前缀
        self.api.request("PUT", f"/security/projects/{project_id}/config",
                         {"internalGroupPrefixes": "com.apusic"})
        cfg = self.api.request("GET", f"/security/projects/{project_id}/config")
        self.assertIn("com.apusic", cfg["effectivePrefixes"])

        # ---- 扫描 1：全引擎
        scan1 = self.api.request("POST", f"/security/scans?projectId={project_id}",
                                 {"engines": ["sca", "sast", "secrets"], "agentNodeId": node_id})
        d1 = self._sec_wait_scan(scan1["id"])
        self.assertEqual("SUCCESS", d1["scan"]["status"], d1["scan"].get("errorMessage"))
        self.assertEqual("SUCCESS", d1["engineStatus"]["sca"]["status"])
        self.assertEqual("SUCCESS", d1["engineStatus"]["sast"]["status"])
        self.assertEqual("SUCCESS", d1["engineStatus"]["secrets"]["status"])
        formats = {(r["engine"], r["format"]) for r in d1["reports"]}
        self.assertIn(("sca", "json"), formats)
        self.assertIn(("sca", "html"), formats)
        self.assertIn(("sca", "deptree"), formats)
        self.assertIn(("sast", "sarif"), formats)
        self.assertIn(("secrets", "sarif"), formats)
        # 白名单过滤计数：com.apusic 内部构件被滤
        self.assertEqual(1, d1["scan"]["filteredCount"])

        all1 = self.api.request("GET", f"/security/scans/{scan1['id']}/findings"
                                       "?status=ALL&includeTestScope=true")
        self.assertNotIn(secret, json.dumps(all1, ensure_ascii=False), "密钥原文泄漏进发现响应")
        by_cve = {json.loads(f["detailJson"]).get("cve"): f for f in all1 if f["engine"] == "sca"}
        self.assertNotIn("CVE-2023-00001", by_cve, "白名单构件未过滤")
        ct = by_cve["CVE-2022-42889"]
        ct_detail = json.loads(ct["detailJson"])
        self.assertEqual("NEW", ct["status"])
        self.assertEqual("CRITICAL", ct["severity"])
        self.assertTrue(ct["directDep"])
        self.assertEqual("org.apache.commons:commons-text", ct_detail["depPath"])
        self.assertEqual("UPGRADE_DIRECT", ct_detail["remediation"]["action"])
        self.assertEqual("1.10.0", ct_detail["remediation"]["recommendedVersion"])
        okio = by_cve["CVE-2023-3635"]
        self.assertEqual("MEDIUM", okio["severity"])
        self.assertFalse(okio["directDep"])
        self.assertEqual("org.apache.commons:commons-text > com.squareup.okio:okio",
                         json.loads(okio["detailJson"])["depPath"])
        junit = by_cve["CVE-2020-15250"]
        self.assertEqual("test", junit["depScope"])
        # 默认查询滤 test scope
        default1 = self.api.request("GET", f"/security/scans/{scan1['id']}/findings")
        self.assertNotIn("CVE-2020-15250",
                         {json.loads(f["detailJson"]).get("cve") for f in default1})
        # SAST 两条 + 脱敏密钥一条
        sast_rules = {f["ruleId"]: f for f in all1 if f["engine"] == "sast"}
        self.assertEqual("HIGH", sast_rules["java.lang.security.audit.sqli"]["severity"])
        self.assertEqual("MEDIUM", sast_rules["java.lang.security.weak-crypto"]["severity"])
        secrets_rows = [f for f in all1 if f["engine"] == "secrets"]
        self.assertEqual(1, len(secrets_rows))
        # 脱敏红线：脱敏后内容只进 codeHash 哈希，响应任何字段都不得带原文（上方已全量断言），
        # detailJson 也不得回流 snippet 原文
        self.assertNotIn("snippet", json.loads(secrets_rows[0]["detailJson"]))

        # 抑制 commons-text（误报），供二扫 diff 断言 SUPPRESSED
        self.api.request("POST", "/security/suppressions",
                         {"findingId": ct["id"], "type": "FALSE_POSITIVE",
                          "reason": "e2e 误报：不使用 StringSubstitutor"})

        # ---- 扫描 2：换 v2 报告（去 junit、加 log4j）
        (mock_src / "sca-report.json").write_text(
            self._sec_sca_report(with_junit=False, with_log4j=True), encoding="utf-8")
        scan2 = self.api.request("POST", f"/security/scans?projectId={project_id}",
                                 {"engines": ["sca", "sast", "secrets"], "agentNodeId": node_id})
        d2 = self._sec_wait_scan(scan2["id"])
        self.assertEqual("SUCCESS", d2["scan"]["status"], d2["scan"].get("errorMessage"))
        all2 = self.api.request("GET", f"/security/scans/{scan2['id']}/findings"
                                       "?status=ALL&includeTestScope=true")
        self.assertNotIn(secret, json.dumps(all2, ensure_ascii=False))
        by_cve2 = {json.loads(f["detailJson"]).get("cve"): f for f in all2 if f["engine"] == "sca"}
        self.assertEqual("SUPPRESSED", by_cve2["CVE-2022-42889"]["status"], "抑制未在 diff 生效")
        self.assertEqual("EXISTING", by_cve2["CVE-2023-3635"]["status"])
        self.assertEqual("FIXED", by_cve2["CVE-2020-15250"]["status"])
        log4j = by_cve2["CVE-2021-44228"]
        self.assertEqual("NEW", log4j["status"])
        self.assertEqual("CRITICAL", log4j["severity"])
        self.assertEqual("org.apache.logging.log4j:log4j-core",
                         json.loads(log4j["detailJson"])["depPath"])
        # EXISTING 的 firstSeen 延续首扫
        self.assertEqual(scan1["id"], by_cve2["CVE-2023-3635"]["firstSeenScanId"])
        # 导出 CSV：行数与内容
        exported = self.api.request("GET", f"/security/scans/{scan2['id']}/export?format=csv")
        self.assertGreater(exported["count"], 0)
        csv_text = self.api.download(f"/attachments/{exported['attachmentId']}/raw").decode("utf-8")
        self.assertIn("CVE-2021-44228", csv_text)
        self.assertNotIn(secret, csv_text)


class CopilotContract(unittest.TestCase):
    """Migrated from cap76_tools_contract.py; GET-only, requires admin."""
    api = None
    workspace = None

    def setUp(self):
        self.doc = self.api.request("GET", "/copilot/admin/openapi.json")
        self.tools = self.api.request("GET", "/copilot/admin/tools")
        self.assertIsInstance(self.tools, list)
        self.assertTrue(self.tools, "tool registry must not be empty")
        (self.workspace / "copilot-openapi.json").write_text(
            json.dumps(self.doc, ensure_ascii=False, indent=2), encoding="utf-8")

    def test_document_metadata(self):
        self.assertEqual("3.0.3", self.doc["openapi"])
        self.assertTrue(self.doc["info"]["title"])
        self.assertTrue(self.doc["info"]["version"])

    def test_operation_inventory(self):
        names = {t["name"] for t in self.tools}
        paths = self.doc["paths"]
        self.assertEqual(len(names), len(self.tools), "duplicate tool names")
        self.assertEqual({"/tools/" + n for n in names}, set(paths))
        self.assertEqual(names, {p["post"]["operationId"] for p in paths.values()})

    def test_operation_contracts(self):
        for tool in self.tools:
            operation = self.doc["paths"]["/tools/" + tool["name"]]["post"]
            self.assertEqual(tool["name"], operation["operationId"])
            self.assertEqual(tool.get("title"), operation.get("summary"))
            for ext, key in (("x-tool-risk", "risk"), ("x-min-role", "minRole"),
                             ("x-owner-module", "ownerModule"), ("x-tool-since", "since")):
                self.assertEqual(tool.get(key), operation.get(ext), f'{tool["name"]}: {ext}')
            self.assertEqual(json.loads(tool.get("parametersSchema") or "{}"),
                             operation["requestBody"]["content"]["application/json"]["schema"])
