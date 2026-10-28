"""Official cases: independent fixtures, explicit assertions, cleanup registered first."""
import unittest
import uuid
import subprocess
import json


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
        self.assertIn("admin.menus", keys)
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

        updated = [k for k in original if k not in ("top.home", "project.tests")]
        updated.append("admin.dashboard")
        self.api.request("PUT", "/auth/menus/grants", {"role": "DEVELOPER", "menuKeys": updated})
        after = {m["menuKey"] for m in client.request("GET", "/auth/menus")["items"]}
        self.assertNotIn("top.home", after)
        self.assertNotIn("project.tests", after)
        self.assertIn("admin.dashboard", after)
        self.assertIn("admin.dashboard",
                      self.api.request("GET", "/auth/menus/grants")["grants"]["DEVELOPER"])
        admin_keys = {m["menuKey"] for m in self.api.request("GET", "/auth/menus")["items"]}
        self.assertIn("admin.menus", admin_keys)

        self.api.request("PUT", "/auth/menus/grants", {"role": "ADMIN", "menuKeys": []}, expected=400)
        self.api.request("PUT", "/auth/menus/grants", {"role": "DEVELOPER", "menuKeys": ["not.exists"]},
                         expected=400)

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
