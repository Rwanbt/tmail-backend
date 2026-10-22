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

    def test_enumerate_classes_uses_declared_scala_classes_and_ignores_empty_java(self):
        import tempfile

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            module = root / "tmail-backend" / "example"
            tests = module / "src" / "test"
            scala = tests / "scala" / "example" / "RespondDetailsTest.scala"
            java = tests / "java" / "example" / "EmptyPlaceholderTest.java"
            scala.parent.mkdir(parents=True)
            java.parent.mkdir(parents=True)
            (module / "pom.xml").write_text(
                "<project><artifactId>example</artifactId></project>",
                encoding="utf-8",
            )
            scala.write_text(
                """package example
trait RespondDetailsContract
class DownloadAllRespondDetailsTest extends RespondDetailsContract
class UnauthenticatedBlobAccessRespondDetailsTest extends RespondDetailsContract
class RedisPlaygroundTest {
  class StringsTest
}
""",
                encoding="utf-8",
            )
            java.write_text("// reserved placeholder; no test class yet\n", encoding="utf-8")

            self.assertEqual(
                fork_unit_tests.enumerate_classes(root),
                [
                    "DownloadAllRespondDetailsTest",
                    "RedisPlaygroundTest",
                    "UnauthenticatedBlobAccessRespondDetailsTest",
                ],
            )
            self.assertNotIn("StringsTest", fork_unit_tests.enumerate_classes(root))
            self.assertEqual(fork_unit_tests.enumerate_modules(root), [":example"])

    def test_enumerate_classes_ignores_nested_classes_of_abstract_contracts(self):
        import tempfile

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            module = root / "tmail-backend" / "example"
            tests = module / "src" / "test" / "java" / "example"
            tests.mkdir(parents=True)
            (module / "pom.xml").write_text(
                "<project><artifactId>example</artifactId></project>",
                encoding="utf-8",
            )
            scala_tests = module / "src" / "test" / "scala" / "example"
            scala_tests.mkdir(parents=True)
            (scala_tests / "WithoutBodyTest.scala").write_text(
                """package example
trait Contract
abstract class EmptyContractTest extends Contract
class ConcreteScalaSuiteTest extends Contract {}
""",
                encoding="utf-8",
            )
            (tests / "ContractTest.java").write_text(
                """package example;
abstract class AbstractEventBusContractTest {
    @Nested class ConcurrentTest { }
}
class ConcreteUnitTest { }
""",
                encoding="utf-8",
            )

            self.assertEqual(
                fork_unit_tests.enumerate_classes(root),
                ["ConcreteScalaSuiteTest", "ConcreteUnitTest"],
            )

    def test_real_scala_serializer_suite_is_enumerated(self):
        classes = fork_unit_tests.enumerate_classes(self.root)
        self.assertIn("AiBotSerializerTest", classes)
        self.assertIn("DownloadAllRespondDetailsTest", classes)
        self.assertIn("UnauthenticatedBlobAccessRespondDetailsTest", classes)
        self.assertNotIn("RespondDetailsTest", classes)
        self.assertNotIn("DeleteEncryptedProjectionHookTest", classes)

    def test_excluded_classes_are_declared_with_a_reason(self):
        excluded = fork_unit_tests.excluded_classes(self.root)
        self.assertTrue(excluded, "no excluded class was found in the tree")
        for name, reason in excluded:
            self.assertIn(name, fork_unit_tests.NON_UNIT_TESTS)
            self.assertTrue(reason.strip(), f"{name} is excluded without a reason")

    def test_excluded_classes_are_the_documented_non_unit_ones(self):
        excluded = fork_unit_tests.excluded_classes(self.root)
        self.assertEqual(
            [name for name, _ in excluded],
            [
                "MigrationMirroringTest",
                "RabbitMQAndRedisEventBusTest",
                "RabbitMQAndRedisEventBusWithKvrocksSentinelTest",
                "RabbitMQAndRedisEventBusWithKvrocksTest",
                "RabbitMQAndRedisEventBusWithPartitioningTest",
                "RabbitMQAndRedisEventBusWithRedisSentinelTest",
                "RedisClusterUnauthenticatedBlobDownloadTokenRepositoryTest",
                "RedisPlaygroundTest",
            ],
            "a new class was excluded from the unit regression job",
        )

    def test_an_excluded_class_is_not_part_of_the_expected_classes(self):
        excluded = {name for name, _ in fork_unit_tests.excluded_classes(self.root)}
        classes = fork_unit_tests.enumerate_classes(self.root)
        for name in excluded:
            self.assertIn(name, classes)


class ForkUnitTestsVerificationTest(unittest.TestCase):
    def _report(
        self,
        directory: Path,
        suite: str,
        executed: int,
        skipped: int,
        testcase_classname: str | None = None,
    ):
        directory.mkdir(parents=True, exist_ok=True)
        classname = testcase_classname or suite
        cases = "".join(
            "<testcase name='test%d' classname='%s'/>" % (index, classname)
            for index in range(executed)
        ) + "".join(
            "<testcase name='skip%d' classname='%s'><skipped/></testcase>" % (index, classname)
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

    def test_verify_maps_nested_junit_cases_to_the_declared_classes(self):
        import tempfile

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self._report(
                root,
                "com.linagora.tmail.OuterTest",
                executed=1,
                skipped=0,
                testcase_classname="com.linagora.tmail.OuterTest$NestedTest",
            )

            summaries = fork_unit_tests.verify(
                root, ["OuterTest", "NestedTest"]
            )

            self.assertEqual(
                summaries,
                [
                    "PASS: 2 fork unit test classes executed; 1 test cases executed; "
                    "0 skipped"
                ],
            )

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
