import hashlib
import importlib.util
import json
from pathlib import Path
import plistlib
import stat
import tempfile
import unittest
import zipfile

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('reference_bundle',ROOT/'scripts/verify-ios-reference-bundle.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
sha=lambda b:hashlib.sha256(b).hexdigest()


class ReferenceBundleIdentity(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.info=plistlib.dumps(dict(CFBundleIdentifier='reference.test',CFBundleExecutable='Reference'))
        self.files={'Info.plist':self.info,'Reference':b'unchanged launcher','Reference.debug.dylib':b'compiled Swift implementation','en.lproj/Localizable.strings':b'labels'}
        self.product=dict(archive='app.zip',bundleID='reference.test',infoSha256=sha(self.info),executableSha256=sha(self.files['Reference']),
            bundleFilesSha256={n:sha(b) for n,b in self.files.items()},bundleSymlinks={},resourceSha256={'en.lproj/Localizable.strings':sha(b'labels')})
        self.write()
    def write(self,files=None,links=None,extras=None):
        with zipfile.ZipFile(self.root/'app.zip','w') as z:
            for name,data in (self.files if files is None else files).items():z.writestr('Reference.app/'+name,data)
            for name,target in (links or {}).items():
                entry=zipfile.ZipInfo('Reference.app/'+name);entry.create_system=3;entry.external_attr=(stat.S_IFLNK|0o777)<<16;z.writestr(entry,target)
            for name,data in (extras or {}).items():z.writestr(name,data)
        self.product['archiveSha256']=sha((self.root/'app.zip').read_bytes())
        (self.root/'build-product.json').write_text(json.dumps(self.product))
    def test_rehashes_implementation_alongside_unchanged_launcher(self):
        r=module.verify(self.root);self.assertEqual(['Reference.debug.dylib'],r['implementationDylibs']);self.assertEqual(4,r['regularFiles'])
    def test_rejects_changed_implementation_even_with_matching_outer_archive_and_launcher(self):
        files=dict(self.files);files['Reference.debug.dylib']=b'wrong implementation';self.write(files=files)
        with self.assertRaisesRegex(AssertionError,'Bundle file identity'):module.verify(self.root)
    def test_rejects_unlisted_extra_file(self):
        self.write(extras={'Reference.app/injected.dylib':b'extra'})
        with self.assertRaisesRegex(AssertionError,'Bundle file identity'):module.verify(self.root)
    def test_rejects_missing_implementation(self):
        files=dict(self.files);del files['Reference.debug.dylib'];self.write(files=files)
        with self.assertRaisesRegex(AssertionError,'Bundle file identity'):module.verify(self.root)
    def test_rejects_escaping_link_even_when_listed_in_manifest(self):
        self.product['bundleSymlinks']={'escape':'../../outside'};self.write(links=self.product['bundleSymlinks'])
        with self.assertRaisesRegex(AssertionError,'Escaping bundle link'):module.verify(self.root)
    def test_preserves_contained_framework_link_identity(self):
        self.product['bundleSymlinks']={'alias':'Reference.debug.dylib'};self.write(links=self.product['bundleSymlinks'])
        self.assertEqual(1,module.verify(self.root)['links'])
    def test_rejects_modified_archive_before_processing_bundle(self):
        with (self.root/'app.zip').open('ab') as stream:stream.write(b'changed')
        with self.assertRaisesRegex(AssertionError,'App archive digest'):module.verify(self.root)


if __name__=='__main__':unittest.main()
