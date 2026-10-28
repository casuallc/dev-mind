"""Contract checks for the runner itself; outputs stay under ignored tmp/."""
import os
import subprocess
import sys
import unittest
import uuid
import xml.etree.ElementTree as ET

from run import ROOT, catalog, write_report


class RunnerContract(unittest.TestCase):
    def test_catalog_complete(self):
        self.assertGreaterEqual(len(catalog()), 6)

    def test_report_preserves_xml_and_outcomes(self):
        path = ROOT / "tmp/e2e" / ("contract-" + uuid.uuid4().hex + ".xml")
        self.addCleanup(path.unlink, missing_ok=True)
        write_report(path, [("suite.pass", "pass", "", 0.1),
                            ("suite.fail", "failure", "bad <value> & 中文", 0.2),
                            ("suite.start", "error", "connection failed", 0),
                            ("suite.skip", "skipped", "explicit reason", 0)])
        xml = ET.parse(path).getroot()
        self.assertEqual(("4", "1", "1", "1"), tuple(xml.get(k) for k in ("tests", "failures", "errors", "skipped")))
        self.assertEqual("bad <value> & 中文", xml.find("testcase/failure").text)

    def run_invalid(self, args, expected):
        name = "tmp/e2e/contract-" + uuid.uuid4().hex + ".xml"
        path = ROOT / name
        self.addCleanup(path.unlink, missing_ok=True)
        # A stale success report must be replaced even on a configuration failure.
        write_report(path, [("old.pass", "pass", "", 0)])
        env = {k: v for k, v in os.environ.items() if not k.startswith("E2E_")}
        proc = subprocess.run([sys.executable, str(ROOT / "tests/e2e/run.py"), "--junit", name] + args,
                              env=env, capture_output=True, timeout=20)
        self.assertEqual(1, proc.returncode)
        xml = ET.parse(path).getroot()
        self.assertEqual("1", xml.get("errors"))
        self.assertIn(expected, xml.find("testcase/error").text)

    def test_no_implicit_shared_target(self):
        self.run_invalid([], "no implicit shared target")

    def test_regression_cannot_write_shared_instance(self):
        self.run_invalid(["--suite", "regression", "--base", "https://example.invalid/api"], "regression requires --isolated")

    def test_isolated_target_cannot_be_overridden(self):
        self.run_invalid(["--isolated", "--base", "https://example.invalid/api"], "--isolated cannot be combined")


if __name__ == "__main__":
    unittest.main()
