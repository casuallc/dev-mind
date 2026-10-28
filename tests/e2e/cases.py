"""Official cases: independent fixtures, explicit assertions, cleanup registered first."""
import unittest
import uuid
import subprocess


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
