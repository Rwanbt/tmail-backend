import tempfile
import unittest
from pathlib import Path

from check_maven_dependencies import errors_in_pom, run


class MavenDependencyContractTests(unittest.TestCase):
    def test_duplicate_same_coordinates_rejected_before_build(self):
        with tempfile.TemporaryDirectory() as directory:
            pom = Path(directory) / "pom.xml"
            pom.write_text(
                "<project><dependencies>"
                "<dependency><groupId>g</groupId><artifactId>a</artifactId></dependency>"
                "<dependency><groupId>g</groupId><artifactId>a</artifactId><type>jar</type></dependency>"
                "</dependencies></project>",
                encoding="utf-8",
            )
            self.assertEqual(len(errors_in_pom(pom)), 1)
            self.assertEqual(run(Path(directory)), 1)

    def test_distinct_test_jar_classifier_and_profiles_are_not_false_positives(self):
        with tempfile.TemporaryDirectory() as directory:
            pom = Path(directory) / "pom.xml"
            pom.write_text(
                "<project><dependencies>"
                "<dependency><groupId>g</groupId><artifactId>a</artifactId></dependency>"
                "<dependency><groupId>g</groupId><artifactId>a</artifactId><type>test-jar</type></dependency>"
                "</dependencies><profiles><profile><dependencies>"
                "<dependency><groupId>g</groupId><artifactId>a</artifactId></dependency>"
                "</dependencies></profile></profiles></project>",
                encoding="utf-8",
            )
            self.assertEqual(errors_in_pom(pom), [])
            self.assertEqual(run(Path(directory)), 0)

    def test_missing_reactor_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(run(Path(directory)), 2)


if __name__ == "__main__":
    unittest.main()
