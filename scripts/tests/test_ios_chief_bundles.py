import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SCRIPT=Path(__file__).resolve().parents[1]/'verify-ios-chief-bundles.py'
spec=importlib.util.spec_from_file_location('chief_bundle_verifier',SCRIPT)
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class OriginalBundleCaptureValidation(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)/'repo';self.capture=Path(self.temp.name)/'capture'
        (self.root/'scripts').mkdir(parents=True);(self.root/'third_party/cmux-chief').mkdir(parents=True)
        (self.capture/'observations').mkdir(parents=True)
        self.commit='a'*40
        digest=lambda value:hashlib.sha256(value.encode()).hexdigest()
        for name in ['run-ios-chief-bundles.py','native-chief-bundle-probe.swift']:
            (self.root/'scripts'/name).write_text(name)
        helper='original-helper';(self.capture/'OriginalL10n.swift').write_text(helper)
        (self.capture/'Probe.swift').write_text('native-chief-bundle-probe.swift')
        self.manifest=dict(revision='b'*40,nativeBundleFiles={'Project':digest('project')},questionPresentationFiles={},
            homeStrings=dict(catalogs=[],unreadHelper=dict(path='L10n',sha256=digest(helper))),questionStrings=dict(path='Catalog',sha256=digest('catalog'),keys={'chief.title':'title','mobile.workspace.unreadCountFormat.one':'one','mobile.workspace.unreadCountFormat.other':'other'}))
        self.write(self.root/'third_party/cmux-chief/manifest.json',self.manifest)
        self.provenance=dict(schema=2,projectCommit=self.commit,revision='b'*40,sourceSha256=dict(Project=digest('project'),L10n=digest(helper),Catalog=digest('catalog')),
            generatorSha256=module.sha(self.root/'scripts/run-ios-chief-bundles.py'),generatedSha256={p.name:module.sha(p) for p in self.capture.glob('*.swift')},
            mainKeys=['chief.title','mobile.workspace.unreadCountFormat.one','mobile.workspace.unreadCountFormat.other'],moduleKeys=['mobile.chief.error.example'],languages=['en','ja','de','es','fr','ar','ko','zh-Hans','zh-Hant'])
        self.write(self.capture/'Provenance.json',self.provenance)
        self.product=dict(bundleID='app.original',originalTrackedInputsUnchanged=True,executableSha256=digest('executable'),infoSha256=digest('info'),probeLibrarySha256=digest('dylib'),resourceSha256={'en.lproj/Localizable.strings':digest('resources')})
        self.write(self.capture/'build-product.json',self.product)
        for language in self.provenance['languages']:
            row=dict(schema=2,requested=language,bundleID='app.original',mainBundle='/native/Original.app',preferredLanguages=[language],
                mainPreferred=[language],modulePreferred=[language],moduleCandidates=['/native/Original.app/ShellUI.bundle'],selectedModule='/native/Original.app/ShellUI.bundle',
                mainValues={'chief.title':'Chief'},defaults={'chief.title':'Chief'},mainRaw={'chief.title':module.MISSING,'mobile.chief.error.example':module.MISSING},
                moduleRaw={'chief.title':'Title '+language,'mobile.chief.error.example':'Error '+language})
            row['unreadLabels']={'nil':'Unread','0':'Unread','1':'1 unread','2':'2 unread','17':'17 unread','1234':'1234 unread'}
            for key,value in [('mobile.workspace.unreadCountFormat.one','1 unread'),('mobile.workspace.unreadCountFormat.other','%d unread')]:
                for name in ['mainValues','defaults','mainRaw']:row[name][key]=value
                row['moduleRaw'][key]=module.MISSING
            row.update({key:self.product[key] for key in ['executableSha256','infoSha256','resourceSha256']})
            self.write(self.capture/'observations'/(language+'.json'),row)

    @staticmethod
    def write(path,value):path.write_text(json.dumps(value))

    def verify(self):return module.verify(self.capture,self.commit,self.root)

    def test_accepts_observed_fallback_without_assuming_main_catalog_ownership(self):
        receipt=self.verify()
        self.assertEqual(9,receipt['languages']);self.assertEqual(27,receipt['mainQueries']);self.assertEqual(36,receipt['moduleQueries'])
        self.assertEqual(['chief.title'],receipt['rows'][1]['mainFallback'])

    def test_accepts_actual_main_resolution_instead_of_forcing_fallback(self):
        path=self.capture/'observations/ja.json';row=json.loads(path.read_text())
        row['mainRaw']['chief.title']='Native main title';row['mainValues']['chief.title']='Native main title';self.write(path,row)
        self.assertIn('chief.title',self.verify()['rows'][1]['mainResolved'])

    def test_rejects_missing_formatted_unread_observations(self):
        path=self.capture/'observations/ja.json';row=json.loads(path.read_text());del row['unreadLabels']['17'];self.write(path,row)
        with self.assertRaises(AssertionError):self.verify()

    def test_rejects_wrong_original_revision(self):
        self.provenance['revision']='c'*40;self.write(self.capture/'Provenance.json',self.provenance)
        with self.assertRaises(AssertionError):self.verify()

    def test_rejects_changed_probe_or_source(self):
        (self.capture/'Probe.swift').write_text('different')
        with self.assertRaises(AssertionError):self.verify()

    def test_rejects_missing_locale(self):
        (self.capture/'observations/ja.json').unlink()
        with self.assertRaises(AssertionError):self.verify()

    def test_rejects_wrong_bundle_locale_and_unobserved_fallback(self):
        path=self.capture/'observations/ja.json';original=json.loads(path.read_text())
        for field,value in [('bundleID','app.probe'),('preferredLanguages',['en']),('mainValues',{'chief.title':'invented'})]:
            with self.subTest(field=field):
                row=copy.deepcopy(original);row[field]=value;self.write(path,row)
                with self.assertRaises(AssertionError):self.verify()

    def test_rejects_module_outside_the_actual_app(self):
        path=self.capture/'observations/ja.json';row=json.loads(path.read_text());row['selectedModule']='/other/ShellUI.bundle';row['moduleCandidates']=[row['selectedModule']];self.write(path,row)
        with self.assertRaises(AssertionError):self.verify()

    def test_rejects_different_process_bytes_even_with_the_same_bundle_id(self):
        path=self.capture/'observations/ja.json';row=json.loads(path.read_text());row['executableSha256']='e'*64;self.write(path,row)
        with self.assertRaises(AssertionError):self.verify()

    def test_rejects_chinese_script_mismatch(self):
        path=self.capture/'observations/zh-Hans.json';row=json.loads(path.read_text());row['preferredLanguages']=['zh-Hant'];self.write(path,row)
        with self.assertRaises(AssertionError):self.verify()

    def test_rejects_invalid_executable_identity(self):
        self.product['executableSha256']='not-a-digest';self.write(self.capture/'build-product.json',self.product)
        with self.assertRaises(AssertionError):self.verify()

    def test_rejects_unbound_generated_path(self):
        self.provenance['generatedSha256']['../escape']='0'*64;self.write(self.capture/'Provenance.json',self.provenance)
        with self.assertRaises(AssertionError):self.verify()


if __name__=='__main__':unittest.main()
