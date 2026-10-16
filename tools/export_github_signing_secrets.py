#!/usr/bin/env python3
"""Export the locally generated PKCS12 key as files for GitHub Actions secrets."""

import base64
from pathlib import Path
import sys

from ci_release import write_private

ROOT = Path(__file__).resolve().parents[1]


def main():
    props = {}
    for line in (ROOT / 'android/key.properties').read_text().splitlines():
        if line.strip() and not line.lstrip().startswith(('#', '!')):
            key, value = line.split('=', 1)
            props[key.strip()] = value
    if props.get('storeType') != 'PKCS12':
        raise ValueError('Expected the local PKCS12 release configuration.')
    if any('\\' in props[key] for key in ('storeFile', 'storePassword', 'keyAlias', 'keyPassword')):
        raise ValueError('This exporter expects unescaped values from the locally generated key.properties.')
    keystore = (ROOT / 'android/app' / props['storeFile']).resolve()
    values = {
        'KEYSTORE_FILE': base64.b64encode(keystore.read_bytes()),
        'KEYSTORE_PASSWORD': props['storePassword'].encode('utf-8'),
        'KEY_ALIAS': props['keyAlias'].encode('utf-8'),
        'KEY_PASSWORD': props['keyPassword'].encode('utf-8'),
    }
    folder = ROOT / '.local/github-secrets'
    folder.mkdir(mode=0o700, exist_ok=True)
    folder.chmod(0o700)
    for name, value in values.items():
        path = folder / f'{name}.txt'
        # Export can be rerun; replace only the named local export files.
        if path.exists():
            path.unlink()
        write_private(path, value)
        print(f'{name}: {path}')
    print('已导出本地文件；请自行填写到 GitHub Repository secrets。未上传任何密钥。')


if __name__ == '__main__':
    try:
        main()
    except (OSError, KeyError, ValueError):
        print('导出失败：请检查本机 android/key.properties 与个人 PKCS12 密钥配置。', file=sys.stderr)
        sys.exit(1)
