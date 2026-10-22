#!/usr/bin/env python3
"""Enumerate and verify the fork's own unit test classes.

The GitHub Actions workflows of this repository exercise named JMAP and SMTP
integration contracts with an explicit `-Dtest=` filter. That filter applies to
every module of the reactor and the workflows pass
`-Dsurefire.failIfNoSpecifiedTests=false`, so the unit tests of the fork's own
modules were never executed by the CI: the reactor built them and silently ran
nothing.

This script closes that gap without hardcoding any test name:

- `list` prints the Maven module selectors and the expected unit test classes,
  computed from the source tree, so a new test class is picked up
  automatically;
- `verify` reads the Surefire XML reports and proves that every expected class
  really executed at least one non-skipped test case. A class whose methods are
  all disabled proves nothing and is reported as a failure.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

# The fork's own reactor. The upstream Apache James tree is the `james-project`
# submodule and is not part of it.
FORK_ROOT = "tmail-backend"

# Modules whose tests are integration tests: they need real servers, databases
# or containers and are exercised by the dedicated contract workflows.
INTEGRATION_PREFIXES = (
    "tmail-backend/integration-tests/",
    "tmail-backend/deployment-tests/",
)

TEST_FILE = re.compile(r"(Test|Tests|Spec)\.(java|scala)$")
ABSTRACT_CLASS = re.compile(r"\babstract\s+(?:final\s+)?class\b")
MODULE_POM = "pom.xml"

# Classes that live under src/test but are not unit tests: they need a
# container image or a running backend, so they cannot run in the unit
# regression job. They are excluded with their reason, the exclusion is
# reported in the job summary, and the contract test asserts that this list
# stays exactly as declared: nothing can be silently dropped.
NON_UNIT_TESTS = {
    "MigrationMirroringTest": (
        "starts the previous backend from the Jib built image tar "
        "(tmail-backend/apps/memory/target/jib-image.tar) and needs a Docker "
        "daemon; it is a container integration test, exercised by the "
        "Apache James Jenkins pipeline"
    ),
    "RabbitMQAndRedisEventBusTest": (
        "concrete implementation of the RabbitMQ/Redis event bus contract; "
        "requires live RabbitMQ and Redis (Testcontainers) and exercises "
        "dead-letter/invalid-payload paths that stall the unit runner "
        "(EventBusTestFixture$TestEventSerializer.asEvents: Invalid format: "
        "BAD_PAYLOAD!); covered by the distributed contract workflows"
    ),
    "RabbitMQAndRedisEventBusWithKvrocksTest": (
        "concrete implementation of the RabbitMQ/Redis event bus contract "
        "against Kvrocks; requires live RabbitMQ, Redis and Kvrocks "
        "(Testcontainers) and the same invalid-payload dead-letter path; "
        "covered by the distributed contract workflows"
    ),
    "RabbitMQAndRedisEventBusWithKvrocksSentinelTest": (
        "concrete implementation of the RabbitMQ/Redis event bus contract "
        "against Kvrocks in sentinel mode; requires live RabbitMQ, Redis and "
        "Kvrocks (Testcontainers) and the same invalid-payload dead-letter "
        "path; covered by the distributed contract workflows"
    ),
    "RabbitMQAndRedisEventBusWithPartitioningTest": (
        "concrete implementation of the RabbitMQ/Redis event bus contract "
        "with partitioning; requires live RabbitMQ and Redis (Testcontainers) "
        "and the same invalid-payload dead-letter path; covered by the "
        "distributed contract workflows"
    ),
    "RabbitMQAndRedisEventBusWithRedisSentinelTest": (
        "concrete implementation of the RabbitMQ/Redis event bus contract "
        "against Redis sentinel; requires live RabbitMQ and Redis "
        "(Testcontainers) and the same invalid-payload dead-letter path; "
        "covered by the distributed contract workflows"
    ),
    "RedisClusterUnauthenticatedBlobDownloadTokenRepositoryTest": (
        "exercises a live Redis cluster through Testcontainers; the cluster "
        "is not available in the unit regression runner (CLUSTERDOWN), so "
        "the test cannot pass there; covered by the Redis integration suites"
    ),
}


def _read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def _module_selector(pom: Path) -> str | None:
    """Return the Maven module selector of a module pom, or None.

    The artifactId of a child pom must be read from the project element, not
    from the parent block: a child inherits the parent artifactId first.
    """
    try:
        root = ET.parse(pom).getroot()
    except (ET.ParseError, OSError):
        return None
    namespace = root.tag.split("}")[0] + "}" if root.tag.startswith("{") else ""
    artifact = root.find(f"{namespace}artifactId")
    if artifact is None or not (artifact.text or "").strip():
        return None
    return f":{artifact.text.strip()}"


def enumerate_modules(root: Path) -> list[str]:
    """Maven selectors of the fork modules that own unit tests."""
    selectors: set[str] = set()
    for pom in sorted(root.glob(f"{FORK_ROOT}/**/{MODULE_POM}")):
        relative = pom.parent.relative_to(root).as_posix()
        if any(relative.startswith(prefix) for prefix in INTEGRATION_PREFIXES):
            continue
        test_dir = pom.parent / "src" / "test"
        if not test_dir.is_dir():
            continue
        if not any(test_dir.rglob("*Test.java")):
            continue
        selector = _module_selector(pom)
        if selector:
            selectors.add(selector)
    return sorted(selectors)


def enumerate_classes(root: Path) -> list[str]:
    """Simple names of the fork unit test classes, abstract ones excluded."""
    names: set[str] = set()
    for module in root.glob(f"{FORK_ROOT}/**/src/test"):
        relative = module.relative_to(root).as_posix()
        if any(relative.startswith(prefix) for prefix in INTEGRATION_PREFIXES):
            continue
        for candidate in list(module.rglob("*Test.java")) + list(
            module.rglob("*Test.scala")
        ):
            if not TEST_FILE.search(candidate.name):
                continue
            if ABSTRACT_CLASS.search(_read(candidate)):
                continue
            names.add(candidate.stem)
    return sorted(names)


def excluded_classes(root: Path) -> list[tuple[str, str]]:
    """The declared non unit test classes that are present in the tree."""
    present = set(enumerate_classes(root))
    return [
        (name, reason)
        for name, reason in sorted(NON_UNIT_TESTS.items())
        if name in present
    ]


def collect_reports(reports_root: Path) -> dict[str, tuple[int, int]]:
    """Map a simple test class name to (executed, skipped) from Surefire XML."""
    collected: dict[str, tuple[int, int]] = {}
    for report in reports_root.rglob("TEST-*.xml"):
        try:
            root = ET.parse(report).getroot()
        except (ET.ParseError, OSError):
            continue
        suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite"))
        for suite in suites:
            name = suite.get("name") or ""
            simple = name.rsplit(".", 1)[-1]
            executed = 0
            skipped = 0
            for case in suite.findall("testcase"):
                if case.find("skipped") is not None:
                    skipped += 1
                else:
                    executed += 1
            previous = collected.get(simple, (0, 0))
            collected[simple] = (previous[0] + executed, previous[1] + skipped)
    return collected


def class_modules(root: Path) -> dict[str, str]:
    """Map each unit test class to the module directory that owns it."""
    owners: dict[str, str] = {}
    for module in root.glob(f"{FORK_ROOT}/**/src/test"):
        relative = module.relative_to(root).as_posix()
        if any(relative.startswith(prefix) for prefix in INTEGRATION_PREFIXES):
            continue
        for candidate in list(module.rglob("*Test.java")) + list(
            module.rglob("*Test.scala")
        ):
            if not TEST_FILE.search(candidate.name):
                continue
            owners.setdefault(candidate.stem, relative[: -len("/src/test")])
    return owners


def per_module(
    owners: dict[str, str], expected: list[str], collected: dict[str, tuple[int, int]]
) -> list[str]:
    """Per module, the expected class count against the count with a report."""
    counters: dict[str, list[int]] = {}
    for name in expected:
        module = owners.get(name, "unknown module")
        entry = counters.setdefault(module, [0, 0])
        entry[0] += 1
        if name in collected:
            entry[1] += 1
    return [
        f"{module}: {expected_count} expected, {reported_count} with a report"
        for module, (expected_count, reported_count) in counters.items()
        if expected_count != reported_count
    ]


def verify(
    reports_root: Path, expected: list[str], owners: dict[str, str] | None = None
) -> list[str]:
    """Prove every expected class executed at least one non-skipped test case."""
    if not expected:
        raise ValueError("No expected unit test class was found in the source tree")
    collected = collect_reports(reports_root)
    owners = owners or {}

    def describe(name: str) -> str:
        module = owners.get(name)
        return f"{name} ({module})" if module else name

    missing: list[str] = []
    empty: list[str] = []
    for name in expected:
        executed, skipped = collected.get(name, (0, 0))
        if name not in collected:
            missing.append(describe(name))
        elif executed == 0:
            empty.append(describe(name))
    if missing:
        raise ValueError(
            "These unit test classes produced no Surefire report: "
            + ", ".join(sorted(missing)[:20])
            + (" ..." if len(missing) > 20 else "")
            + "; per module, expected classes versus classes with a report: "
            + "; ".join(sorted(per_module(owners, expected, collected)))
        )
    if empty:
        raise ValueError(
            "These unit test classes executed no test case (all skipped): "
            + ", ".join(sorted(empty)[:20])
            + (" ..." if len(empty) > 20 else "")
            + "; per module, expected classes versus classes with a report: "
            + "; ".join(sorted(per_module(owners, expected, collected)))
        )
    total_executed = sum(collected[name][0] for name in expected)
    total_skipped = sum(collected[name][1] for name in expected)
    return [
        "PASS: {0} fork unit test classes executed; {1} test cases executed; "
        "{2} skipped".format(len(expected), total_executed, total_skipped)
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["list", "verify"])
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--reports", type=Path, default=None)
    parser.add_argument("--expected", type=Path, default=None)
    args = parser.parse_args()

    root = args.root.resolve()
    if args.command == "list":
        modules = enumerate_modules(root)
        classes = enumerate_classes(root)
        excluded = excluded_classes(root)
        excluded_names = {name for name, _ in excluded}
        classes = [name for name in classes if name not in excluded_names]
        if not modules or not classes:
            print("No fork unit test module was found", file=sys.stderr)
            return 1
        print("MODULES=" + ",".join(modules))
        print("CLASSES=" + ",".join(classes))
        for name, reason in excluded:
            print(f"EXCLUDED={name}: {reason}")
        return 0

    if not args.reports or not args.expected:
        print("--reports and --expected are required by verify", file=sys.stderr)
        return 2
    expected = [line.strip() for line in args.expected.read_text(encoding="utf-8").splitlines() if line.strip()]
    try:
        owners = class_modules(root)
        for summary in verify(args.reports.resolve(), expected, owners):
            print(summary)
    except (ValueError, ET.ParseError, OSError) as error:
        print(f"Fork unit test verification FAILED: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
