#!/usr/bin/env python3
"""Check the shared version and optional release tag before building."""
import argparse
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--tag')
args = parser.parse_args()
values = dict(line.split('=', 1) for line in (ROOT / 'version.properties').read_text().splitlines() if line and not line.startswith('#'))
version = values['versionName']
assert re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', version), 'Use MAJOR.MINOR.PATCH'
code = int(values['versionCode'])
assert 10000 <= code <= 2100000000, 'Use an increasing Android versionCode'
tag = 'v' + version
if args.tag:
    assert args.tag == tag, f'Tag {args.tag} differs from source version {tag}'
for app in ['io.github.ctxinf.cameraagent', 'io.github.ctxinf.cameraagent.legacy']:
    metadata = (ROOT / 'fdroid' / (app + '.yml')).read_text()
    assert f'versionName: {version}\n' in metadata
    assert f'versionCode: {code}\n' in metadata
    assert f'commit: {tag}\n' in metadata
    assert f'CurrentVersion: {version}\n' in metadata
    assert f'CurrentVersionCode: {code}\n' in metadata
    for locale in ['en-US', 'zh-CN']:
        assert (ROOT / 'fastlane/metadata/android' / locale / 'changelogs' / f'{code}.txt').is_file()
print(f'Version {version}, code {code}, release ref {tag}: consistent')
