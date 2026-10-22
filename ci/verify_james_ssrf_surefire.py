#!/usr/bin/env python3
"""Verify the pinned Apache James SSRF policy suite really executed.

The SSRF address policy (private IPv4 and IPv6 ranges, IPv4-mapped IPv6 and
DNS rebinding) lives in the pinned Apache James submodule. Its JUnit suite is
not selected by the TMail `-Dtest=` filters, so a Maven SUCCESS could
silently mean "no SSRF test executed at all". This verifier fails closed when
the suite is missing, skipped, failing or reduced.
"""

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

SUITE = "org.apache.james.jmap.pushsubscription.SSRFValidatorTest"

REQUIRED_METHODS = (
    # Every private and reserved IPv4/IPv6 range the policy must refuse.
    "forbiddenReasonShouldRejectAddressesReachingTheLocalNetwork",
    # IPv4-mapped IPv6 held as an IPv6 address.
    "forbiddenReasonShouldRejectIPv4MappedLoopbackHeldAsAnIPv6Address",
    # A single forbidden address, and a host resolving to both a public and a
    # forbidden address.
    "validateShouldRejectAHostResolvingToASingleForbiddenAddress",
    "validateShouldRejectAHostResolvingToBothAPublicAndAForbiddenAddress",
    # The resolver used by the HTTP client is the one holding the policy.
    "addressResolverGroupShouldRejectForbiddenAddresses",
    "addressResolverGroupShouldRejectARebindingHost",
    "addressResolverGroupShouldResolvePublicAddresses",
)


def verify(report_directory: Path, required=REQUIRED_METHODS) -> list[str]:
    report = report_directory / f"TEST-{SUITE}.xml"
    if not report.is_file():
        raise ValueError(f"Missing exact Surefire report for {SUITE}: {report}")

    root = ET.parse(report).getroot()
    if root.tag != "testsuite" or root.get("name") != SUITE:
        raise ValueError(f"Wrong Surefire suite identity in {report}")

    cases = root.findall("testcase")
    if not cases or int(root.get("tests", "-1")) != len(cases):
        raise ValueError(f"No valid test cases in {report}")
    if any(case.get("classname") != SUITE for case in cases):
        raise ValueError(f"Unexpected test class in {report}")

    failures = sum(len(case.findall("failure")) for case in cases)
    errors = sum(len(case.findall("error")) for case in cases)
    skipped = sum(len(case.findall("skipped")) for case in cases)
    if failures or errors:
        raise ValueError(f"Apache James {SUITE} failed: {failures=}, {errors=}")

    executed = len(cases) - skipped
    if executed <= 0:
        raise ValueError(f"Apache James {SUITE} ran no test methods: {skipped=}")

    # JUnit 5 appends the argument signature to the method name.
    executed_names = {case.get("name").split("(", 1)[0]
                      for case in cases if not case.findall("skipped")}
    missing = set(required) - executed_names
    if missing:
        raise ValueError(f"Apache James {SUITE} missing executed SSRF policy methods: "
                         f"{', '.join(sorted(missing))}")

    return [f"PASS: {SUITE}: {executed} executed; {skipped} skipped"]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report_directory", type=Path)
    args = parser.parse_args()
    try:
        for summary in verify(args.report_directory):
            print(summary)
    except (ValueError, ET.ParseError, OSError) as error:
        print(f"Apache James SSRF verification FAILED: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
