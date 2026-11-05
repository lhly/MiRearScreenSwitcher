#!/usr/bin/env python3
"""Extract one tag's categorized, user-facing notes from CHANGELOG.md."""

import argparse
import json
from pathlib import Path
import re
import sys

GROUPS = (('新增', 'added'), ('优化', 'improved'), ('修复', 'fixed'))
GROUP_KEYS = {key for _, key in GROUPS}
MAX_ITEMS_PER_GROUP = 12
MAX_ITEM_LENGTH = 500
MAX_TOTAL_LENGTH = 6000
TAG_PATTERN = re.compile(r'^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$')
VERSION_HEADING = re.compile(r'^##\s+V?([0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?)(?=\s|[（(]|$)', re.IGNORECASE)
GROUP_HEADING = {title: key for title, key in GROUPS}


def validate_release_notes(value):
    if not isinstance(value, dict) or set(value) != GROUP_KEYS:
        raise ValueError('releaseNotes must contain exactly added, improved and fixed arrays.')
    result = {}
    total = 0
    for key in ('added', 'improved', 'fixed'):
        items = value[key]
        if not isinstance(items, list) or len(items) > MAX_ITEMS_PER_GROUP:
            raise ValueError(f'releaseNotes.{key} must be an array of at most {MAX_ITEMS_PER_GROUP} strings.')
        normalized = []
        for item in items:
            if not isinstance(item, str):
                raise ValueError(f'releaseNotes.{key} items must be strings.')
            text = item.strip()
            if (not text or len(text) > MAX_ITEM_LENGTH or '\n' in text or '\r' in text
                    or any(ord(char) < 32 for char in text)):
                raise ValueError(f'releaseNotes.{key} contains an invalid item.')
            total += len(text)
            normalized.append(text)
        result[key] = normalized
    if total > MAX_TOTAL_LENGTH:
        raise ValueError(f'releaseNotes total text must not exceed {MAX_TOTAL_LENGTH} characters.')
    if not any(result.values()):
        raise ValueError('releaseNotes must contain at least one feature entry.')
    return result


def extract_release_notes(changelog, tag):
    if not isinstance(tag, str) or not TAG_PATTERN.fullmatch(tag):
        raise ValueError('Release tag must be a stable or prerelease vMAJOR.MINOR.PATCH tag.')
    wanted = tag[1:].lower()
    lines = changelog.splitlines()
    starts = []
    for index, line in enumerate(lines):
        match = VERSION_HEADING.match(line)
        if match and match.group(1).lower() == wanted:
            starts.append(index)
    if len(starts) != 1:
        raise ValueError(f'Expected exactly one CHANGELOG section for {tag}; found {len(starts)}.')
    start = starts[0] + 1
    end = next((index for index in range(start, len(lines)) if lines[index].startswith('## ')), len(lines))
    section = lines[start:end]

    headings = []
    for index, line in enumerate(section):
        if line.startswith('### '):
            title = line[4:].strip()
            if title not in GROUP_HEADING or title in headings:
                raise ValueError(f'Unsupported or duplicate release-note category in {tag}: {title}')
            headings.append(title)
    if set(headings) != set(GROUP_HEADING):
        missing = sorted(set(GROUP_HEADING) - set(headings))
        raise ValueError(f'{tag} CHANGELOG section is missing categories: {", ".join(missing)}')

    result = {'added': [], 'improved': [], 'fixed': []}
    heading_indexes = [(index, GROUP_HEADING[line[4:].strip()]) for index, line in enumerate(section) if line.startswith('### ')]
    for position, (heading_index, key) in enumerate(heading_indexes):
        group_end = heading_indexes[position + 1][0] if position + 1 < len(heading_indexes) else len(section)
        for line in section[heading_index + 1:group_end]:
            stripped = line.strip()
            if not stripped:
                continue
            bullet = re.match(r'^[-*]\s+(.+)$', stripped)
            if not bullet:
                raise ValueError(f'Unexpected text outside a categorized bullet in {tag}: {stripped[:80]}')
            result[key].append(bullet.group(1).strip())
    return validate_release_notes(result)


def format_release_notes(notes):
    notes = validate_release_notes(notes)
    sections = []
    for title, key in GROUPS:
        if notes[key]:
            sections.append(f'## {title}\n\n' + '\n'.join(f'- {item}' for item in notes[key]))
    return '\n\n'.join(sections) + '\n'


def write_extraction(tag, changelog_path, output_dir):
    notes = extract_release_notes(Path(changelog_path).read_text(encoding='utf-8'), tag)
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / 'release-notes.md').write_text(format_release_notes(notes), encoding='utf-8')
    (output_dir / 'release-notes.json').write_text(
        json.dumps({'tag': tag, 'releaseNotes': notes}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return notes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('tag')
    parser.add_argument('--changelog', type=Path, default=Path(__file__).resolve().parents[1] / 'CHANGELOG.md')
    parser.add_argument('--output-dir', type=Path, default=Path('dist'))
    args = parser.parse_args()
    try:
        notes = write_extraction(args.tag, args.changelog, args.output_dir)
        print(f'Extracted categorized release notes for {args.tag}: '
              f'{sum(len(items) for items in notes.values())} feature entries.')
    except (OSError, UnicodeError, ValueError) as error:
        print(f'Release notes extraction failed: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
