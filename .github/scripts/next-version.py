"""Compute or set the project version from pom.xml.

The project <version> is anchored to the block following
<artifactId>human-verify</artifactId> so parent/modelVersion tags
can never be matched by accident.

Modes:
    python3 next-version.py <run-number> [--minor]
        Compute current -> next version (patch bump by default,
        minor bump with --minor), print results and append
        current=/new=/tag=/bump= to $GITHUB_OUTPUT when set.

    python3 next-version.py --write <new-version>
        Rewrite the project version in pom.xml (fails unless
        exactly one anchored match exists).
"""

import os
import re
import sys

PROJECT_VERSION_RE = re.compile(
    r"<artifactId>\s*human-verify\s*</artifactId>\s*<version>([^<]+)</version>"
)
VERSION_RE = re.compile(r"\d+\.\d+\.\d+")


def read_pom(path: str = "pom.xml") -> str:
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def current_version(pom: str) -> str:
    match = PROJECT_VERSION_RE.search(pom)
    if match is None:
        raise SystemExit("no project <version> anchored to human-verify found in pom.xml")
    return match.group(1).strip()


def next_patch(current: str) -> str:
    base = current.split("-")[0]
    if not VERSION_RE.fullmatch(base):
        raise SystemExit(f"unexpected project version format: {current!r}")
    major, minor, patch = (int(part) for part in base.split("."))
    return f"{major}.{minor}.{patch + 1}"


def next_minor(current: str) -> str:
    base = current.split("-")[0]
    if not VERSION_RE.fullmatch(base):
        raise SystemExit(f"unexpected project version format: {current!r}")
    major, minor, _ = (int(part) for part in base.split("."))
    return f"{major}.{minor + 1}.0"


def write_version(pom: str, new: str) -> str:
    if not VERSION_RE.fullmatch(new.split("-")[0]):
        raise SystemExit(f"refusing to write invalid version: {new!r}")
    updated, count = PROJECT_VERSION_RE.subn(
        lambda m: m.group(0).replace(m.group(1), new), pom, count=1
    )
    if count != 1:
        raise SystemExit("failed to update project version in pom.xml")
    return updated


def emit(name: str, value: str) -> None:
    print(f"{name}={value}")
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8") as fh:
            fh.write(f"{name}={value}\n")


def main() -> None:
    if len(sys.argv) == 3 and sys.argv[1] == "--write":
        new = sys.argv[2]
        updated = write_version(read_pom(), new)
        with open("pom.xml", "w", encoding="utf-8") as fh:
            fh.write(updated)
        print(f"pom version -> {new}")
        return
    if len(sys.argv) < 2 or sys.argv[1] == "--write":
        raise SystemExit("usage: next-version.py <run-number> [--minor] | next-version.py --write <new-version>")
    minor = len(sys.argv) > 2 and sys.argv[2] == "--minor"
    current = current_version(read_pom())
    new = next_minor(current) if minor else next_patch(current)
    emit("current", current)
    emit("new", new)
    emit("tag", f"v{new}-b{sys.argv[1]}")
    emit("bump", "minor" if minor else "patch")


if __name__ == "__main__":
    main()
