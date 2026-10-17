#!/usr/bin/env python3
"""Reject local planning files in the Git index before committing."""

from pathlib import PurePosixPath
import subprocess
import sys


def main():
    result = subprocess.run(["git", "ls-files", "--cached", "-z"], capture_output=True)
    if result.returncode:
        sys.stderr.write(result.stderr.decode(errors="replace"))
        return result.returncode
    private_names = {"task_plan.md", "findings.md", "progress.md"}
    blocked = []
    for raw in result.stdout.split(b"\0"):
        if not raw:
            continue
        path = raw.decode(errors="surrogateescape")
        parts = [part.casefold() for part in PurePosixPath(path).parts]
        if ".planning" in parts or parts[-1] in private_names:
            blocked.append(path)
    if blocked:
        print("提交已阻止：暂存区包含仅供本地使用的开发记录。", file=sys.stderr)
        for path in blocked:
            print(f"  {path!r}", file=sys.stderr)
        print("请将记录保存在 .planning/，并取消这些文件的 Git 跟踪。", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
