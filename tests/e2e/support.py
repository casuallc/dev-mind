"""Standard-library HTTP client shared by the E2E runner and platform setup."""
import json
import ssl
import urllib.error
import urllib.request
import uuid


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

    def upload(self, path, filename, content, fields=None, expected=200):
        """Multipart POST（测试资产上传）；错误信息同样不携带响应体。"""
        boundary = "----devmind-e2e-" + uuid.uuid4().hex
        parts = []
        for key, value in (fields or {}).items():
            parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{key}\"\r\n\r\n"
                         f"{value}\r\n".encode("utf-8"))
        parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
                     f"filename=\"{filename}\"\r\nContent-Type: application/octet-stream\r\n\r\n"
                     .encode("utf-8") + content + b"\r\n")
        parts.append(f"--{boundary}--\r\n".encode("utf-8"))
        headers = {"Content-Type": "multipart/form-data; boundary=" + boundary}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        request = urllib.request.Request(self.base + path, method="POST", headers=headers,
                                         data=b"".join(parts))
        try:
            with urllib.request.urlopen(request, context=self.context, timeout=15) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        if status != expected:
            raise AssertionError(f"POST {path}: HTTP {status}, expected {expected}")
        return json.loads(raw.decode("utf-8")) if raw else None

    def download(self, path, expected=200):
        headers = {}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        request = urllib.request.Request(self.base + path, headers=headers)
        try:
            with urllib.request.urlopen(request, context=self.context, timeout=15) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        if status != expected:
            raise AssertionError(f"GET {path}: HTTP {status}, expected {expected}")
        return raw

    def login(self, username, password):
        self.token = self.request("POST", "/auth/login", {"username": username, "password": password})["accessToken"]
