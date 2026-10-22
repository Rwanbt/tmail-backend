"""Contract tests for the fork unit test enumeration and verification."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import fork_unit_tests  # noqa: E402


class ForkUnitTestsEnumerationTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(__file__).resolve().parent.parent

    def test_enumerate_modules_returns_the_fork_modules(self):
        modules = fork_unit_tests.enumerate_modules(self.root)
        self.assertTrue(modules, "no fork unit test module was found")
        for module in modules:
            self.assertTrue(module.startswith(":"), module)
            self.assertNotIn(" ", module)

    def test_enumerate_modules_excludes_the_integration_test_modules(self):
        modules = fork_unit_tests.enumerate_modules(self.root)
        for module in modules:
            self.assertNotIn("integration-tests", module)
            self.assertNotIn("deployment-tests", module)

    def test_enumerate_classes_returns_concrete_test_classes(self):
        classes = fork_unit_tests.enumerate_classes(self.root)
        self.assertTrue(classes, "no fork unit test class was found")
        self.assertEqual(len(classes), len(set(classes)))
        for name in classes:
            self.assertTrue(name.endswith("Test") or name.endswith("Tests"), name)

    def test_enumerate_classes_ignores_the_integration_test_modules(self):
        classes = fork_unit_tests.enumerate_classes(self.root)
        # The integration contract classes are exercised by their own workflows
        # and must not be claimed by the unit regression job.
        self.assertNotIn("MemoryEmailGetMethodTest", classes)
        self.assertNotIn("MemoryTeamMailboxSmtpTest", classes)

    def test_excluded_classes_are_declared_with_a_reason(self):
        excluded = fork_unit_tests.excluded_classes(self.root)
        self.assertTrue(excluded, "no excluded class was found in the tree")
        for name, reason in excluded:
            self.assertIn(name, fork_unit_tests.NON_UNIT_TESTS)
            self.assertTrue(reason.strip(), f"{name} is excluded without a reason")

    def test_the_only_excluded_class_is_the_container_one(self):
        excluded = fork_unit_tests.excluded_classes(self.root)
        self.assertEqual(
            [name for name, _ in excluded],
            ["MigrationMirroringTest"],
            "a new class was excluded from the unit regression job",
        )

    def test_an_excluded_class_is_not_part_of_the_expected_classes(self):
        excluded = {name for name, _ in fork_unit_tests.excluded_classes(self.root)}
        classes = fork_unit_tests.enumerate_classes(self.root)
        for name in excluded:
            self.assertIn(name, classes)


class ForkUnitTestsVerificationTest(unittest.TestCase):
    def _report(self, directory: Path, suite: str, executed: int, skipped: int):
        directory.mkdir(parents=True, exist_ok=True)
        cases = "".join(
            "<testcase name='test%d' classname='%s'/>" % (index, suite)
            for index in range(executed)
        ) + "".join(
            "<testcase name='skip%d' classname='%s'><skipped/></testcase>" % (index, suite)
            for index in range(skipped)
        )
        (directory / ("TEST-%s.xml" % suite)).write_text(
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<testsuite name="%s" tests="%d" failures="0" errors="0" skipped="%d">%s</testsuite>'
            % (suite, executed + skipped, skipped, cases),
            encoding="utf-8",
        )

    def test_verify_accepts_a_class_that_executed(self):
        import tempfile

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self._report(root, "com.linagora.tmail.FooTest", executed=2, skipped=0)
            summaries = fork_unit_tests.verify(root, ["FooTest"])
            self.assertTrue(any("PASS" in summary for summary in summaries))

    def test_verify_rejects_a_class_without_any_report(self):
        import tempfile

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self._report(root, "com.linagora.tmail.FooTest", executed=1, skipped=0)
            with self.assertRaises(ValueError):
                fork_unit_tests.verify(root, ["FooTest", "MissingTest"])

    def test_verify_rejects_a_class_whose_tests_are_all_skipped(self):
        import tempfile

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self._report(root, "com.linagora.tmail.FooTest", executed=0, skipped=3)
            with self.assertRaises(ValueError):
                fork_unit_tests.verify(root, ["FooTest"])

    def test_verify_rejects_an_empty_expectation(self):
        import tempfile

        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(ValueError):
                fork_unit_tests.verify(Path(temporary), [])


if __name__ == "__main__":
    unittest.main()
