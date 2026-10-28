#!/usr/bin/env bash
# Platform entry: same runner and JUnit location as a developer's local run.
set -euo pipefail
cd "$(dirname "$0")/.."
# The platform may reuse this checkout; never return a previous run's report.
rm -f tmp/e2e/junit.xml
export PYTHONUTF8=1
export PYTHONIOENCODING=utf-8
if [[ -n "${E2E_PYTHON:-}" ]]; then
  python_bin="$E2E_PYTHON"
elif command -v python3 >/dev/null 2>&1; then
  python_bin=python3
else
  python_bin=python
fi
exec "$python_bin" tests/e2e/run.py "$@"
