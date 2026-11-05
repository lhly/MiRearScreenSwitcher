#!/usr/bin/env python3
"""Mirror the published stable ReMRSS release through the official Quark Skill."""

import argparse
import contextlib
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener

from update_version_manifest import validate_manifest

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONFIG = ROOT / '.local/quark-release-sync/config.json'
REPOSITORY = 'lhly/MiRearScreenSwitcher'
FEED_URL = f'https://raw.githubusercontent.com/{REPOSITORY}/main/updates/remrss/version.json'
MAX_APK = 256 * 1024 * 1024


class SyncError(Exception):
    pass


def now():
    return datetime.now(timezone.utc).isoformat()


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=path.name + '.', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(data, stream, ensure_ascii=False, indent=2)
            stream.write('\n')
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


class SafeRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parsed = urlparse(newurl)
        host = parsed.hostname or ''
        if parsed.scheme != 'https' or not (host == 'github.com' or host.endswith('.githubusercontent.com')):
            raise SyncError('发行文件跳转到了非 GitHub 地址。')
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class Http:
    def __init__(self):
        self.opener = build_opener(SafeRedirect())

    def download(self, url, path, limit):
        request = Request(url, headers={'User-Agent': 'ReMRSS-Quark-release-sync'})
        try:
            with self.opener.open(request, timeout=45) as response, path.open('wb') as output:
                size = 0
                while chunk := response.read(128 * 1024):
                    size += len(chunk)
                    if size > limit:
                        raise SyncError('发行文件超出预期大小，已停止。')
                    output.write(chunk)
        except HTTPError as error:
            raise SyncError(f'获取发行文件失败：HTTP {error.code}。') from None
        except (URLError, TimeoutError) as error:
            raise SyncError('获取发行文件时网络不可用，请稍后重试。') from error

    def manifest(self, directory):
        path = directory / 'stable-manifest.json'
        self.download(FEED_URL, path, 32768)
        return json.loads(path.read_text())


def manifest_identity(manifest):
    version = validate_manifest(manifest, require_apk=True)
    tag = manifest['tag']
    expected = f'https://github.com/{REPOSITORY}/releases/download/{tag}/ReMRSS-{tag}.apk'
    if manifest['apk']['url'] != expected:
        raise SyncError('稳定版清单中的下载地址不属于本项目。')
    return version, {'tag': tag, 'versionCode': manifest['versionCode'], 'apk': manifest['apk']}


def expected_files(manifest):
    return [f'ReMRSS-{manifest["tag"]}.apk', 'LICENSE', 'NOTICE', 'SHA256SUMS']


def same_fid(left, right):
    # Compare SDK ID representations locally; always send the full returned ID.
    return bool(left and right) and left.rsplit('|', 1)[-1] == right.rsplit('|', 1)[-1]


def checksums(path, names):
    expected = set(names) - {'SHA256SUMS'}
    result = {}
    for line in path.read_text().splitlines():
        match = re.fullmatch(r'([0-9a-fA-F]{64}) [ *]([A-Za-z0-9._-]+)', line)
        if not match or match[2] not in expected or match[2] in result:
            raise SyncError('SHA256SUMS 包含异常文件名、重复项或无效记录。')
        result[match[2]] = match[1].lower()
    if set(result) != expected:
        raise SyncError('SHA256SUMS 未包含完整的发行文件。')
    return result


def prepare(manifest, work, publish_dir, http):
    manifest_identity(manifest)
    tag = manifest['tag']
    names = expected_files(manifest)
    destination = publish_dir / tag
    stage = Path(tempfile.mkdtemp(prefix=tag + '-', dir=work))
    try:
        base = f'https://github.com/{REPOSITORY}/releases/download/{tag}/'
        for name in names:
            limit = manifest['apk']['size'] if name.endswith('.apk') else 1024 * 1024
            cached = destination / name
            if cached.is_file() and not cached.is_symlink():
                shutil.copyfile(cached, stage / name)
            else:
                http.download(base + name, stage / name, min(limit, MAX_APK))
        sums = checksums(stage / 'SHA256SUMS', names)
        apk_name = names[0]
        if sums[apk_name] != manifest['apk']['sha256']:
            raise SyncError('APK 校验文件与已公开稳定清单不一致。')
        if (stage / apk_name).stat().st_size != manifest['apk']['size']:
            raise SyncError('APK 大小与稳定版清单不一致。')
        for name, digest in sums.items():
            if sha256(stage / name) != digest:
                raise SyncError(f'{name} 校验失败，禁止上传。')
        if destination.exists():
            if destination.is_symlink() or any(not p.is_file() or p.is_symlink() for p in destination.iterdir()):
                raise SyncError('本地发行缓存包含异常条目。')
            if {p.name for p in destination.iterdir()} != set(names):
                raise SyncError('本地发行缓存包含非发行文件。')
            for name in names:
                if sha256(destination / name) != sha256(stage / name):
                    raise SyncError('相同版本的本地发行文件发生变化，已停止。')
        else:
            publish_dir.mkdir(parents=True, exist_ok=True)
            stage.rename(destination)
        return destination, {name: {'size': (destination / name).stat().st_size,
                                   'sha256': sha256(destination / name)} for name in names}
    finally:
        if stage.exists():
            shutil.rmtree(stage)


