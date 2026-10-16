#!/usr/bin/env python3
"""After a stable ReMRSS release succeeds, update its isolated feed using the CI token."""

import base64
import argparse
import json
import os
from pathlib import Path
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener

from ci_release import PACKAGE_ID, release_metadata

ROOT = Path(__file__).resolve().parents[1]
BRANCH = 'main'  # Must match ProjectInfo.VERSION_MANIFEST in the Android client.
FEED_PATH = 'updates/remrss/version.json'  # Keep the old app's root version.json unchanged.


class ApiError(Exception):
    def __init__(self, status):
        self.status = status
        super().__init__(f'GitHub API returned HTTP {status}')


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class GitHubApi:
    def __init__(self, token):
        if not token:
            raise ValueError('GH_TOKEN is required in CI; never bundle it in the app.')
        self.token = token
        self.opener = build_opener(NoRedirect())

    def __call__(self, method, path, payload=None):
        request = Request('https://api.github.com' + path, method=method,
                          data=None if payload is None else json.dumps(payload).encode('utf-8'),
                          headers={'Authorization': 'Bearer ' + self.token,
                                   'Accept': 'application/vnd.github+json',
                                   'Content-Type': 'application/json',
                                   'X-GitHub-Api-Version': '2026-03-10',
                                   'User-Agent': 'ReMRSS-release-manifest'})
        try:
            with self.opener.open(request, timeout=30) as response:
                data = response.read(1024 * 1024 + 1)
                if len(data) > 1024 * 1024:
                    raise ValueError('GitHub response is too large.')
                return json.loads(data)
        except HTTPError as error:
            # Do not print request headers, credentials or arbitrary API error bodies.
            raise ApiError(error.code) from None


def validate_manifest(manifest, require_apk=False):
    if not isinstance(manifest, dict) or type(manifest.get('schemaVersion')) is not int or manifest['schemaVersion'] != 1:
        raise ValueError('Unsupported version manifest schema.')
    name, code, tag = (manifest.get(key) for key in ('versionName', 'versionCode', 'tag'))
    if not isinstance(name, str) or not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', name):
        raise ValueError('The manifest must describe a stable semantic version.')
    if type(code) is not int or not 0 < code <= 2100000000 or tag != 'v' + name:
        raise ValueError('Invalid manifest versionCode or tag.')
    apk = manifest.get('apk')
    if apk is not None or require_apk:
        if not isinstance(apk, dict) or not isinstance(apk.get('sha256'), str) or not re.fullmatch(r'[0-9a-f]{64}', apk['sha256']):
            raise ValueError('Invalid APK SHA256 metadata.')
        if type(apk.get('size')) is not int or not 0 < apk['size'] <= 256 * 1024 * 1024:
            raise ValueError('Invalid APK size metadata.')
        if apk.get('packageName') != PACKAGE_ID:
            raise ValueError('Invalid release package name.')
        # Check the repository-specific prefix against the published asset below.
        if not isinstance(apk.get('url'), str) or not re.fullmatch(r'https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/download/'
                + re.escape(tag) + r'/[A-Za-z0-9][A-Za-z0-9._-]{0,150}\.apk', apk['url']):
            raise ValueError('Invalid release download URL.')
    return tuple(int(part) for part in name.split('.'))


