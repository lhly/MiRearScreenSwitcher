#!/usr/bin/env python3
"""Regression tests for exact-tag release feature-note extraction."""

import json
from pathlib import Path
import tempfile
import unittest

from release_notes import extract_release_notes, format_release_notes, validate_release_notes, write_extraction


ROOT = Path(__file__).resolve().parents[1]


class ReleaseNotesChecks(unittest.TestCase):
    def test_v362_reads_only_its_categorized_entries_and_keeps_applicability(self):
        notes = extract_release_notes((ROOT / 'CHANGELOG.md').read_text(encoding='utf-8'), 'v3.6.2')
        self.assertEqual(len(notes['added']), 2)
        self.assertEqual(len(notes['improved']), 3)
        self.assertEqual(len(notes['fixed']), 1)
        self.assertTrue(any('小米17 Pro Max' in item for item in notes['added']))
        self.assertTrue(any('物理直投' in item for item in notes['added']))
        self.assertFalse(any('小米18 Pro' in item for group in notes.values() for item in group))

    def test_unreleased_cannot_leak_into_a_tag_selection(self):
        notes = extract_release_notes((ROOT / 'CHANGELOG.md').read_text(encoding='utf-8'), 'v3.6.2')
        self.assertFalse(any('小米18 Pro' in item for item in notes['added']))
        with self.assertRaisesRegex(ValueError, 'found 0'):
            extract_release_notes((ROOT / 'CHANGELOG.md').read_text(encoding='utf-8'), 'v3.6.3')

    def test_tag_must_select_one_categorized_section(self):
        complete = '## V1.2.3\n### 新增\n- Add one\n### 优化\n### 修复\n'
        self.assertEqual(['Add one'], extract_release_notes(complete, 'v1.2.3')['added'])
        for changelog in (
            complete + '\n## v1.2.3 duplicate\n### 新增\n- duplicate\n### 优化\n### 修复\n',
            '## V1.2.3\n### 新增\n- Add one\n',
            '## V1.2.3\n### 新增\ntext without a bullet\n### 优化\n### 修复\n',
            '## V1.2.3\n### 新增\n### 优化\n### 修复\n',
        ):
            with self.subTest(changelog=changelog), self.assertRaises(ValueError):
                extract_release_notes(changelog, 'v1.2.3')

    def test_categories_are_typed_bounded_and_render_deterministically(self):
        valid = {'added': [' Add '], 'improved': [], 'fixed': ['Repair']}
        normalized = validate_release_notes(valid)
        self.assertEqual(['Add'], normalized['added'])
        self.assertEqual('## 新增\n\n- Add\n\n## 修复\n\n- Repair\n', format_release_notes(normalized))
        invalid = [
            {'added': 'wrong', 'improved': [], 'fixed': []},
            {'added': [1], 'improved': [], 'fixed': []},
            {'added': ['x'] * 13, 'improved': [], 'fixed': []},
            {'added': ['x' * 501], 'improved': [], 'fixed': []},
            {'added': ['x' * 500] * 12, 'improved': ['y' * 500] * 12, 'fixed': ['z' * 500] * 12},
            {'added': [], 'improved': [], 'fixed': []},
        ]
        for value in invalid:
            with self.subTest(value=str(value)[:60]), self.assertRaises(ValueError):
                validate_release_notes(value)

    def test_build_artifact_outputs_same_json_notes_and_markdown(self):
        changelog = '## V1.2.3\n### 新增\n- One\n### 优化\n- Two\n### 修复\n'
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'CHANGELOG.md'
            source.write_text(changelog, encoding='utf-8')
            notes = write_extraction('v1.2.3', source, directory)
            payload = json.loads((Path(directory) / 'release-notes.json').read_text(encoding='utf-8'))
            markdown = (Path(directory) / 'release-notes.md').read_text(encoding='utf-8')
        self.assertEqual('v1.2.3', payload['tag'])
        self.assertEqual(notes, payload['releaseNotes'])
        self.assertEqual(format_release_notes(notes), markdown)


if __name__ == '__main__':
    unittest.main(verbosity=2)