class Quark:
    def __init__(self, config, work, session_input=None, session_id=None):
        self.skill = Path(config['skill_dir']).expanduser().resolve()
        self.node = config['node_path']
        self.work = work
        self.session_input = session_input
        self.session_id = session_id or f'{int(datetime.now().timestamp())}-{secrets.token_hex(3)}'
        self.updated = False
        self.notices = []

    def install_check(self):
        env = dict(os.environ)
        env['PATH'] = str(Path(self.node).parent) + os.pathsep + env.get('PATH', '/usr/bin:/bin')
        result = subprocess.run(['bash', str(self.skill / 'scripts/install.sh')],
                                capture_output=True, text=True, timeout=240, env=env)
        records = parse_lines(result.stdout)
        record = next((r for r in reversed(records) if r.get('type') == 'install_result'), {})
        if result.returncode or record.get('success') is not True:
            raise SyncError('夸克 Skill 安装检查未通过，未进行上传。')
        self.updated = record.get('updated') is True

    def call(self, *args):
        cmd = [self.node, str(self.skill / 'scripts/quark-drive.cjs'), *map(str, args),
               '--session-id', self.session_id]
        if self.session_input is not None:
            cmd += ['--session-input', self.session_input]
        if self.updated:
            cmd.append('--skill-updated')
        env = dict(os.environ)
        env['OPENCLAW_RUNTIME_DIR'] = str(self.work.parent)
        process = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, timeout=600, env=env)
        records = parse_lines(process.stdout)
        final = next((r for r in reversed(records) if r.get('type') == 'result'), None)
        if final is None:
            raise SyncError('夸克 Skill 未返回有效结果，请检查授权或网络。')
        notice = final.get('notice', {}).get('text')
        if notice:
            self.notices.append(notice)
        if process.returncode or final.get('code') != 0:
            write_json(self.work.parent / 'last-skill-error.json', {'action': args[0], 'result': final})
            # Never print arbitrary stdout/stderr, account tokens or signed URLs.
            if final.get('code') == -104:
                raise SyncError('夸克工具无法识别后台运行环境，请在原 Codex 环境重新启用自动同步。')
            msg = str(final.get('msg', '操作失败'))
            if re.search('未授权|未完成授权|认证|token', msg, re.IGNORECASE):
                raise SyncError('夸克授权失效，请重新授权 Skill；本次未继续上传。')
            raise SyncError(f'夸克操作失败（状态 {final.get("code", "未知")}），本次已停止。')
        return final.get('data', {}), records

    def browse(self, fid):
        directory = self.work / ('browse-' + secrets.token_hex(4))
        directory.mkdir()
        data, _ = self.call('browse', '--parent-fid', fid, '--all', '--task-dir', directory)
        if data.get('hasMore') is not False or not data.get('file_path'):
            raise SyncError('未取得完整网盘目录清单，禁止继续上传。')
        artifact = Path(data['file_path']).resolve()
        if not artifact.is_relative_to(directory.resolve()) or not artifact.is_file():
            raise SyncError('网盘目录清单路径无效。')
        entries = [json.loads(line) for line in artifact.read_text().splitlines() if line.strip()]
        if any(not isinstance(e, dict) or not e.get('fid') or not isinstance(e.get('filename'), str) for e in entries):
            raise SyncError('网盘目录清单缺少有效文件信息。')
        return entries

    def folder(self, parent, tag):
        data, _ = self.call('create-folder', '--dir-path', tag, '--parent-fid', parent)
        if not isinstance(data.get('fid'), str) or not data['fid']:
            raise SyncError('未取得版本文件夹信息。')
        return data['fid']

    def readback(self, fid, expected):
        with tempfile.TemporaryDirectory(prefix='readback-', dir=self.work) as directory:
            data, _ = self.call('download', '--fid', fid, '--output-dir', directory)
            path = Path(data.get('filePath', '')).resolve()
            if not path.is_relative_to(Path(directory).resolve()) or not path.is_file() or path.stat().st_size != expected['size']:
                raise SyncError('网盘文件内容与发行文件不一致，未覆盖已有文件。')
            if sha256(path) != expected['sha256']:
                raise SyncError('网盘已有同名但内容不同的文件，未覆盖；请人工检查。')

    def upload(self, path, parent):
        data, records = self.call('upload', path, '--parent-fid', parent)
        results = [r.get('data', {}) for r in records if r.get('type') == 'list' and r.get('code') == 0]
        if data.get('successCount') != 1 or data.get('failedCount', 0) != 0 or len(results) != 1:
            raise SyncError('上传结果不完整，未将版本标记为完成。')
        item = results[0]
        if item.get('fileName') != path.name or item.get('fileSize') != path.stat().st_size or not item.get('fileId'):
            raise SyncError('上传回执与发行文件不一致。')
        return item['fileId'], data.get('fullPath', '')


