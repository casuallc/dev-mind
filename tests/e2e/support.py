"""Standard-library HTTP client shared by the E2E runner and platform setup."""
import json
import ssl
import urllib.error
import urllib.request


class Api:
    def __init__(self, base, token="", insecure=False):
        self.base = base.rstrip("/")
        self.token = token
        self.context = ssl._create_unverified_context() if insecure else ssl.create_default_context()

    def request(self, method, path, body=None, expected=200, authenticated=True):
        headers = {"Content-Type": "application/json; charset=UTF-8"}
        if authenticated and self.token:
            headers["Authorization"] = "Bearer " + self.token
        request = urllib.request.Request(self.base + path, method=method, headers=headers,
                                         data=None if body is None else json.dumps(body).encode("utf-8"))
        try:
            with urllib.request.urlopen(request, context=self.context, timeout=15) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        # Never include response bodies or headers: they may contain credentials.
        if status != expected:
            raise AssertionError(f"{method} {path}: HTTP {status}, expected {expected}")
        return json.loads(raw.decode("utf-8")) if raw else None

    def login(self, username, password):
        self.token = self.request("POST", "/auth/login", {"username": username, "password": password})["accessToken"]
