#!/usr/bin/env python3
"""Check locale coverage and Android string format arguments in both apps."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = (
    ROOT / 'android-8.0-camera-agent/app/src/main/res',
    ROOT / 'android-4.0-camera-agent/android/app/src/main/res',
)
LOCALES = ('values', 'values-zh-rCN', 'values-zh-rTW', 'values-ko', 'values-ja',
           'values-es', 'values-fr', 'values-de', 'values-nl', 'values-ar',
           'values-vi', 'values-th', 'values-ms')
FORMAT = re.compile(r'%(?:\d+\$)?[dsf]|%%')


def strings(path):
    entries = ET.parse(path).getroot().findall('string')
    result = {entry.attrib['name']: ''.join(entry.itertext()) for entry in entries}
    if len(result) != len(entries):
        raise ValueError(f'{path}: duplicate string names')
    return result


def main():
    errors = []
    for res in RESOURCES:
        base = strings(res / 'values/strings.xml')
        for locale in LOCALES:
            path = res / locale / 'strings.xml'
            try:
                translated = strings(path)
                if base.keys() != translated.keys():
                    errors.append(f'{path}: missing or extra keys: {base.keys() ^ translated.keys()}')
                for key in base.keys() & translated.keys():
                    if sorted(FORMAT.findall(base[key])) != sorted(FORMAT.findall(translated[key])):
                        errors.append(f'{path}: format arguments differ for {key}')
                    if not translated[key].strip():
                        errors.append(f'{path}: empty translation for {key}')
            except (OSError, ET.ParseError, ValueError) as exc:
                errors.append(str(exc))
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1
    print(f'Validated {len(LOCALES)} language resources in both apps.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
