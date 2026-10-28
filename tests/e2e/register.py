#!/usr/bin/env python3
"""Idempotently register the smoke suite in an existing Dev-Mind project."""
import argparse
import json
import os
from urllib.parse import quote

from support import Api


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True, help="platform API URL, including /api")
    parser.add_argument("--project", required=True)
    parser.add_argument("--node", required=True)
    parser.add_argument("--repo", default="https://github.com/casuallc/dev-mind.git")
    parser.add_argument("--branch", default="master")
    parser.add_argument("--suite", choices=["smoke", "contract"], default="smoke")
    parser.add_argument("--workspace-key",
                        help="bump when updating test code; the platform reuses suite worktrees")
    parser.add_argument("--insecure", action="store_true")
    parser.add_argument("--apply", action="store_true", help="default prints a reviewable template")
    args = parser.parse_args()
    env = [{"key": "E2E_BASE", "value": args.base, "secret": False},
           {"key": "E2E_USER", "value": os.environ.get("E2E_USER", "admin"), "secret": False}]
    password = os.environ.get("E2E_PASSWORD")
    env.append({"key": "E2E_PASSWORD", "value": password if args.apply else "<set E2E_PASSWORD>", "secret": True})
    payload = {"projectId": args.project, "name": f"dev-mind / E2E {args.suite} (standard v1)",
               "repoUrl": args.repo, "branch": args.branch, "workSubdir": None,
               "command": f"bash scripts/e2e.sh --suite {args.suite}" + (" --insecure" if args.insecure else ""),
               "junitPath": "tmp/e2e/junit.xml", "env": env, "agentNodeId": args.node,
               "timeoutSec": 300, "workspaceKey": args.workspace_key or f"devmind-e2e-{args.suite}-v1"}
    if os.environ.get("E2E_PYTHON"):
        payload["env"].append({"key": "E2E_PYTHON", "value": os.environ["E2E_PYTHON"], "secret": False})
    if not args.apply:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return
    if not password:
        parser.error("E2E_PASSWORD is required for repeatable platform runs (short-lived tokens are unsuitable)")
    api = Api(args.base, os.environ.get("E2E_TOKEN", ""), args.insecure)
    if not api.token:
        api.login(os.environ.get("E2E_USER", "admin"), password)
    project = api.request("GET", "/projects/" + quote(args.project, safe=""))
    if project["name"] != "dev-mind":
        parser.error("expected project name dev-mind; verify --project")
    matches = [s for s in api.request("GET", "/script-suites?projectId=" + quote(args.project, safe=""))
               if s["name"] == payload["name"]]
    if len(matches) > 1:
        parser.error("duplicate managed suite names; resolve before applying")
    route = "/script-suites" + ("/" + str(matches[0]["id"]) if matches else "")
    result = api.request("PUT" if matches else "POST", route, payload)
    print(f'Registered suite #{result["id"]}; run from project Tests page after this branch is pushed.')


if __name__ == "__main__":
    main()
