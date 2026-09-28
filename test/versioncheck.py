"""Fail if NunoDrama source changed without a plugin version bump.

CloudStream's PluginManager only triggers an auto-update when the integer at
the top of build.gradle.kts changes:

    "Integer over 0, any change of this will trigger an auto update."

So a commit that changes Kotlin but leaves the version alone builds fine,
passes CI, and never reaches anyone's device. That happened for eight
commits in a row, and every fix in them was invisible to the user. This
check compares the version against the last commit that touched it, and
fails when NunoDrama source has moved since then.

Run:  python3 test/versioncheck.py
"""
import re
import subprocess
import sys
from pathlib import Path

GRADLE = Path('NunoDrama/build.gradle.kts')
SOURCE_DIRS = ['NunoDrama/src']
MAX_UNBUMPED_COMMITS = 1  # the current, not-yet-released commit is fine


def sh(*args):
    return subprocess.run(args, capture_output=True, text=True, check=False).stdout.strip()


def current_version():
    text = GRADLE.read_text()
    m = re.search(r'^version\s*=\s*(\d+)\s*$', text, re.M)
    if not m:
        print(f'  cannot read a version from {GRADLE}')
        return None
    return int(m.group(1))


def paths_touched(commit):
    out = sh('git', 'show', '--name-only', '--format=', commit)
    return [line for line in out.splitlines() if line.strip()]


def main():
    if not GRADLE.exists():
        print('  no NunoDrama module in this checkout, nothing to check')
        return 0

    version = current_version()
    if version is None:
        return 1

    # The newest commit that changed the version number.
    last_bump = sh('git', 'log', '-n', '1', '--format=%H', '-S', 'version = %d' % version,
                   '--', str(GRADLE))
    if not last_bump:
        print(f'  no commit has ever set version = {version} in {GRADLE}')
        return 1

    newer = sh('git', 'log', '--format=%H', f'{last_bump}..HEAD').split()
    offenders = []
    for commit in newer:
        changed = paths_touched(commit)
        source = [p for p in changed
                  if any(p.startswith(d) for d in SOURCE_DIRS) or p == str(GRADLE)]
        if source:
            subject = sh('git', 'log', '-n', '1', '--format=%s', commit)
            offenders.append((commit[:7], subject, source[:3]))

    print(f'  version = {version}, last bumped in {last_bump[:7]}')
    if not offenders:
        print('  no NunoDrama source changes since the last version bump')
        return 0

    # The tip commit is what CI is about to publish, so one offender is expected.
    if len(offenders) <= MAX_UNBUMPED_COMMITS:
        print(f'  {len(offenders)} unbumped commit (the one being published now):')
        for short, subject, _ in offenders:
            print(f'    {short} {subject}')
        return 0

    print(f'  FAIL: {len(offenders)} commits changed NunoDrama without a version bump.')
    print('  CloudStream will not deliver them; users stay on the old build.')
    for short, subject, files in offenders:
        print(f'    {short} {subject}')
        for f in files:
            print(f'        {f}')
    return 1


sys.exit(main())
