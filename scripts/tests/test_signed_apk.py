import importlib.util
from pathlib import Path
import unittest
import hashlib
import io
import json
import tempfile
import zipfile

spec = importlib.util.spec_from_file_location("signed_apk", Path(__file__).resolve().parents[1] / "verify-signed-apk.py")
signed_apk = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signed_apk)


def manifest(body):
    return '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application>' + body + '</application></manifest>'


class SignedApkFixtureTest(unittest.TestCase):
    def test_expanding_inventory_checks_every_activity_without_a_fixed_count(self):
        source = manifest(''.join(f'<activity android:name=".Fixture{i}" />' for i in range(9)))
        names = signed_apk.verify_debug_fixture_exclusion(source, 'io.github.docmorphic.cmuxapp.MainActivity')
        self.assertEqual(9, len(names))
        for name in names:
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "exclusion failed"):
                signed_apk.verify_debug_fixture_exclusion(source, name)

    def test_relative_short_qualified_and_alias_names_are_checked(self):
        source = manifest('''<activity android:name='.Relative' /><activity android:name='Short' />
            <activity android:name='other.package.Fixture' />
            <activity-alias android:name='.Alias' android:targetActivity='.MainActivity' />''')
        names = signed_apk.verify_debug_fixture_exclusion(source, '')
        self.assertEqual(['io.github.docmorphic.cmuxapp.Relative', 'io.github.docmorphic.cmuxapp.Short',
                         'other.package.Fixture', 'io.github.docmorphic.cmuxapp.Alias'], names)
        with self.assertRaisesRegex(ValueError, "exclusion failed"):
            signed_apk.verify_debug_fixture_exclusion(source, 'other.package.Fixture')

    def test_provider_service_and_receiver_fixtures_are_also_excluded(self):
        source = manifest('<provider android:name=".ProviderFixture"/>'
                          '<service android:name=".ServiceFixture"/>'
                          '<receiver android:name=".ReceiverFixture"/>')
        names = signed_apk.verify_debug_fixture_exclusion(source, '')
        self.assertEqual(3, len(names))
        for name in names:
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "exclusion failed"):
                signed_apk.verify_debug_fixture_exclusion(source, name)

    def test_packaged_assets_include_office_and_reject_missing_or_corrupt_bundles(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folders = ("raw-code", "markdown-viewer", "docx-viewer", "workbook-viewer")
            for folder in folders:
                (root / folder).mkdir()
                files = {"library.js": hashlib.sha256(folder.encode()).hexdigest()}
                (root / folder / "manifest.json").write_text(json.dumps({"files": files}))
            def packed(corrupt=None, missing=None):
                data = io.BytesIO()
                with zipfile.ZipFile(data, 'w') as archive:
                    for folder in folders:
                        if folder != missing:
                            archive.writestr(f"assets/{folder}/library.js", b'wrong' if folder == corrupt else folder.encode())
                data.seek(0)
                return zipfile.ZipFile(data)
            with packed() as archive:
                self.assertEqual(4, signed_apk.verify_packaged_viewer_assets(root, archive))
            for folder in folders:
                with self.subTest(folder=folder), packed(corrupt=folder) as archive:
                    with self.assertRaisesRegex(ValueError, folder):
                        signed_apk.verify_packaged_viewer_assets(root, archive)
                with self.subTest(missing=folder), packed(missing=folder) as archive:
                    with self.assertRaises(KeyError):
                        signed_apk.verify_packaged_viewer_assets(root, archive)

    def test_missing_ambiguous_or_unresolved_inventory_fails_closed(self):
        for source in ['<manifest/>', manifest(''), manifest('<activity/>'),
                       manifest('<activity android:name="${fixture}"/>'),
                       manifest('<activity android:name=".Same"/><activity android:name="Same"/>')]:
            with self.subTest(source=source), self.assertRaises(ValueError):
                signed_apk.verify_debug_fixture_exclusion(source, '')


if __name__ == '__main__':
    unittest.main()
