"""Compute the next patch version from pom.xml.

Reads the first <version> tag in pom.xml (the project version),
bumps the patch component, and prints GitHub Actions outputs:

    current=<current pom version>
    new=<bumped version>
    tag=v<new>-b<run number>

Usage:
    python3 next-version.py [run-number]
"""

import re
import sys


def main() -> None:
    run_number = sys.argv[1] if len(sys.argv) > 1 else "0"
    with open("pom.xml", encoding="utf-8") as fh:
        pom = fh.read()
    match = re.search(r"<version>([^<]+)</version>", pom)
    if match is None:
        raise SystemExit("no <version> tag found in pom.xml")
    current = match.group(1).strip()
    base = current.split("-")[0]
    nums = [int(part) for part in base.split(".")]
    while len(nums) < 3:
        nums.append(0)
    new = f"{nums[0]}.{nums[1]}.{nums[2] + 1}"
    print(f"current={current}")
    print(f"new={new}")
    print(f"tag=v{new}-b{run_number}")


if __name__ == "__main__":
    main()
