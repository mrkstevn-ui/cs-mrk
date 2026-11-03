import importlib.util
from pathlib import Path
import ssl
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('updater', Path(__file__).resolve().parents[1] / 'scripts/update_domains.py')
u = importlib.util.module_from_spec(spec)
spec.loader.exec_module(u)

class DomainTests(unittest.TestCase):
    def test_tls_verification_enabled(self):
        self.assertEqual(u.SSL_CTX.verify_mode, ssl.CERT_REQUIRED)
        self.assertTrue(u.SSL_CTX.check_hostname)

    def test_reject_unsafe_origins(self):
        for url in ['http://example.com', 'https://user:pass@example.com', 'https://127.0.0.1', 'https://localhost', 'https://example.com:8443', 'https://example.com/path']:
            with self.subTest(url=url), self.assertRaises(ValueError):
                u.normalize_origin(url)

    def test_redirect_allowlist(self):
        handler = u.SafeRedirectHandler({'example.com'})
        req = u.urllib.request.Request('https://example.com')
        for target in ['http://example.com', 'https://evil.example', 'https://example.com.evil.test']:
            with self.assertRaises(ValueError):
                handler.redirect_request(req, None, 302, 'Found', {}, target)

    def test_source_update_rejects_missing_main_url(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'Provider.kt'
            path.write_text('class Provider {}')
            with self.assertRaises(ValueError):
                u.update_kotlin_file(str(path), 'https://example.com')

    def test_source_update_only_changes_main_url(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'Provider.kt'
            path.write_text('override var mainUrl = "https://old.example"\nval api = "https://cdn.example"')
            self.assertTrue(u.update_kotlin_file(str(path), 'https://new.example'))
            self.assertIn('val api = "https://cdn.example"', path.read_text())

if __name__ == '__main__':
    unittest.main()

class UpdateIntegrationTests(unittest.TestCase):
    def test_shared_plugin_version_bumped_once_and_stale_source_synced(self):
        import json
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'build.gradle.kts').write_text('version = 24\n')
            config = {}
            for name in ('One', 'Two'):
                (root / (name + '.kt')).write_text('override var mainUrl = "https://old.example"')
                config[name] = dict(file=name + '.kt', gradle='build.gradle.kts', current_url='https://new.example', mirrors=[], verification_keyword=name)
            (root / 'domains.json').write_text(json.dumps(config))
            with patch.object(u, 'test_url', return_value=(True, 'https://new.example')):
                self.assertFalse(u.run(root, apply=True))
            self.assertEqual((root / 'build.gradle.kts').read_text().strip(), 'version = 25')
            for name in config:
                self.assertIn('https://new.example', (root / (name + '.kt')).read_text())

    def test_failed_check_preserves_all_files(self):
        import json
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'P.kt').write_text('override var mainUrl = "https://old.example"')
            (root / 'build.gradle.kts').write_text('version = 24\n')
            config = {'P': dict(file='P.kt', gradle='build.gradle.kts', current_url='https://old.example', mirrors=[], verification_keyword='provider')}
            (root / 'domains.json').write_text(json.dumps(config))
            before = {p.name: p.read_bytes() for p in root.iterdir()}
            with patch.object(u, 'test_url', return_value=(False, None)):
                self.assertTrue(u.run(root, apply=True))
            self.assertEqual(before, {p.name: p.read_bytes() for p in root.iterdir()})
