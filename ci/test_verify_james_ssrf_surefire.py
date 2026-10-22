"""Guard against a Maven SUCCESS caused by a missing, skipped or failing Apache James SSRF suite."""

from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
import xml.etree.ElementTree as ET

from verify_james_ssrf_surefire import REQUIRED_METHODS, SUITE, verify


class JamesSsrfVerificationTest(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.report = self.directory / f"TEST-{SUITE}.xml"
        self.write_report()

    def write_report(self, name=None, tests=None, failures=0, errors=0, skipped=0,
                     classname=None, methods=REQUIRED_METHODS):
        cases = list(methods)
        if tests is not None:
            cases = (cases * tests)[:tests]
        root = ET.Element("testsuite", {
            "name": name or SUITE,
            "tests": str(len(cases)),
            "failures": str(failures),
            "errors": str(errors),
            "skipped": str(skipped),
        })
        for method in cases:
            case = ET.SubElement(root, "testcase", {
                "classname": classname or SUITE,
                "name": method,
            })
            if failures:
                ET.SubElement(case, "failure", {"message": "assertion failed"})
            if errors:
                ET.SubElement(case, "error", {"message": "exception"})
            if skipped:
                ET.SubElement(case, "skipped")
        ET.ElementTree(root).write(self.report, encoding="utf-8")

    def test_executed_suite_passes(self):
        self.assertEqual(len(verify(self.directory)), 1)

    def test_missing_report_rejected(self):
        self.report.unlink()
        with self.assertRaisesRegex(ValueError, "Missing exact Surefire report"):
            verify(self.directory)

    def test_wrong_suite_identity_rejected(self):
        self.write_report(name="org.apache.james.jmap.pushsubscription.OtherTest")
        with self.assertRaisesRegex(ValueError, "Wrong Surefire suite identity"):
            verify(self.directory)

    def test_failing_suite_rejected(self):
        self.write_report(failures=1)
        with self.assertRaisesRegex(ValueError, "failed"):
            verify(self.directory)

    def test_errored_suite_rejected(self):
        self.write_report(errors=1)
        with self.assertRaisesRegex(ValueError, "failed"):
            verify(self.directory)

    def test_fully_skipped_suite_rejected(self):
        self.write_report(skipped=len(REQUIRED_METHODS))
        with self.assertRaisesRegex(ValueError, "ran no test methods"):
            verify(self.directory)

    def test_reduced_suite_rejected(self):
        self.write_report(methods=REQUIRED_METHODS[:2])
        with self.assertRaisesRegex(ValueError, "missing executed SSRF policy methods"):
            verify(self.directory)


if __name__ == "__main__":
    unittest.main()
