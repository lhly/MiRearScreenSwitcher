#!/usr/bin/env python3
"""Validate release metadata, restore CI signing, and stage verified APK assets."""

import argparse
import base64
import binascii
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE_ID = 'com.tgwgroup.MiRearScreenSwitcher.personal'
SEMVER = re.compile(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?')


def release_metadata(properties, tag):
    version = tag.removeprefix('v')
    match = SEMVER.fullmatch(version) if tag.startswith('v') else None
    if not match or any(p.isdigit() and len(p) > 1 and p.startswith('0')
                        for p in (match[4] or '').split('.')):
        raise ValueError('Tag must be vMAJOR.MINOR.PATCH, optionally with a prerelease suffix.')
    names = re.findall(r'^versionName\s*=\s*([^\s#]+)\s*$', properties, re.MULTILINE)
    codes = re.findall(r'^versionCode\s*=\s*([^\s#]+)\s*$', properties, re.MULTILINE)
    if len(names) != 1 or len(codes) != 1:
        raise ValueError('version.properties must contain one versionName and one versionCode.')
    name, number = names[0], codes[0]
    if not re.fullmatch(r'[1-9][0-9]*', number) or int(number) > 2100000000:
        raise ValueError('versionCode must be a positive integer no greater than 2100000000.')
    if name != version:
        raise ValueError('Tag version must match versionName in version.properties.')
    return {'version': version, 'build_number': number,
            'prerelease': str(match[4] is not None).lower(),
            'apk_name': f'MRSS-{tag}-native.apk'}


def properties_value(value):
    """Escape a value for Java Properties.load(InputStream), including Unicode."""
    result = []
    escapes = {'\\': '\\\\', '\n': '\\n', '\r': '\\r', '\t': '\\t', '\f': '\\f'}
    for char in value:
        if char in escapes:
            result.append(escapes[char])
        elif char in ' =:#!':
            result.append('\\' + char)
        elif ord(char) < 32 or ord(char) > 126:
            units = char.encode('utf-16-be')
            result.extend(f'\\u{int.from_bytes(units[i:i+2], "big"):04x}'
                          for i in range(0, len(units), 2))
        else:
            result.append(char)
    return ''.join(result)


def write_private(path, content):
    # Fail instead of overwriting an existing local signing configuration.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'wb') as output:
        output.write(content)


def expected_certificate():
    value = os.environ.get('RELEASE_CERT_SHA256', '').lower()
    if not re.fullmatch(r'[0-9a-f]{64}', value):
        raise ValueError('RELEASE_CERT_SHA256 must contain the expected certificate SHA256.')
    return value


def prepare_signing(project):
    names = ('KEYSTORE_FILE', 'KEYSTORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD')
    missing = [name for name in names if not os.environ.get(name)]
    if missing:
        raise ValueError('Missing GitHub Actions secrets: ' + ', '.join(missing))
    fingerprint = expected_certificate()
    try:
        key_bytes = base64.b64decode(''.join(os.environ['KEYSTORE_FILE'].split()), validate=True)
    except (binascii.Error, ValueError):
        raise ValueError('KEYSTORE_FILE is not valid base64.') from None
    if not key_bytes:
        raise ValueError('KEYSTORE_FILE is empty.')
    key_path = Path(os.environ['RUNNER_TEMP']).resolve() / 'mrss-release.p12'
    write_private(key_path, key_bytes)
    try:
        certificate = subprocess.run([
            'keytool', '-exportcert', '-keystore', str(key_path), '-storetype', 'PKCS12',
            '-alias', os.environ['KEY_ALIAS'], '-storepass:env', 'KEYSTORE_PASSWORD',
        ], check=True, capture_output=True).stdout
    except subprocess.CalledProcessError:
        raise ValueError('Cannot open PKCS12 signing key; check the keystore, alias and password secrets.') from None
    if hashlib.sha256(certificate).hexdigest() != fingerprint:
        raise ValueError('Signing certificate differs from RELEASE_CERT_SHA256; refusing to release.')
    properties = {
        'storeFile': str(key_path), 'storeType': 'PKCS12',
        'storePassword': os.environ['KEYSTORE_PASSWORD'],
        'keyAlias': os.environ['KEY_ALIAS'], 'keyPassword': os.environ['KEY_PASSWORD'],
        'releaseApplicationIdSuffix': '.personal',
    }
    content = ''.join(f'{key}={properties_value(value)}\n' for key, value in properties.items())
    write_private(project / 'android/key.properties', content.encode('ascii'))
    print('Personal signing key restored and certificate verified.')


def verify_apk(apk, metadata, dist):
    fingerprint = expected_certificate()
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        raise ValueError('ANDROID_HOME or ANDROID_SDK_ROOT is required.')
    build_tools = Path(sdk) / 'build-tools/36.0.0'
    signature = subprocess.run([str(build_tools / 'apksigner'), 'verify', '--print-certs', str(apk)],
                               check=True, capture_output=True, text=True).stdout
    certificates = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})', signature)
    if certificates != [fingerprint]:
        raise ValueError('APK must have exactly the expected personal signing certificate.')
    badging = subprocess.run([str(build_tools / 'aapt'), 'dump', 'badging', str(apk)],
                             check=True, capture_output=True, text=True).stdout
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.MULTILINE)
    if not package or package.groups() != (PACKAGE_ID, metadata['build_number'], metadata['version']):
        raise ValueError('APK package or version differs from the intended release.')
    if 'application-debuggable' in badging:
        raise ValueError('Refusing to publish a debuggable APK.')
    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        if 'classes.dex' not in names:
            raise ValueError('APK has no native Android application code.')
        if any(p.endswith(('/libflutter.so', '/libapp.so', 'kernel_blob.bin'))
               or p.startswith('assets/flutter_assets/') for p in names):
            raise ValueError('Refusing to publish an APK containing the removed Flutter runtime.')
    dist.mkdir(parents=True, exist_ok=True)
    target = dist / metadata['apk_name']
    shutil.copy2(apk, target)
    checksum = hashlib.sha256(target.read_bytes()).hexdigest()
    (dist / 'SHA256SUMS').write_text(f'{checksum}  {target.name}\n', encoding='ascii')
    print(f'Verified {target.name}: {PACKAGE_ID}, certificate {fingerprint}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('metadata', 'signing', 'verify'))
    parser.add_argument('apk', nargs='?', type=Path)
    parser.add_argument('--project', type=Path, default=ROOT)
    parser.add_argument('--dist', type=Path, default=Path('dist'))
    args = parser.parse_args()
    try:
        if args.command == 'signing':
            prepare_signing(args.project)
            return
        metadata = release_metadata((args.project / 'version.properties').read_text(), os.environ.get('RELEASE_TAG', ''))
        if args.command == 'metadata':
            if os.environ.get('GITHUB_OUTPUT'):
                with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
                    output.write(''.join(f'{key}={value}\n' for key, value in metadata.items()))
            print(f"Release {metadata['version']} / versionCode {metadata['build_number']}")
        else:
            if args.apk is None:
                parser.error('verify requires an APK path')
            verify_apk(args.apk, metadata, args.dist)
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print(f'Release validation failed: {error}', file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
