"""Guard against Maven SUCCESS caused by missing, skipped, or failed JMAP tests."""

from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
import xml.etree.ElementTree as ET

from verify_jmap_surefire import verify


class SurefireVerificationTest(unittest.TestCase):
    suite = "MemoryEmailGetMethodTest"

    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.name = f"com.linagora.tmail.james.{self.suite}"
        self.report = self.directory / f"TEST-{self.name}.xml"
        self.write_report()

    def write_report(self, name=None, tests=1, failures=0, errors=0, skipped=0, classname=None,
                     method="draftIsCreatedAndCanBeRead"):
        root = ET.Element("testsuite", {
            "name": name or self.name,
            "tests": str(tests),
            "failures": str(failures),
            "errors": str(errors),
            "skipped": str(skipped),
        })
        case = ET.SubElement(root, "testcase", {
            "classname": classname or self.name,
            "name": method,
        })
        if failures:
            ET.SubElement(case, "failure", {"message": "assertion failed"})
        if errors:
            ET.SubElement(case, "error", {"message": "exception"})
        if skipped:
            ET.SubElement(case, "skipped")
        ET.ElementTree(root).write(self.report, encoding="utf-8")

    def test_executed_contract_passes(self):
        self.assertEqual(len(verify(self.directory, [self.suite])), 1)

    def test_exact_qualified_extension_suite_executes(self):
        suite = "com.linagora.tmail.james.jmap.method.EmailSendQueueCleanupTest"
        self.name = suite
        self.report.unlink()
        self.report = self.directory / f"TEST-{suite}.xml"
        self.write_report(method="shouldReleaseMailAfterSuccessfulEnqueue")
        self.assertEqual(len(verify(self.directory, [suite],
                                    [f"{suite}:shouldReleaseMailAfterSuccessfulEnqueue"])), 1)

    def test_arbitrary_qualified_suite_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Unexpected JMAP test namespace"):
            verify(self.directory, ["org.unrelated.MailCleanupTest"])

    def test_missing_report_rejected(self):
        self.report.unlink()
        with self.assertRaisesRegex(ValueError, "Missing exact Surefire report"):
            verify(self.directory, [self.suite])

    def test_all_skipped_rejected(self):
        self.write_report(skipped=1)
        with self.assertRaisesRegex(ValueError, "ran no test methods"):
            verify(self.directory, [self.suite])

    def test_failure_rejected(self):
        self.write_report(failures=1)
        with self.assertRaisesRegex(ValueError, "failed"):
            verify(self.directory, [self.suite])

    def test_misidentified_suite_rejected(self):
        self.write_report(name="com.linagora.tmail.james.SomeOtherTest")
        with self.assertRaisesRegex(ValueError, "Wrong Surefire suite identity"):
            verify(self.directory, [self.suite])

    def test_wrong_class_rejected(self):
        self.write_report(classname="com.linagora.tmail.james.SomeOtherTest")
        with self.assertRaisesRegex(ValueError, "Unexpected test class"):
            verify(self.directory, [self.suite])

    def test_incorrect_test_count_rejected(self):
        self.write_report(tests=2)
        with self.assertRaisesRegex(ValueError, "No valid test cases"):
            verify(self.directory, [self.suite])

    def test_duplicate_requested_suite_rejected(self):
        with self.assertRaisesRegex(ValueError, "distinct JMAP suites"):
            verify(self.directory, [self.suite, self.suite])

    def test_required_contract_method_executed(self):
        self.write_report(method="draftIsCreatedAndCanBeRead(GuiceJamesServer)")
        self.assertEqual(len(verify(self.directory, [self.suite],
                                    [f"{self.suite}:draftIsCreatedAndCanBeRead"])), 1)

    def test_missing_required_contract_method_rejected(self):
        with self.assertRaisesRegex(ValueError, "missing executed contract methods"):
            verify(self.directory, [self.suite], [f"{self.suite}:unauthorizedAccountIsRejected"])

    def test_skipped_required_contract_method_rejected(self):
        self.write_report(skipped=1)
        with self.assertRaisesRegex(ValueError, "ran no test methods"):
            verify(self.directory, [self.suite], [f"{self.suite}:draftIsCreatedAndCanBeRead"])

    def test_invalid_or_duplicate_required_contract_method_rejected(self):
        with self.assertRaisesRegex(ValueError, "Invalid required JMAP method"):
            verify(self.directory, [self.suite], ["UnexpectedSuite:someMethod"])
        with self.assertRaisesRegex(ValueError, "distinct required JMAP methods"):
            verify(self.directory, [self.suite], [f"{self.suite}:draftIsCreatedAndCanBeRead"] * 2)


if __name__ == "__main__":
    unittest.main()
