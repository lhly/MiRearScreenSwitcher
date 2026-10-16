#!/usr/bin/env python3
"""Offline release-to-manifest contract and concurrency regression checks."""

import base64
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

from ci_release import release_metadata
from update_version_manifest import ApiError, FEED_PATH, GitHubApi, publish, validate_manifest

REPO = 'lhly/MiRearScreenSwitcher'
TAG = 'v3.3.2'
METADATA = release_metadata('versionName=3.3.2\nversionCode=2014\n', TAG)
CURRENT = {'schemaVersion': 1, 'versionName': '3.3.0', 'versionCode': 2012, 'tag': 'v3.3.0'}
CANDIDATE = dict(CURRENT, versionName='3.3.2', versionCode=2014, tag=TAG,
                 apk={'url': f'https://github.com/{REPO}/releases/download/{TAG}/ReMRSS-{TAG}.apk',
                      'sha256': '1' * 64, 'size': 123, 'packageName': 'top.zainzh.rearscreen'})
NEWER = dict(CANDIDATE, versionName='3.4.0', versionCode=2015, tag='v3.4.0',
             apk=dict(CANDIDATE['apk'], url=f'https://github.com/{REPO}/releases/download/v3.4.0/ReMRSS-v3.4.0.apk'))


def release():
    return {'tag_name': TAG, 'draft': False, 'prerelease': False,
            'published_at': '2026-10-15T00:00:00Z',
            'html_url': f'https://github.com/{REPO}/releases/tag/{TAG}',
            'assets': [{'name': name, 'state': 'uploaded', 'size': 123, 'digest': 'sha256:' + '1' * 64,
                        'browser_download_url': f'https://github.com/{REPO}/releases/download/{TAG}/{name}'}
                       for name in ('ReMRSS-v3.3.2.apk', 'SHA256SUMS', 'LICENSE', 'NOTICE')]}


def contents(manifest, sha='original-sha'):
    return {'type': 'file', 'encoding': 'base64', 'sha': sha,
            'content': base64.b64encode(json.dumps(manifest).encode()).decode()}


class FakeApi:
    def __init__(self):
        self.release = release()
        self.file = contents(CURRENT)
        self.calls = []
        self.conflict_file = None
        self.fail = None

    def __call__(self, method, path, payload=None):
        self.calls.append((method, path, copy.deepcopy(payload)))
        if self.fail:
            raise ApiError(self.fail)
        if '/releases/tags/' in path:
            return self.release
        if method == 'GET':
            if self.file is None:
                raise ApiError(404)
            return self.file
        if self.conflict_file:
            self.file = self.conflict_file
            self.conflict_file = None
            raise ApiError(409)
        self.file = dict(contents(json.loads(base64.b64decode(payload['content']))), sha='updated-sha')
        return {'content': self.file}

    @property
    def writes(self):
        return [call for call in self.calls if call[0] == 'PUT']


