#!/usr/bin/env python3
"""Enable, disable or inspect this project's macOS Quark release background job."""

import argparse
import json
import os
from pathlib import Path
import plistlib
import subprocess
import sys

from quark_release_sync import DEFAULT_CONFIG, ROOT, SyncError, load_config

LABEL = 'top.zainzh.remrss.quark-sync'


def definition(config_path, config):
    python = str(Path(config.get('python_path', sys.executable)).resolve())
    node = Path(config['node_path']).resolve()
    if not Path(python).is_file() or not node.is_file():
        raise SyncError('Python 或 Node.js 不可用，后台任务尚未启用。')
    interval = config.get('interval_seconds', 900)
    if type(interval) is not int or not 300 <= interval <= 86400:
        raise SyncError('检查间隔应为5分钟至24小时。')
    local = config_path.resolve().parent
    environment = {
        'PATH': os.pathsep.join([str(node.parent), str(Path(python).parent), '/usr/bin', '/bin', '/usr/sbin', '/sbin']),
    }
    # launchd does not inherit the creating Codex process's environment. Keep its
    # actual channel marker so the official CLI can use the same authorized agent.
    # Do not copy thread identifiers, credentials or the rest of the shell environment.
    if os.environ.get('CODEX_CI') == '1':
        environment['CODEX_CI'] = os.environ['CODEX_CI']
    return {
        'Label': LABEL,
        'ProgramArguments': [python, str(ROOT / 'tools/quark_release_sync.py'), 'run',
                             '--config', str(config_path.resolve())],
        'WorkingDirectory': str(ROOT),
        'EnvironmentVariables': environment,
        'StartInterval': interval,
        'RunAtLoad': True,
        'ProcessType': 'Background',
        'ThrottleInterval': 60,
        'StandardOutPath': str(local / 'sync.log'),
        'StandardErrorPath': str(local / 'sync-error.log'),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['enable', 'disable', 'status'])
    parser.add_argument('--config', type=Path, default=DEFAULT_CONFIG)
    args = parser.parse_args()
    try:
        if sys.platform != 'darwin':
            raise SyncError('此后台任务安装器仅适用于 macOS。')
        config = load_config(args.config)
        service = f'gui/{os.getuid()}/{LABEL}'
        target = Path.home() / 'Library/LaunchAgents' / (LABEL + '.plist')
        if target.exists():
            existing = plistlib.loads(target.read_bytes())
            if str(ROOT / 'tools/quark_release_sync.py') not in existing.get('ProgramArguments', []):
                raise SyncError('相同名称的后台任务不属于当前项目，未覆盖。')
        if args.command == 'status':
            process = subprocess.run(['launchctl', 'print', service], capture_output=True, text=True)
            print(json.dumps({'installed': target.exists(), 'enabled': process.returncode == 0,
                              'interval_seconds': config.get('interval_seconds', 900)}, ensure_ascii=False))
            return
        subprocess.run(['launchctl', 'bootout', service], capture_output=True, text=True)
        if args.command == 'disable':
            target.unlink(missing_ok=True)
            print('本地自动同步已停止；网盘文件和 Skill 授权保持不变。')
            return
        value = definition(args.config, config)
        for name in ['sync.log', 'sync-error.log']:
            path = args.config.resolve().parent / name
            path.touch(exist_ok=True)
            path.chmod(0o600)
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_suffix('.plist.new')
        temporary.write_bytes(plistlib.dumps(value))
        temporary.chmod(0o600)
        temporary.replace(target)
        process = subprocess.run(['launchctl', 'bootstrap', f'gui/{os.getuid()}', str(target)],
                                 capture_output=True, text=True)
        if process.returncode:
            raise SyncError(f'本地后台任务启动失败（状态{process.returncode}）。')
        print(f'本地自动同步已启用，每{value["StartInterval"] // 60}分钟检查一次。')
    except (SyncError, OSError, ValueError, KeyError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
