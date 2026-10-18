#!/usr/bin/env python3
"""Verify real Apache James JMAP contract test execution from Maven Surefire XML."""

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def verify(report_directory: Path, suites: list[str]) -> list[str]:
    if not suites or len(suites) != len(set(suites)):
        raise ValueError("Specify distinct JMAP suites")

    summaries = []
    for suite in suites:
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
        summaries.append(f"PASS: {suite}: {executed} executed; {skipped} skipped")
    return summaries


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report_directory", type=Path)
    parser.add_argument("suites", nargs="+")
    args = parser.parse_args()
    try:
        for summary in verify(args.report_directory, args.suites):
            print(summary)
    except (ValueError, ET.ParseError, OSError) as error:
        print(f"JMAP Surefire verification FAILED: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
