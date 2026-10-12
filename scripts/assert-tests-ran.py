#!/usr/bin/env python3
"""Assert that the unit-test suite actually RAN, and was green.

Why this exists rather than trusting the Gradle task's exit status: a build can
report success having executed zero tests (a bad `-Ptests` filter, a source set
that stopped being compiled, a task that was UP-TO-DATE from a stale cache), and
a gate that is green because it measured nothing is worse than no gate — it
trains everyone to stop looking. So the JUnit XML is the authority: this reads
the per-class result files, and fails if there are none, if the parsed total is
zero, or if anything failed or errored.

Usage:
    assert-tests-ran.py [--min N] [results-dir]

Default results dir: app/build/test-results/testDebugUnitTest
Exit codes: 0 green, 1 a failure/error, 2 nothing ran (or nothing parsed).
"""
from __future__ import annotations

import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET

DEFAULT_DIR = os.path.join("app", "build", "test-results", "testDebugUnitTest")


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("results_dir", nargs="?", default=DEFAULT_DIR)
    ap.add_argument(
        "--min",
        type=int,
        default=1,
        help="fail if fewer than this many tests ran (default 1)",
    )
    args = ap.parse_args(argv)

    files = sorted(glob.glob(os.path.join(args.results_dir, "TEST-*.xml")))
    if not files:
        print(
            f"error: no TEST-*.xml under {args.results_dir} -- zero tests executed",
            file=sys.stderr,
        )
        return 2

    tests = failures = errors = skipped = 0
    bad: list[str] = []
    for path in files:
        root = ET.parse(path).getroot()
        tests += int(root.get("tests", 0))
        failures += int(root.get("failures", 0))
        errors += int(root.get("errors", 0))
        skipped += int(root.get("skipped", 0))
        cls = os.path.basename(path)[len("TEST-"):-len(".xml")]
        print(
            f"  {cls}: tests={root.get('tests')} "
            f"failures={root.get('failures')} errors={root.get('errors')} "
            f"skipped={root.get('skipped')}"
        )
        for case in root.iter("testcase"):
            for child in case:
                if child.tag in ("failure", "error"):
                    bad.append(f"{case.get('classname')}.{case.get('name')}")

    print(
        f"TOTAL tests={tests} failures={failures} errors={errors} "
        f"skipped={skipped} across {len(files)} classes"
    )

    if tests == 0:
        print(
            "error: the XML reports 0 tests -- the suite is wired to nothing",
            file=sys.stderr,
        )
        return 2
    if tests < args.min:
        print(
            f"error: only {tests} tests ran, expected at least {args.min}",
            file=sys.stderr,
        )
        return 2
    if bad:
        print("error: failing tests:", file=sys.stderr)
        for name in bad:
            print(f"  {name}", file=sys.stderr)
        return 1
    if failures or errors:
        # Counts disagree with the per-case scan: report rather than pass.
        print(
            f"error: {failures} failures / {errors} errors in the suite totals",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
