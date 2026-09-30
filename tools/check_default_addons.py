#!/usr/bin/env python3
"""Assert the shape of StremioDefaultAddons.kt without needing a Kotlin toolchain.

Two things this protects, both cheap to state and easy to break by accident:
  1. Every entry is a bare `https://` manifest URL string. A typo'd scheme means the addon
     silently never loads, and the settings screen has no way to say why.
  2. There is no code path that turns a default on by itself. The switches live in preferences
     and start empty, so "add a line" can never mean "silently query a third party".

Run: python3 tools/check_default_addons.py [path/to/StremioDefaultAddons.kt]
Exit: 0 and prints "OK: N default addons, all off by default" on success.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_TARGET = REPO_ROOT / "Stremio/src/main/kotlin/com/stremio/StremioDefaultAddons.kt"

LIST_RE = re.compile(r"val\s+all\s*=\s*listOf\((.*?)\n\s*\)", re.DOTALL)
ENTRY_RE = re.compile(r'^\s*"(https://[^"]+)",?\s*$', re.MULTILINE)


def main() -> int:
    target = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_TARGET
    if not target.is_file():
        print(f"FAIL: no such file: {target}")
        return 1
    source = target.read_text(encoding="utf-8")

    match = LIST_RE.search(source)
    if not match:
        print("FAIL: could not find `val all = listOf(...)`")
        return 1

    body = match.group(1)
    entries = ENTRY_RE.findall(body)
    problems: list[str] = []

    # Every non-blank, non-comment line in the list body must be a bare URL string. This is what
    # catches someone adding a `DefaultAddon(...)` block or a stray expression back in.
    for raw in body.splitlines():
        stripped = raw.strip()
        if not stripped or stripped.startswith("//") or stripped.startswith("/*") or stripped == "*":
            continue
        if not ENTRY_RE.match(raw):
            problems.append(f"not a bare https URL string: {stripped[:70]}")

    if not entries:
        problems.append("parsed zero default addon URLs")

    for url in entries:
        if any(c.isspace() for c in url):
            problems.append(f"URL contains whitespace: {url[:60]}")
        if not url.endswith("/manifest.json"):
            problems.append(f"not a manifest.json URL: {url[:60]}")
        if "?" in url and "%3F" not in url.upper():
            problems.append(f"raw query string, use percent-encoding: {url[:60]}")

    dupes = {u for u in entries if entries.count(u) > 1}
    for url in sorted(dupes):
        problems.append(f"duplicate entry: {url[:60]}")

    # Nothing in this file may flip a switch on. The enabled set is read from preferences and
    # defaults to empty, so an explicit `= true` here would be the only way to break rule 2.
    if re.search(r"defaultEnabled|setDefaultEnabled|\btrue\b", source):
        problems.append("this file must not contain any switch that turns a default on")

    for problem in problems:
        print(f"FAIL: {problem}")
    if problems:
        return 1

    print(f"OK: {len(entries)} default addons, all off by default")
    for url in entries:
        print(f"  - {url[:96]}{'…' if len(url) > 96 else ''}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