def parse_lines(output):
    result = []
    for line in output.splitlines():
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(item, dict):
            result.append(item)
    return result


def sync_files(quark, folder, local, files, record, save):
    entries = quark.browse(folder)
    full_path = ''
    for name, expected in files.items():
        matches = [e for e in entries if e['filename'] == name]
        if len(matches) > 1 or any(e.get('file_type') != '1' for e in matches):
            raise SyncError('版本目录存在重复名称或同名文件夹，未覆盖任何文件。')
        if matches:
            item = matches[0]
            previous = record.get('files', {}).get(name)
            if not previous or any(previous.get(k) != v for k, v in expected.items()) or not same_fid(previous.get('fid'), item['fid']):
                if item.get('size') != expected['size']:
                    raise SyncError('网盘已有同名但大小不同的文件，未覆盖。')
                quark.readback(item['fid'], expected)
            fid = item['fid']
        else:
            _, full_path = quark.upload(local / name, folder)
            # Upload receipts and Browse may use unrelated ID representations.
            # Resolve the uploaded name from the full listing before readback.
            entries = quark.browse(folder)
            uploaded = [e for e in entries if e['filename'] == name]
            if len(uploaded) != 1 or uploaded[0].get('file_type') != '1' or uploaded[0].get('size') != expected['size']:
                raise SyncError('上传后未能唯一定位发行文件，未将版本标记为完成。')
            fid = uploaded[0]['fid']
            quark.readback(fid, expected)
        record.setdefault('files', {})[name] = dict(expected, fid=fid)
        save()
    # Check the full directory, not the five-item preview.
    confirmed = quark.browse(folder)
    for name, expected in record['files'].items():
        same_name = [e for e in confirmed if e['filename'] == name]
        if len(same_name) != 1 or not same_fid(same_name[0].get('fid'), expected['fid']) or same_name[0].get('size') != expected['size']:
            raise SyncError('上传后目录核对失败，未将版本标记为完成。')
    return full_path


