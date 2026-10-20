#!/usr/bin/env python3
"""Verify real Apache James JMAP contract test execution from Maven Surefire XML."""

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def verify(report_directory: Path, suites: list[str], required: list[str] | None = None) -> list[str]:
    if not suites or len(suites) != len(set(suites)):
        raise ValueError("Specify distinct JMAP suites")
    required = required or []
    if len(required) != len(set(required)):
        raise ValueError("Specify distinct required JMAP methods")
    required_methods: dict[str, set[str]] = {suite: set() for suite in suites}
    for entry in required:
        suite, separator, method = entry.partition(":")
        if not separator or suite not in required_methods or not method or not method.isidentifier():
            raise ValueError(f"Invalid required JMAP method: {entry}")
        required_methods[suite].add(method)

    summaries = []
    for suite in suites:
        # Integration contracts use the direct james package. Unit tests in
        # extensions use nested packages; require their exact qualified name
        # instead of guessing an incorrect Surefire report filename.
        if "." in suite:
            if not suite.startswith("com.linagora.tmail.james."):
                raise ValueError(f"Unexpected JMAP test namespace: {suite}")
            expected_name = suite
        else:
            expected_name = f"com.linagora.tmail.james.{suite}"
        report = report_directory / f"TEST-{expected_name}.xml"
        if not report.is_file():
            raise ValueError(f"Missing exact Surefire report for {suite}: {report}")

        root = ET.parse(report).getroot()
        if root.tag != "testsuite" or root.get("name") != expected_name:
            raise ValueError(f"Wrong Surefire suite identity in {report}")

        cases = root.findall("testcase")
        if not cases or int(root.get("tests", "-1")) != len(cases):
            raise ValueError(f"No valid test cases in {report}")
        if any(case.get("classname") != expected_name for case in cases):
            raise ValueError(f"Unexpected test class in {report}")
        if any(not case.get("name") for case in cases):
            raise ValueError(f"Missing JMAP test method name in {report}")

        failures = sum(len(case.findall("failure")) for case in cases)
        errors = sum(len(case.findall("error")) for case in cases)
        skipped = sum(len(case.findall("skipped")) for case in cases)
        counts = {"failures": failures, "errors": errors, "skipped": skipped}
        if any(int(root.get(key, "-1")) != value for key, value in counts.items()):
            raise ValueError(f"Inconsistent Surefire counters in {report}: {counts}")
        if failures or errors:
            raise ValueError(f"JMAP {suite} failed: {failures=}, {errors=}")
        executed = len(cases) - skipped
        if executed <= 0:
            raise ValueError(f"JMAP {suite} ran no test methods: {skipped=}")
        # JUnit 5 may include the argument signature in Surefire's method name.
        executed_names = {case.get("name").split("(", 1)[0]
                          for case in cases if not case.findall("skipped")}
        missing = required_methods[suite] - executed_names
        if missing:
            raise ValueError(f"JMAP {suite} missing executed contract methods: {', '.join(sorted(missing))}")
        summaries.append(f"PASS: {suite}: {executed} executed; {skipped} skipped")
    return summaries


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report_directory", type=Path)
    parser.add_argument("suites", nargs="+")
    parser.add_argument("--require", action="append", default=[], metavar="SUITE:METHOD",
                        help="Require a named JUnit method to execute without a skip")
    args = parser.parse_args()
    try:
        for summary in verify(args.report_directory, args.suites, args.require):
            print(summary)
    except (ValueError, ET.ParseError, OSError) as error:
        print(f"JMAP Surefire verification FAILED: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