def published_manifest(metadata, tag, release, repository, manifest):
    validate_manifest(manifest, require_apk=True)
    if any((manifest['versionName'] != metadata['version'], manifest['versionCode'] != int(metadata['build_number']), manifest['tag'] != tag)):
        raise ValueError('Verified artifact metadata does not match the release tag.')
    if metadata['prerelease'] != 'false' or not isinstance(release, dict) or any((
            release.get('tag_name') != tag,
            release.get('draft') is not False,
            release.get('prerelease') is not False,
            not release.get('published_at'),
            release.get('html_url') != f'https://github.com/{repository}/releases/tag/{tag}')):
        raise ValueError('Only a published, non-draft stable release may update version.json.')
    uploaded = {asset.get('name') for asset in release.get('assets', [])
                if isinstance(asset, dict) and asset.get('state') == 'uploaded'
                and type(asset.get('size')) is int and asset['size'] > 0}
    if not {metadata['apk_name'], 'SHA256SUMS', 'LICENSE', 'NOTICE'} <= uploaded:
        raise ValueError('Published release is missing the verified APK or required assets.')
    assets = [asset for asset in release['assets'] if asset.get('name') == metadata['apk_name']]
    expected_url = f'https://github.com/{repository}/releases/download/{tag}/{metadata["apk_name"]}'
    apk = manifest['apk']
    if len(assets) != 1 or apk['url'] != expected_url or any((
            assets[0].get('browser_download_url') != expected_url, assets[0].get('size') != apk['size'],
            assets[0].get('digest') != 'sha256:' + apk['sha256'])):
        raise ValueError('Published APK URL, size or SHA256 differs from the verified artifact.')
    return manifest


def should_update(current, candidate):
    old_version, new_version = validate_manifest(current), validate_manifest(candidate)
    if new_version < old_version:
        return False  # A delayed older job must never roll back the stable feed.
    if new_version == old_version:
        if current['versionCode'] != candidate['versionCode']:
            raise ValueError('An existing release tag cannot change its versionCode.')
        if 'apk' not in current:
            return True  # Upgrade an older four-field feed after verifying its published APK.
        if current['apk'] != candidate['apk']:
            raise ValueError('An existing release tag cannot change its APK metadata.')
        return False  # Idempotent reruns do not create another commit.
    if candidate['versionCode'] <= current['versionCode']:
        raise ValueError('New stable releases must increase versionCode.')
    return True


def publish(api, repository, tag, metadata, artifact):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('Invalid repository name.')
    # Validate the tag before constructing any request paths.
    validate_manifest({'schemaVersion': 1, 'versionName': metadata['version'],
                       'versionCode': int(metadata['build_number']), 'tag': tag})
    release = api('GET', f'/repos/{repository}/releases/tags/{tag}')
    candidate = published_manifest(metadata, tag, release, repository, artifact)
    endpoint = f'/repos/{repository}/contents/{FEED_PATH}'
    for attempt in range(3):
        sha = None
        try:
            file = api('GET', endpoint + '?ref=' + BRANCH)
        except ApiError as error:
            if error.status != 404:
                raise
        else:
            if file.get('type') != 'file' or file.get('encoding') != 'base64' or not file.get('sha'):
                raise ValueError('Unexpected version.json contents response.')
            current = json.loads(base64.b64decode(''.join(file['content'].split()), validate=True))
            if not should_update(current, candidate):
                return False
            sha = file['sha']
        content = json.dumps(candidate, ensure_ascii=False, indent=2) + '\n'
        payload = {'message': f'chore: update ReMRSS stable manifest to {tag}',
                   'branch': BRANCH,
                   'content': base64.b64encode(content.encode('utf-8')).decode('ascii')}
        if sha:
            payload['sha'] = sha
        try:
            api('PUT', endpoint, payload)
            return True
        except ApiError as error:
            if error.status != 409 or attempt == 2:
                raise
            # Re-read the current file/SHA after a conflicting concurrent update.
    return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--artifact', type=Path, default=Path('dist/version-artifact.json'))
    args = parser.parse_args()
    try:
        if os.environ.get('GITHUB_ACTIONS') != 'true':
            raise ValueError('This publishing command may only run in GitHub Actions.')
        tag = os.environ.get('RELEASE_TAG', '')
        metadata = release_metadata((ROOT / 'version.properties').read_text(), tag)
        updated = publish(GitHubApi(os.environ.get('GH_TOKEN', '')),
                          os.environ.get('GITHUB_REPOSITORY', ''), tag, metadata, json.loads(args.artifact.read_text()))
        print('Stable version.json updated.' if updated else 'Stable manifest is already current or newer; unchanged.')
    except (ValueError, OSError, KeyError, TypeError, ApiError, URLError) as error:
        print(f'Version manifest update failed: {error}', file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