def run(config, state_path, work, http, quark_factory=Quark, session_input=None, session_id=None):
    state = json.loads(state_path.read_text()) if state_path.exists() else {'schema_version': 1, 'releases': {}}
    if state.get('schema_version') != 1 or not isinstance(state.get('releases'), dict):
        raise SyncError('本地同步状态无效。')
    with tempfile.TemporaryDirectory(prefix='check-', dir=work) as temporary:
        manifest = http.manifest(Path(temporary))
    version, identity = manifest_identity(manifest)
    floor = tuple(map(int, config['minimum_version'].split('.')))
    if version < floor:
        return {'status': 'skipped', 'reason': 'older_than_configured_baseline'}
    for old in state['releases'].values():
        if old.get('status') == 'complete' and old.get('versionCode', 0) > identity['versionCode']:
            return {'status': 'skipped', 'reason': 'older_than_uploaded_release'}
    record = state['releases'].get(identity['tag'])
    if record and any(record.get(k) != v for k, v in identity.items()):
        raise SyncError('已记录的正式版本内容发生变化，未上传。')
    if record and record.get('target_fid') != config['quark_target_fid']:
        raise SyncError('目标目录已改变，请使用独立状态文件重新配置。')
    if record and record.get('status') == 'complete':
        return {'status': 'unchanged', 'tag': identity['tag']}
    local, files = prepare(manifest, work, Path(config['publish_dir']), http)
    quark = quark_factory(config, work, session_input, session_id)
    quark.install_check()
    try:
        roots = quark.browse(config['quark_target_fid'])
        matches = [e for e in roots if e['filename'] == identity['tag']]
        if len(matches) > 1 or any(e.get('file_type') != '0' for e in matches):
            raise SyncError('网盘中存在与版本目录同名的其他内容，已停止。')
        if not matches:
            quark.folder(config['quark_target_fid'], identity['tag'])
            roots = quark.browse(config['quark_target_fid'])
            matches = [e for e in roots if e['filename'] == identity['tag']]
            if len(matches) != 1 or matches[0].get('file_type') != '0':
                raise SyncError('新建版本目录核对失败，未继续上传。')
        folder = matches[0]['fid']
        if record and not same_fid(record.get('folder_fid'), folder):
            # Reconcile the initial receipt from an interrupted first run.
            # create-folder is documented as idempotent for an existing name.
            previous = record.get('folder_fid', '')
            if '|' in previous or quark.folder(config['quark_target_fid'], identity['tag']) != previous:
                raise SyncError('已记录的版本目录发生变化，未继续上传。')
        record = record or dict(identity, target_fid=config['quark_target_fid'], folder_fid=folder, files={}, status='pending')
        record['folder_fid'] = folder
        state['releases'][identity['tag']] = record
        save = lambda: write_json(state_path, state)
        save()
        full_path = sync_files(quark, folder, local, files, record, save)
        if full_path:
            record['full_path'] = full_path
        record.update(status='complete', completed_at=now())
        save()
        return {'status': 'uploaded', 'tag': identity['tag'], 'file_count': len(files),
                'full_path': full_path or record.get('full_path', ''), 'notices': quark.notices}
    except SyncError as error:
        error.notices = quark.notices
        raise


@contextlib.contextmanager
def locked(path):
    with path.open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise SyncError('另一个同步任务仍在运行，本次跳过。') from None
        yield


def load_config(path):
    config = json.loads(path.read_text())
    if config.get('schema_version') != 1 or config.get('repository') != REPOSITORY:
        raise SyncError('本地同步配置不属于本项目。')
    if not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', config.get('minimum_version', '')):
        raise SyncError('最低同步版本配置无效。')
    if not config.get('quark_target_fid') or config['quark_target_fid'] == '0':
        raise SyncError('必须明确配置已确认的目标目录，不允许默认上传根目录。')
    return config


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['run', 'status'])
    parser.add_argument('--config', type=Path, default=DEFAULT_CONFIG)
    parser.add_argument('--session-input-file', type=Path, help='Agent调用时从本地文件传入原始用户输入与会话ID。')
    args = parser.parse_args()
    root = args.config.resolve().parent
    root.mkdir(parents=True, exist_ok=True)
    result = {}
    try:
        config = load_config(args.config)
        state_path = root / 'state.json'
        if args.command == 'status':
            print(json.dumps(json.loads((root / 'last-result.json').read_text()) if (root / 'last-result.json').exists()
                             else {'status': 'not_run'}, ensure_ascii=False))
            return
        work = root / 'work'
        work.mkdir(exist_ok=True)
        session = json.loads(args.session_input_file.read_text()) if args.session_input_file else {}
        with locked(root / 'sync.lock'):
            result = run(config, state_path, work, Http(), session_input=session.get('request'), session_id=session.get('id'))
        result['checked_at'] = now()
        write_json(root / 'last-result.json', result)
        print(json.dumps(result, ensure_ascii=False))
    except (SyncError, ValueError, OSError, KeyError, subprocess.SubprocessError) as error:
        result = {'status': 'failed', 'checked_at': now(), 'message': str(error) if isinstance(error, SyncError)
                  else f'本地同步检查失败（{type(error).__name__}）。', 'notices': getattr(error, 'notices', [])}
        write_json(root / 'last-result.json', result)
        print(json.dumps(result, ensure_ascii=False), file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
