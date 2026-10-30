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