class ManifestChecks(unittest.TestCase):
    def test_published_stable_release_updates_only_isolated_remrss_feed(self):
        api = FakeApi()
        self.assertTrue(publish(api, REPO, TAG, METADATA, CANDIDATE))
        self.assertEqual(len(api.writes), 1)
        _, path, payload = api.writes[0]
        self.assertEqual(path, f'/repos/{REPO}/contents/updates/remrss/version.json')
        self.assertTrue(all('/contents/version.json' not in call[1] for call in api.calls))
        self.assertEqual(payload['branch'], 'main')
        self.assertEqual(payload['sha'], 'original-sha')
        self.assertEqual(json.loads(base64.b64decode(payload['content'])), CANDIDATE)

    def test_rerun_is_idempotent(self):
        api = FakeApi()
        self.assertTrue(publish(api, REPO, TAG, METADATA, CANDIDATE))
        self.assertFalse(publish(api, REPO, TAG, METADATA, CANDIDATE))
        self.assertEqual(len(api.writes), 1)

    def test_missing_feed_can_be_created_after_successful_release(self):
        api = FakeApi(); api.file = None
        self.assertTrue(publish(api, REPO, TAG, METADATA, CANDIDATE))
        self.assertNotIn('sha', api.writes[0][2])

    def test_draft_prerelease_and_unpublished_releases_never_write(self):
        for field, value in [('draft', True), ('prerelease', True), ('published_at', None),
                             ('tag_name', 'v3.4.0'), ('html_url', 'https://example.invalid')]:
            api = FakeApi(); api.release[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                publish(api, REPO, TAG, METADATA, CANDIDATE)
            self.assertEqual(api.writes, [])

    def test_incomplete_or_still_uploading_assets_never_write(self):
        for index in range(4):
            for field, value in [('state', 'new'), ('size', 0)]:
                api = FakeApi(); api.release['assets'][index][field] = value
                with self.subTest(index=index, field=field), self.assertRaises(ValueError):
                    publish(api, REPO, TAG, METADATA, CANDIDATE)
                self.assertEqual(api.writes, [])

    def test_failed_release_lookup_never_writes(self):
        for status in (403, 404, 429, 500):
            api = FakeApi(); api.fail = status
            with self.subTest(status=status), self.assertRaises(ApiError):
                publish(api, REPO, TAG, METADATA, CANDIDATE)
            self.assertEqual(api.writes, [])

    def test_delayed_older_job_cannot_roll_back_feed(self):
        api = FakeApi()
        api.file = contents(NEWER)
        self.assertFalse(publish(api, REPO, TAG, METADATA, CANDIDATE))
        self.assertEqual(api.writes, [])

    def test_new_release_must_increase_android_version_code(self):
        api = FakeApi(); api.file = contents(dict(CURRENT, versionCode=2014))
        with self.assertRaisesRegex(ValueError, 'increase versionCode'):
            publish(api, REPO, TAG, METADATA, CANDIDATE)
        self.assertEqual(api.writes, [])

    def test_same_tag_cannot_change_android_version_code(self):
        api = FakeApi(); api.file = contents(dict(CANDIDATE, versionCode=2012))
        with self.assertRaisesRegex(ValueError, 'cannot change'):
            publish(api, REPO, TAG, METADATA, CANDIDATE)
        self.assertEqual(api.writes, [])

    def test_conflict_reloads_sha_without_overwriting_unrelated_changes(self):
        api = FakeApi(); api.conflict_file = contents(CURRENT, 'concurrent-sha')
        self.assertTrue(publish(api, REPO, TAG, METADATA, CANDIDATE))
        self.assertEqual([call[2]['sha'] for call in api.writes], ['original-sha', 'concurrent-sha'])

    def test_conflict_with_newer_release_does_not_retry_old_write(self):
        api = FakeApi()
        api.conflict_file = contents(NEWER)
        self.assertFalse(publish(api, REPO, TAG, METADATA, CANDIDATE))
        self.assertEqual(len(api.writes), 1)

    def test_persistent_conflicts_fail_after_three_attempts(self):
        api = FakeApi()
        def conflict(method, path, payload=None):
            if method == 'PUT':
                api.writes_attempted += 1
                raise ApiError(409)
            return api(method, path, payload)
        api.writes_attempted = 0
        with self.assertRaises(ApiError):
            publish(conflict, REPO, TAG, METADATA, CANDIDATE)
        self.assertEqual(api.writes_attempted, 3)

    def test_malformed_existing_feed_is_never_silently_overwritten(self):
        for manifest in ({}, dict(CURRENT, schemaVersion=2), dict(CURRENT, versionCode='2012'),
                         dict(CURRENT, tag='v9.0.0'), dict(CURRENT, versionName='3.3.0-rc.1')):
            api = FakeApi(); api.file = contents(manifest)
            with self.subTest(manifest=manifest), self.assertRaises(ValueError):
                publish(api, REPO, TAG, METADATA, CANDIDATE)
            self.assertEqual(api.writes, [])

    def test_prerelease_or_unsafe_tag_and_repository_rejected_before_requests(self):
        for tag, metadata in [('v3.3.2-rc.1', release_metadata('versionName=3.3.2-rc.1\nversionCode=2014', 'v3.3.2-rc.1')),
                              ('../../other', METADATA)]:
            api = FakeApi()
            with self.assertRaises(ValueError): publish(api, REPO, tag, metadata, CANDIDATE)
            self.assertEqual(api.calls, [])
        api = FakeApi()
        with self.assertRaises(ValueError): publish(api, '../other?secret', TAG, METADATA, CANDIDATE)
        self.assertEqual(api.calls, [])

    def test_legacy_feed_keeps_its_identity_and_new_feed_cannot_use_legacy_package(self):
        root = Path(__file__).resolve().parents[1]
        legacy = json.loads((root / 'version.json').read_text())
        self.assertEqual(legacy['apk']['packageName'], 'com.tgwgroup.MiRearScreenSwitcher.personal')
        with self.assertRaisesRegex(ValueError, 'package name'):
            validate_manifest(legacy, require_apk=True)
        if not (root / FEED_PATH).exists():
            return  # The first successful ReMRSS release creates the feed, without a placeholder APK.
        manifest = json.loads((root / FEED_PATH).read_text())
        validate_manifest(manifest, require_apk=True)
        properties = (root / 'version.properties').read_text()
        name = next(line.split('=', 1)[1] for line in properties.splitlines() if line.startswith('versionName='))
        working = release_metadata(properties, 'v' + name)
        self.assertLessEqual(manifest['versionCode'], int(working['build_number']))
        self.assertLessEqual(validate_manifest(manifest), tuple(map(int, name.split('-')[0].split('.'))))

    def test_cli_cannot_publish_from_developer_machine(self):
        env = dict(os.environ); env.pop('GITHUB_ACTIONS', None)
        result = subprocess.run([sys.executable, str(Path(__file__).with_name('update_version_manifest.py'))],
                                env=env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('only run in GitHub Actions', result.stderr)

    def test_ci_http_adapter_sends_token_only_to_fixed_github_api_origin(self):
        api = GitHubApi('ci-only-test-value')
        class Response:
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def read(self, size): return b'{"content":{}}'
        with patch.object(api.opener, 'open', return_value=Response()) as opened:
            api('PUT', f'/repos/{REPO}/contents/version.json', {'branch': 'main'})
        request = opened.call_args.args[0]
        self.assertEqual(request.full_url, f'https://api.github.com/repos/{REPO}/contents/version.json')
        self.assertEqual(request.get_method(), 'PUT')
        self.assertEqual(request.get_header('Authorization'), 'Bearer ci-only-test-value')
        self.assertEqual(json.loads(request.data), {'branch': 'main'})
        self.assertEqual(opened.call_args.kwargs['timeout'], 30)

    def test_api_errors_do_not_log_headers_tokens_or_response_bodies(self):
        api = GitHubApi('ci-only-test-value')
        with patch.object(api.opener, 'open', side_effect=HTTPError('https://api.github.com', 403, 'ci-only-test-value', {}, None)):
            with self.assertRaises(ApiError) as caught:
                api('GET', f'/repos/{REPO}/contents/version.json?ref=main')
        self.assertEqual(str(caught.exception), 'GitHub API returned HTTP 403')
        self.assertNotIn('ci-only-test-value', str(caught.exception))

    def test_published_apk_must_match_verified_artifact_digest_size_and_url(self):
        for field, value in [('digest', 'sha256:' + '2' * 64), ('digest', None), ('size', 124),
                             ('browser_download_url', 'https://example.invalid/update.apk')]:
            api = FakeApi(); api.release['assets'][0][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                publish(api, REPO, TAG, METADATA, CANDIDATE)
            self.assertEqual(api.writes, [])

    def test_invalid_apk_fields_and_untrusted_urls_never_write(self):
        for field, value in [('sha256', 'abc'), ('size', 0), ('size', '123'), ('size', 256 * 1024 * 1024 + 1),
                             ('packageName', 'other.package'), ('url', 'http://github.com/evil.apk'),
                             ('url', CANDIDATE['apk']['url'] + '?redirect=elsewhere')]:
            artifact = copy.deepcopy(CANDIDATE); artifact['apk'][field] = value
            api = FakeApi()
            with self.subTest(field=field), self.assertRaises(ValueError):
                publish(api, REPO, TAG, METADATA, artifact)
            self.assertEqual(api.writes, [])

    def test_existing_four_field_feed_can_gain_verified_apk_metadata(self):
        api = FakeApi(); api.file = contents({key: value for key, value in CANDIDATE.items() if key != 'apk'})
        self.assertTrue(publish(api, REPO, TAG, METADATA, CANDIDATE))
        self.assertFalse(publish(api, REPO, TAG, METADATA, CANDIDATE))
        self.assertEqual(len(api.writes), 1)

    def test_same_tag_apk_cannot_change(self):
        api = FakeApi(); old = copy.deepcopy(CANDIDATE); old['apk']['sha256'] = '2' * 64; api.file = contents(old)
        with self.assertRaisesRegex(ValueError, 'cannot change its APK'):
            publish(api, REPO, TAG, METADATA, CANDIDATE)
        self.assertEqual(api.writes, [])


if __name__ == '__main__':
    unittest.main(verbosity=2)
