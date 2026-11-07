#!/usr/bin/env python3
"""Reject local planning and development records in the Git index."""

from pathlib import PurePosixPath
import subprocess
import sys


def is_private_path(path):
    parts = [part.casefold() for part in PurePosixPath(path).parts]
    return (
        any(part in {".planning", "dev-docs"} for part in parts)
        or parts[-1] in {"task_plan.md", "findings.md", "progress.md"}
    )


def main():
    result = subprocess.run(["git", "ls-files", "--cached", "-z"], capture_output=True)
    if result.returncode:
        sys.stderr.write(result.stderr.decode(errors="replace"))
        return result.returncode
    blocked = [
        raw.decode(errors="surrogateescape")
        for raw in result.stdout.split(b"\0")
        if raw and is_private_path(raw.decode(errors="surrogateescape"))
    ]
    if blocked:
        print("提交已阻止：暂存区包含仅供本地使用的开发记录。", file=sys.stderr)
        for path in blocked:
            print(f"  {path!r}", file=sys.stderr)
        print("请将开发文档放在 dev-docs/、任务规划放在 .planning/，并取消这些文件的 Git 跟踪。", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
