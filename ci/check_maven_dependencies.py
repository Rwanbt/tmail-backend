#!/usr/bin/env python3
"""Fail on duplicate coordinates in the actual Maven reactor's dependency lists.

Maven 3/4 must load every POM before -pl can select the in-memory JMAP
subgraph. Duplicates in unrelated modules can therefore prevent JMAP tests
from starting. The check is stdlib-only and never downloads dependencies.
"""

from collections import Counter
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def tag_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def direct(node: ET.Element, name: str) -> str:
    for child in node:
        if tag_name(child.tag) == name:
            return (child.text or "").strip()
    return ""


def errors_in_pom(path: Path) -> list[str]:
    root = ET.parse(path).getroot()
    errors: list[str] = []
    for parent in root.iter():
        if tag_name(parent.tag) != "dependencies":
            continue
        coordinates = [
            (
                direct(dependency, "groupId"),
                direct(dependency, "artifactId"),
                direct(dependency, "type") or "jar",
                direct(dependency, "classifier"),
            )
            for dependency in parent
            if tag_name(dependency.tag) == "dependency"
        ]
        for coordinate, count in Counter(coordinates).items():
            if count > 1:
                errors.append(
                    f"{path}: {count} declarations of {':'.join(coordinate)} "
                    "in the same dependency list"
                )
    return errors


def run(root: Path) -> int:
    pom_files = sorted(root.rglob("pom.xml"))
    if not pom_files:
        print(f"No pom.xml under {root}: cannot establish valid Maven reactor", file=sys.stderr)
        return 2
    errors: list[str] = []
    for path in pom_files:
        errors.extend(errors_in_pom(path))
    if errors:
        print("Duplicate Maven coordinates detected:", file=sys.stderr)
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"Maven dependency coordinates unique in {len(pom_files)} POM files")
    return 0


if __name__ == "__main__":
    sys.exit(run(Path(sys.argv[1] if len(sys.argv) > 1 else "tmail-backend")))
