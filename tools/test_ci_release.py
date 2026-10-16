#!/usr/bin/env python3
"""Regression checks for tag validation and signing credential handling."""

import os
import json
import hashlib
import zipfile
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from ci_release import prepare_signing, properties_value, release_metadata, write_private, verify_apk


class ReleaseChecks(unittest.TestCase):
    def check_legal_assets(self, license_content=b'complete GPL text', notice_content=b'upstream and maintainer credits', signature=None):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            (project / 'LICENSE').write_bytes(b'complete GPL text')
            (project / 'NOTICE').write_bytes(b'upstream and maintainer credits')
            apk = project / 'app.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('classes.dex', b'native code')
                if license_content is not None:
                    archive.writestr('assets/LICENSE', license_content)
                archive.writestr('assets/NOTICE', notice_content)
            metadata = release_metadata('versionName=3.3.2\nversionCode=2014\n', 'v3.3.2')
            def inspect(command, **kwargs):
                output = ((signature or 'Signer #1 certificate SHA-256 digest: ' + '1' * 64) if 'apksigner' in command[0]
                          else "package: name='com.tgwgroup.MiRearScreenSwitcher.personal' versionCode='2014' versionName='3.3.2'")
                return subprocess.CompletedProcess(command, 0, stdout=output)
            dist = project / 'dist'
            with patch.dict(os.environ, {'RELEASE_CERT_SHA256': '1' * 64, 'ANDROID_HOME': directory}), patch('ci_release.subprocess.run', side_effect=inspect):
                try:
                    verify_apk(apk, metadata, dist, project)
                except ValueError:
                    self.assertFalse(dist.exists(), 'Reject incomplete legal assets before staging any release.')
                    raise
            self.assertEqual((dist / 'LICENSE').read_bytes(), (project / 'LICENSE').read_bytes())
            self.assertEqual((dist / 'NOTICE').read_bytes(), (project / 'NOTICE').read_bytes())
            manifest = json.loads((dist / 'version-artifact.json').read_text())
            self.assertEqual(manifest['apk']['sha256'], hashlib.sha256((dist / metadata['apk_name']).read_bytes()).hexdigest())
            self.assertEqual(manifest['apk']['size'], (dist / metadata['apk_name']).stat().st_size)
            self.assertEqual(manifest['apk']['packageName'], 'com.tgwgroup.MiRearScreenSwitcher.personal')
            lines = (dist / 'SHA256SUMS').read_text().splitlines()
            self.assertEqual(len(lines), 3)
            for line in lines:
                digest, name = line.split('  ')
                self.assertEqual(digest, hashlib.sha256((dist / name).read_bytes()).hexdigest())

    def test_bundled_legal_texts_are_staged_with_verified_checksums(self):
        self.check_legal_assets()

    def test_missing_apk_license_prevents_release(self):
        with self.assertRaisesRegex(ValueError, 'complete current LICENSE'):
            self.check_legal_assets(license_content=None)

    def test_outdated_apk_credits_prevent_release(self):
        with self.assertRaisesRegex(ValueError, 'complete current NOTICE'):
            self.check_legal_assets(notice_content=b'old credits')

    def test_build_tools_37_signature_format(self):
        self.check_legal_assets(signature='V2 Signer: certificate SHA-256 digest: ' + '1' * 64 + '\nV3.0 Signer: certificate SHA-256 digest: ' + '1' * 64)

    def test_different_certificate_in_another_signature_scheme_prevents_release(self):
        with self.assertRaisesRegex(ValueError, 'exactly the expected'):
            self.check_legal_assets(signature='V2 Signer: certificate SHA-256 digest: ' + '1' * 64 + '\nV3.0 Signer: certificate SHA-256 digest: ' + '2' * 64)

    def test_stable_and_prerelease(self):
        stable = release_metadata('versionName=3.2.1\nversionCode=2011\n', 'v3.2.1')
        self.assertEqual(stable['build_number'], '2011')
        self.assertEqual(stable['prerelease'], 'false')
        prerelease = release_metadata('versionName=3.2.2-rc.1\nversionCode=2012\n', 'v3.2.2-rc.1')
        self.assertEqual(prerelease['prerelease'], 'true')
        self.assertEqual(prerelease['apk_name'], 'MRSS-v3.2.2-rc.1.apk')

    def test_mismatched_tag(self):
        with self.assertRaisesRegex(ValueError, 'must match'):
            release_metadata('versionName=3.2.1\nversionCode=2011\n', 'v3.2.2')

    def test_unsafe_or_invalid_tags(self):
        for tag in ['3.2.1', 'v03.2.1', 'v3.2.1-01', 'v3.2.1-rc..1',
                    'v3.2.1+2011', 'v3.2.1;echo bad', 'v3.2.1\nprerelease=false', 'v3.2.1１']:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release_metadata('versionName=3.2.1\nversionCode=2011\n', tag)

    def test_version_code_bounds(self):
        for code in ['0', '-1', '01', 'abc', '201１', '2100000001']:
            with self.subTest(code=code), self.assertRaises(ValueError):
                release_metadata(f'versionName=3.2.1\nversionCode={code}\n', 'v3.2.1')
        self.assertEqual(release_metadata('versionName=3.2.1\nversionCode=2100000000', 'v3.2.1')['build_number'], '2100000000')

    def test_missing_or_duplicate_version(self):
        for properties in ['name=mrss\n', 'versionName=3.2.1\n',
                        'versionName=3.2.1\nversionCode=2011\nversionCode=2012\n']:
            with self.subTest(properties=properties), self.assertRaises(ValueError):
                release_metadata(properties, 'v3.2.1')

    def test_missing_secrets_fail_without_writing_files(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {}, clear=True):
            project = Path(directory)
            with self.assertRaisesRegex(ValueError, 'Missing GitHub Actions secrets'):
                prepare_signing(project)
            self.assertEqual(list(project.iterdir()), [])

    def test_invalid_base64_does_not_echo_secret_or_write_files(self):
        secret = 'invalid!private-key-text'
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {
            'KEYSTORE_FILE': secret, 'KEYSTORE_PASSWORD': 'synthetic-password',
            'KEY_ALIAS': 'test', 'KEY_PASSWORD': 'synthetic-password',
            'RELEASE_CERT_SHA256': '1' * 64, 'RUNNER_TEMP': directory,
        }, clear=True):
            with self.assertRaises(ValueError) as caught:
                prepare_signing(Path(directory))
            self.assertNotIn(secret, str(caught.exception))
            self.assertEqual(list(Path(directory).iterdir()), [])

    def test_private_file_permissions_and_overwrite_protection(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'secret'
            write_private(path, b'original')
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            with self.assertRaises(FileExistsError):
                write_private(path, b'replacement')
            self.assertEqual(path.read_bytes(), b'original')

    def test_credentials_roundtrip_through_real_java_properties(self):
        # Java must recover literal punctuation, backslashes, whitespace and Unicode.
        java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
        self.assertTrue(java, 'JDK required')
        sample = ' leading:=#!\\tail\n\r\t末尾😀'
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            properties = folder / 'test.properties'
            properties.write_text('credential=' + properties_value(sample) + '\n', encoding='ascii')
            source = folder / 'ReadProperties.java'
            source.write_text('''import java.util.Properties; import java.nio.file.*;
public class ReadProperties { public static void main(String[] args) throws Exception {
Properties p = new Properties(); try (var input = Files.newInputStream(Path.of(args[0]))) { p.load(input); }
System.out.write(p.getProperty("credential").getBytes(java.nio.charset.StandardCharsets.UTF_8));
} }''')
            result = subprocess.run([java, str(source), str(properties)], capture_output=True, check=True)
            self.assertEqual(result.stdout.decode('utf-8'), sample)


if __name__ == '__main__':
    unittest.main(verbosity=2)
