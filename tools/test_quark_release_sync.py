#!/usr/bin/env python3
"""Exercise release integrity, conflict handling and interrupted Quark syncs."""

import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import quark_release_sync as sync
from quark_release_job import definition


def digest(data):
    return hashlib.sha256(data).hexdigest()


class HttpFixture:
    def __init__(self):
        self.contents = {'ReMRSS-v3.6.0.apk': b'published apk', 'LICENSE': b'GPL text', 'NOTICE': b'Credits'}
        self.contents['SHA256SUMS'] = ''.join(f'{digest(data)}  {name}\n' for name, data in self.contents.items()).encode()
        self.feed = {'schemaVersion': 1, 'versionName': '3.6.0', 'versionCode': 2019, 'tag': 'v3.6.0',
                     'apk': {'url': 'https://github.com/lhly/MiRearScreenSwitcher/releases/download/v3.6.0/ReMRSS-v3.6.0.apk',
                             'size': len(self.contents['ReMRSS-v3.6.0.apk']),
                             'sha256': digest(self.contents['ReMRSS-v3.6.0.apk']), 'packageName': 'top.zainzh.rearscreen'}}

    def manifest(self, directory):
        return copy.deepcopy(self.feed)

    def download(self, url, path, limit):
        data = self.contents[url.rsplit('/', 1)[1]]
        if len(data) > limit:
            raise sync.SyncError('too large')
        path.write_bytes(data)


class QuarkFixture:
    def __init__(self):
        self.folders = {'target': [], 'version': []}
        self.data = {}
        self.uploads = []
        self.checked = 0
        self.notices = []
        self.fail_on = None
        self.corrupt_readback = False

    def factory(self, *args):
        return self

    def install_check(self):
        self.checked += 1

    def browse(self, fid):
        return copy.deepcopy(self.folders[fid])

    def folder(self, parent, tag):
        if not any(e['filename'] == tag for e in self.folders[parent]):
            self.folders[parent].append({'fid': 'version', 'filename': tag, 'file_type': '0'})
        return 'version'

    def upload(self, path, parent):
        if path.name == self.fail_on:
            raise sync.SyncError('interrupted')
        self.uploads.append(path.name)
        fid = 'file-' + path.name
        self.data[fid] = path.read_bytes()
        self.folders[parent].append({'fid': fid, 'filename': path.name, 'file_type': '1', 'size': path.stat().st_size})
        return fid, '夸克网盘/分享/ReMRSS/v3.6.0'

    def readback(self, fid, expected):
        if self.corrupt_readback or digest(self.data[fid]) != expected['sha256']:
            raise sync.SyncError('content mismatch')


class ReleaseSyncTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.work = self.root / 'work'
        self.work.mkdir()
        self.config = {'minimum_version': '3.6.0', 'publish_dir': str(self.root / 'public'),
                       'quark_target_fid': 'target'}
        self.state = self.root / 'state.json'
        self.http = HttpFixture()
        self.quark = QuarkFixture()

    def tearDown(self):
        self.temp.cleanup()

    def run_sync(self):
        return sync.run(self.config, self.state, self.work, self.http, self.quark.factory)

    def test_published_release_uploaded_and_verified(self):
        result = self.run_sync()
        self.assertEqual(result['status'], 'uploaded')
        self.assertEqual(result['file_count'], 4)
        self.assertEqual(set(self.quark.uploads), set(self.http.contents))
        self.assertEqual(json.loads(self.state.read_text())['releases']['v3.6.0']['status'], 'complete')

    def test_completed_release_does_not_call_skill_again(self):
        self.run_sync()
        self.assertEqual(self.run_sync()['status'], 'unchanged')
        self.assertEqual(self.quark.checked, 1)
        self.assertEqual(len(self.quark.uploads), 4)

    def test_interrupted_upload_resumes_only_missing_files(self):
        self.quark.fail_on = 'NOTICE'
        with self.assertRaises(sync.SyncError):
            self.run_sync()
        self.assertEqual(self.quark.uploads, ['ReMRSS-v3.6.0.apk', 'LICENSE'])
        self.quark.fail_on = None
        self.assertEqual(self.run_sync()['status'], 'uploaded')
        self.assertEqual(len(self.quark.uploads), 4)

    def test_existing_identical_files_reconciled_by_readback(self):
        self.run_sync()
        self.state.unlink()
        self.assertEqual(self.run_sync()['status'], 'uploaded')
        self.assertEqual(len(self.quark.uploads), 4)

    def test_sdk_creation_receipt_reconciled_with_opaque_folder_id(self):
        self.quark.fail_on = 'NOTICE'
        with self.assertRaises(sync.SyncError):
            self.run_sync()
        self.quark.folders['target'][0]['fid'] = '~opaque|folderFingerprint'
        self.quark.folders['~opaque|folderFingerprint'] = self.quark.folders['version']
        self.quark.fail_on = None
        self.assertEqual(self.run_sync()['status'], 'uploaded')
        self.assertEqual(len(self.quark.uploads), 4)

    def test_readback_uses_listing_ids_instead_of_upload_receipts(self):
        original = self.quark.browse
        def qualified(fid):
            rows = original(fid)
            if fid == 'version':
                for row in rows:
                    receipt = row['fid']
                    row['fid'] = '~opaque|fingerprint-' + receipt
                    self.quark.data[row['fid']] = self.quark.data[receipt]
            return rows
        self.quark.browse = qualified
        self.assertEqual(self.run_sync()['status'], 'uploaded')

    def test_rotating_folder_prefix_keeps_same_identity(self):
        self.quark.fail_on = 'NOTICE'
        with self.assertRaises(sync.SyncError):
            self.run_sync()
        record = json.loads(self.state.read_text())
        record['releases']['v3.6.0']['folder_fid'] = '~old|folderFingerprint'
        sync.write_json(self.state, record)
        self.quark.folders['target'][0]['fid'] = '~new|folderFingerprint'
        self.quark.folders['~new|folderFingerprint'] = self.quark.folders['version']
        self.quark.fail_on = None
        self.assertEqual(self.run_sync()['status'], 'uploaded')
        self.assertEqual(len(self.quark.uploads), 4)

    def test_replaced_version_folder_stops_resume(self):
        self.quark.fail_on = 'NOTICE'
        with self.assertRaises(sync.SyncError):
            self.run_sync()
        record = json.loads(self.state.read_text())
        record['releases']['v3.6.0']['folder_fid'] = '~old|oldFingerprint'
        sync.write_json(self.state, record)
        self.quark.folders['target'][0]['fid'] = '~new|newFingerprint'
        self.quark.fail_on = None
        with self.assertRaises(sync.SyncError):
            self.run_sync()
        self.assertEqual(len(self.quark.uploads), 2)

    def test_conflicting_same_size_file_never_overwritten(self):
        self.run_sync()
        self.state.unlink()
        self.quark.data['file-LICENSE'] = b'bad text'
        with self.assertRaises(sync.SyncError):
            self.run_sync()
        self.assertEqual(len(self.quark.uploads), 4)

    def test_ambiguous_version_folder_stops_upload(self):
        self.quark.folders['target'] = [{'fid': 'a', 'filename': 'v3.6.0', 'file_type': '0'},
                                        {'fid': 'b', 'filename': 'v3.6.0', 'file_type': '0'}]
        with self.assertRaises(sync.SyncError):
            self.run_sync()
        self.assertEqual(self.quark.uploads, [])

    def test_existing_version_name_is_file_stops_upload(self):
        self.quark.folders['target'] = [{'fid': 'file', 'filename': 'v3.6.0', 'file_type': '1'}]
        with self.assertRaises(sync.SyncError):
            self.run_sync()

    def test_corrupt_readback_does_not_complete_release(self):
        self.quark.corrupt_readback = True
        with self.assertRaises(sync.SyncError):
            self.run_sync()
        record = json.loads(self.state.read_text())['releases']['v3.6.0']
        self.assertEqual(record['status'], 'pending')
        self.assertEqual(record['files'], {})

    def test_changed_existing_release_rejected(self):
        self.run_sync()
        self.http.feed['apk']['sha256'] = 'a' * 64
        with self.assertRaises(sync.SyncError):
            self.run_sync()
        self.assertEqual(len(self.quark.uploads), 4)

    def test_changed_target_requires_new_state(self):
        self.run_sync()
        self.config['quark_target_fid'] = 'other'
        with self.assertRaises(sync.SyncError):
            self.run_sync()

    def test_feed_rollback_does_not_upload_older_release(self):
        sync.write_json(self.state, {'schema_version': 1, 'releases': {'v9.0.0': {'status': 'complete', 'versionCode': 9999}}})
        self.assertEqual(self.run_sync()['reason'], 'older_than_uploaded_release')
        self.assertEqual(self.quark.checked, 0)

    def test_pre_baseline_release_skipped(self):
        self.config['minimum_version'] = '4.0.0'
        self.assertEqual(self.run_sync()['reason'], 'older_than_configured_baseline')

    def test_prerelease_manifest_rejected(self):
        self.http.feed['versionName'] = '3.6.1-rc.1'
        self.http.feed['tag'] = 'v3.6.1-rc.1'
        with self.assertRaises(ValueError):
            self.run_sync()

    def test_foreign_repository_url_rejected(self):
        self.http.feed['apk']['url'] = self.http.feed['apk']['url'].replace('/lhly/', '/other/')
        with self.assertRaises(sync.SyncError):
            self.run_sync()

    def test_corrupt_artifact_never_uploaded(self):
        self.http.contents['LICENSE'] = b'corrupted'
        with self.assertRaises(sync.SyncError):
            self.run_sync()
        self.assertEqual(self.quark.checked, 0)
        self.assertEqual(self.quark.uploads, [])

    def test_checksum_path_traversal_rejected(self):
        self.http.contents['SHA256SUMS'] += (('a' * 64) + '  ../secret\n').encode()
        with self.assertRaises(sync.SyncError):
            self.run_sync()

    def test_duplicate_checksums_rejected(self):
        self.http.contents['SHA256SUMS'] += self.http.contents['SHA256SUMS'].splitlines(keepends=True)[0]
        with self.assertRaises(sync.SyncError):
            self.run_sync()

    def test_unsigned_extra_file_in_cache_rejected(self):
        self.run_sync()
        self.state.unlink()
        (Path(self.config['publish_dir']) / 'v3.6.0' / 'credentials.json').write_text('not a release asset')
        with self.assertRaises(sync.SyncError):
            self.run_sync()

    def test_second_process_cannot_take_lock(self):
        with sync.locked(self.root / 'sync.lock'):
            with self.assertRaises(sync.SyncError):
                with sync.locked(self.root / 'sync.lock'):
                    self.fail('second lock should not succeed')

    def test_launch_job_has_no_gui_or_credentials(self):
        import sys
        config = {'node_path': sys.executable, 'python_path': sys.executable, 'interval_seconds': 900}
        job = definition(self.root / 'config.json', config)
        self.assertEqual(job['StartInterval'], 900)
        self.assertTrue(job['RunAtLoad'])
        self.assertEqual(job['ProgramArguments'][2], 'run')
        self.assertNotIn('token', json.dumps(job).lower())
        self.assertNotIn('osascript', json.dumps(job))

    def test_launch_job_preserves_creating_codex_context_without_credentials(self):
        import sys
        config = {'node_path': sys.executable, 'python_path': sys.executable}
        with patch.dict(os.environ, {'CODEX_CI': '1', 'CODEX_THREAD_ID': 'private-thread',
                                     'GH_TOKEN': 'private-token'}, clear=True):
            job = definition(self.root / 'config.json', config)
        self.assertEqual(job['EnvironmentVariables']['CODEX_CI'], '1')
        self.assertEqual(set(job['EnvironmentVariables']), {'PATH', 'CODEX_CI'})
        self.assertNotIn('private-', json.dumps(job))

    def test_launch_job_does_not_invent_codex_context(self):
        import sys
        config = {'node_path': sys.executable, 'python_path': sys.executable}
        with patch.dict(os.environ, {}, clear=True):
            job = definition(self.root / 'config.json', config)
        self.assertNotIn('CODEX_CI', job['EnvironmentVariables'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
