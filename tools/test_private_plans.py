#!/usr/bin/env python3
"""Exercise the local-document commit guard with real temporary Git indexes."""

from pathlib import Path
import subprocess
import tempfile
import unittest

from check_private_plans import is_private_path

GUARD = Path(__file__).with_name("check_private_plans.py").resolve()


class PrivateDocumentsTest(unittest.TestCase):
    def test_public_paths(self):
        for path in ["docs/usage.md", "BUILDING.md", "AGENTS.md", "design-system/mrss-native/MASTER.md"]:
            with self.subTest(path=path):
                self.assertFalse(is_private_path(path))

    def test_private_paths(self):
        for path in ["dev-docs/design.md", "dev-docs/nested/notes.md", ".planning/task/a.md", "task_plan.md", "docs/findings.md", "nested/DEV-DOCS/log.md", "Progress.md"]:
            with self.subTest(path=path):
                self.assertTrue(is_private_path(path))

    def run_guard(self, names):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            (root / ".gitignore").write_text("/dev-docs/\n.planning/\n")
            for name in names:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("test\n")
            subprocess.run(["git", "-C", str(root), "add", "-f", "--", *names], check=True)
            return subprocess.run(["python3", str(GUARD)], cwd=root, capture_output=True, text=True)

    def test_public_index_passes(self):
        self.assertEqual(self.run_guard(["docs/usage.md", "BUILDING.md"]).returncode, 0)

    def test_force_added_development_document_fails(self):
        result = self.run_guard(["docs/usage.md", "dev-docs/design.md"])
        self.assertEqual(result.returncode, 1)
        self.assertIn("dev-docs/design.md", result.stderr)

    def test_planning_file_still_fails(self):
        self.assertEqual(self.run_guard([".planning/work/task_plan.md"]).returncode, 1)


if __name__ == "__main__":
    unittest.main()
