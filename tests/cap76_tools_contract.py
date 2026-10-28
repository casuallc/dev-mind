#!/usr/bin/env python3
"""Compatibility entry for CAP-76; delegates to the standard contract suite.

Use E2E_BASE + E2E_TOKEN / E2E_PASSWORD for a shared target.
Without E2E_BASE the runner starts and cleans up a private app.
Snapshots are evidence under tmp/, never automatic edits to tracked docs.
"""
import os
from pathlib import Path
import subprocess
import sys

if __name__ == "__main__":
    root = Path(__file__).resolve().parent.parent
    args = [sys.executable, str(root / "tests/e2e/run.py"), "--suite", "contract"]
    if not os.environ.get("E2E_BASE"):
        args.append("--isolated")
    sys.exit(subprocess.call(args + sys.argv[1:]))
