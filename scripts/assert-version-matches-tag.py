#!/usr/bin/env python3
"""Assert that a release tag agrees with the version in app/build.gradle.kts.

🔴 WHY THE TREE IS THE SOURCE OF TRUTH, AND THE TAG ONLY HAS TO MATCH IT

The obvious alternative is to inject the version INTO the build from the git
tag, so tagging alone decides what gets published and this script is
unnecessary. That was deliberately rejected, because it makes every LOCAL build
a downgrade:

  * `app/build.gradle.kts` would keep whatever versionCode it was last
    committed with — in practice 1, forever, since nothing would ever bump it;
  * the published OTA build would carry the tag's much higher code;
  * Android refuses to install a lower versionCode over a higher one
    (INSTALL_FAILED_VERSION_DOWNGRADE), so once the operator has taken one OTA,
    `adb install` of a locally built debug APK fails until they uninstall —
    and uninstalling destroys the on-device dictation history.

So the gradle file is the single source of truth, this script refuses a tag that
disagrees with it, and the release procedure is "bump the gradle file, commit,
then tag to match".

The versionCode convention is fixed by this script, not by taste:

    versionCode == major * 10000 + minor * 100 + patch

so 0.2.0 -> 200, 1.0.0 -> 10000, 1.2.3 -> 10203. It is monotonic in semver
order for any component below 100, which is the only property Android needs.

Usage:
    assert-version-matches-tag.py v0.2.0 [app/build.gradle.kts]

Exit codes:
    0  the tag matches the tree
    2  the tag is malformed, the gradle file is unreadable/unparseable, or the
       versions disagree
"""
from __future__ import annotations

import argparse
import os
import re
import sys

DEFAULT_GRADLE = os.path.join("app", "build.gradle.kts")

TAG_RE = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")

# Anchored on the assignment, and deliberately NOT tolerant of a leading `//`:
# this file's own rationale quotes version numbers in prose, and so does the
# gradle file's comment block, so a parser that reads a commented line would
# happily report on a historical value. Comments are stripped before matching.
VERSION_CODE_RE = re.compile(r"^\s*versionCode\s*=\s*(\d+)\s*$", re.MULTILINE)
VERSION_NAME_RE = re.compile(r"""^\s*versionName\s*=\s*["'](.+?)["']\s*$""", re.MULTILINE)


def strip_comments(text: str) -> str:
    """Drop `//` line comments and `/* */` blocks.

    Only whole-line `//` handling is needed for the assignments we match (they
    are anchored to the start of a line), but a `versionCode = 1` sitting inside
    a block comment would otherwise be read as live, which is the direction that
    makes a stale value look current.
    """
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    return "\n".join(line for line in text.splitlines() if not line.lstrip().startswith("//"))


def expected_code(major: int, minor: int, patch: int) -> int:
    return major * 10000 + minor * 100 + patch


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("tag", help="release tag, e.g. v0.2.0")
    ap.add_argument("gradle_file", nargs="?", default=DEFAULT_GRADLE)
    args = ap.parse_args(argv)

    m = TAG_RE.match(args.tag)
    if not m:
        print(
            f"error: tag {args.tag!r} is not of the form v<major>.<minor>.<patch> "
            "(e.g. v0.2.0). Pre-release and build-metadata suffixes are not "
            "supported, because versionCode has no room to encode them.",
            file=sys.stderr,
        )
        return 2
    major, minor, patch = (int(g) for g in m.groups())

    if not os.path.exists(args.gradle_file):
        print(
            f"error: {args.gradle_file} does not exist (cwd {os.getcwd()}). "
            "Refusing to report a pass on a file this script never read.",
            file=sys.stderr,
        )
        return 2

    with open(args.gradle_file, encoding="utf-8") as fh:
        source = strip_comments(fh.read())

    code_m = VERSION_CODE_RE.search(source)
    name_m = VERSION_NAME_RE.search(source)
    if not code_m or not name_m:
        missing = []
        if not code_m:
            missing.append("versionCode")
        if not name_m:
            missing.append("versionName")
        print(
            f"error: could not parse {', '.join(missing)} out of "
            f"{args.gradle_file}. The parser, not the version, is what is "
            "wrong — fix it rather than skipping this gate.",
            file=sys.stderr,
        )
        return 2

    actual_code = int(code_m.group(1))
    actual_name = name_m.group(1)
    want_name = f"{major}.{minor}.{patch}"
    want_code = expected_code(major, minor, patch)

    print(f"tag                  {args.tag}")
    print(f"versionName  tree    {actual_name}        expected {want_name}")
    print(f"versionCode  tree    {actual_code}        expected {want_code}")

    problems = []
    if actual_name != want_name:
        problems.append(
            f"versionName is {actual_name!r} but tag {args.tag} wants {want_name!r}"
        )
    if actual_code != want_code:
        problems.append(
            f"versionCode is {actual_code} but tag {args.tag} wants {want_code} "
            f"({major}*10000 + {minor}*100 + {patch})"
        )

    if problems:
        print("error: the tag and the tree disagree:", file=sys.stderr)
        for p in problems:
            print(f"  {p}", file=sys.stderr)
        print(
            "\nThe TREE is the source of truth. Fix it by bumping "
            f"{args.gradle_file} and re-tagging — never by injecting the tag's "
            "version into the build, which would leave every local build a "
            "downgrade. See this script's module docstring.",
            file=sys.stderr,
        )
        return 2

    print("OK: the release tag matches the version committed in the tree")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
