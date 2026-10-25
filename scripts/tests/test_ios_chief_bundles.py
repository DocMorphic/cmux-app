import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import plistlib
import tempfile
import unittest
import zipfile

SCRIPT=Path(__file__).resolve().parents[1]/'verify-ios-chief-bundles.py'
spec=importlib.util.spec_from_file_location('chief_bundle_verifier',SCRIPT)
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class OriginalBundleCaptureValidation(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)/'repo';self.capture=Path(self.temp.name)/'capture'
        (self.root/'scripts').mkdir(parents=True);(self.root/'third_party/cmux-chief').mkdir(parents=True)
        (self.root/'tests/chief-home-search').mkdir(parents=True)
        (self.capture/'observations').mkdir(parents=True)
        self.commit='a'*40
        digest=lambda value:hashlib.sha256(value.encode()).hexdigest()
        for name in ['run-ios-chief-bundles.py','native-chief-bundle-probe.swift','native-chief-foundation-probe.swift']:
            (self.root/'scripts'/name).write_text(name)
        (self.root/'scripts/verify-ios-reference-bundle.py').write_bytes(SCRIPT.with_name('verify-ios-reference-bundle.py').read_bytes())
        helper='original-helper';(self.capture/'OriginalL10n.swift').write_text(helper)
        (self.capture/'Probe.swift').write_text('native-chief-bundle-probe.swift')
        (self.capture/'FoundationProbe.swift').write_text('native-chief-foundation-probe.swift')
        home='    private func matches(_ conversation: MobileChiefConversation) -> Bool {\n        return query.isEmpty\n    }\n\n    private func load() async {\n'
        client='public struct MobileChiefConversation: Codable {\n    let title: String\n}\n\n/// One compact transcript message\n'
        cases=[dict(id='one',title='Straße',preview='',query='strasse')]
        cases_raw=json.dumps(cases).encode()
        (self.root/'tests/chief-home-search/Cases.json').write_bytes(cases_raw)
        (self.capture/'HomeSearchCases.json').write_bytes(cases_raw)
        (self.capture/'OriginalChiefHome.swift').write_text(home)
        (self.capture/'OriginalMobileChiefClient.swift').write_text(client)
        (self.capture/'OriginalHomeSearch.swift').write_text(module.home_search_source(home,client,cases_raw))
        self.manifest=dict(revision='b'*40,nativeBundleFiles={'Project':digest('project')},questionPresentationFiles={module.HOME:digest(home)},files={module.CLIENT:digest(client)},
            homeStrings=dict(catalogs=[],unreadHelper=dict(path='L10n',sha256=digest(helper))),questionStrings=dict(path='Catalog',sha256=digest('catalog'),keys={'chief.title':'title','mobile.workspace.unreadCountFormat.one':'one','mobile.workspace.unreadCountFormat.other':'other'}))
        self.write(self.root/'third_party/cmux-chief/manifest.json',self.manifest)
        self.provenance=dict(schema=4,projectCommit=self.commit,revision='b'*40,sourceSha256={**dict(Project=digest('project'),L10n=digest(helper),Catalog=digest('catalog')),module.HOME:digest(home),module.CLIENT:digest(client)},
            bundleVerifierSha256=module.sha(self.root/'scripts/verify-ios-reference-bundle.py'),
            generatorSha256=module.sha(self.root/'scripts/run-ios-chief-bundles.py'),generatedSha256={p.name:module.sha(p) for p in self.capture.glob('*.swift')},
            mainKeys=['chief.title','mobile.workspace.unreadCountFormat.one','mobile.workspace.unreadCountFormat.other'],moduleKeys=['mobile.chief.error.example'],languages=['en','ja','de','es','fr','ar','ko','zh-Hans','zh-Hant'])
        self.write(self.capture/'Provenance.json',self.provenance)
        self.product=dict(bundleID='app.original',originalTrackedInputsUnchanged=True,executableSha256=digest('executable'),infoSha256=digest('info'),probeLibrarySha256=digest('dylib'),resourceSha256={'en.lproj/Localizable.strings':digest('resources')})
        info=plistlib.dumps(dict(CFBundleIdentifier='app.original',CFBundleExecutable='Original'))
        files={'Info.plist':info,'Original':b'executable','Original.debug.dylib':b'implementation','en.lproj/Localizable.strings':b'resources'}
        archive=self.capture/'Original.app.zip'
        with zipfile.ZipFile(archive,'w') as z:
            for name,data in files.items():z.writestr('Original.app/'+name,data)
        self.product.update(infoSha256=hashlib.sha256(info).hexdigest(),archive=archive.name,archiveSha256=module.sha(archive),
            bundleFilesSha256={name:hashlib.sha256(data).hexdigest() for name,data in files.items()},bundleSymlinks={},runnerSha256=module.sha(self.root/'scripts/run-ios-chief-bundles.py'))
        (self.capture/'libChiefBundleProbe.dylib').write_bytes(b'dylib')
        self.write(self.capture/'build-product.json',self.product)
        for language in self.provenance['languages']:
            row=dict(schema=4,requested=language,bundleID='app.original',mainBundle='/native/Original.app',preferredLanguages=[language],
                locale=module.LOCALES[language],
                mainPreferred=[language],modulePreferred=[language],moduleCandidates=['/native/Original.app/ShellUI.bundle'],selectedModule='/native/Original.app/ShellUI.bundle',
                mainValues={'chief.title':'Chief'},defaults={'chief.title':'Chief'},mainRaw={'chief.title':module.MISSING,'mobile.chief.error.example':module.MISSING},
                moduleRaw={'chief.title':'Title '+language,'mobile.chief.error.example':'Error '+language})
            row['unreadLabels']={'nil':'Unread','0':'Unread','1':'1 unread','2':'2 unread','17':'17 unread','1234':'1234 unread'}
            row['unreadLabels'].update({'2147483647':'2147483647 unread','2147483648':'-2147483648 unread','4294967296':'0 unread','9223372036854775807':'-1 unread'})
            row['homeSearch']=[dict(**cases[0],matches=True)]
            row['foundationProvider']=dict(schema=1,scalarQueries=1112064,caseMapType=3,flags=0,language=None,characterSets=dict(letter=5,graphemeExtend=110,whitespaceAndNewline=3),
                caseFold={'41':'a','df':'ss'},canonicalDecomposition={'e9':'e\u0301'},combiningClass={'301':230},
                letterRanges=[[65,90],[97,122]],graphemeExtendRanges=[[768,879]],whitespaceAndNewlineRanges=[[9,13],[32,32]],scope='Synthetic verifier fixture; not native provider evidence.') if language=='en' else None
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
        self.assertEqual(90,receipt['unreadQueries']);self.assertEqual(9,receipt['homeSearchQueries'])

    def test_rejects_changed_search_source_even_with_rehashed_generated_file(self):
        path=self.capture/'OriginalHomeSearch.swift';path.write_text(path.read_text().replace('return query.isEmpty','return true'))
        self.provenance['generatedSha256'][path.name]=module.sha(path);self.write(self.capture/'Provenance.json',self.provenance)
        with self.assertRaises(AssertionError):self.verify()

    def test_rejects_changed_original_home_even_with_rehashed_generated_file(self):
        path=self.capture/'OriginalChiefHome.swift';path.write_text(path.read_text()+'\n')
        self.provenance['generatedSha256'][path.name]=module.sha(path);self.write(self.capture/'Provenance.json',self.provenance)
        with self.assertRaises(AssertionError):self.verify()

    def test_rejects_search_case_or_result_shape_changes(self):
        path=self.capture/'observations/ja.json';original=json.loads(path.read_text())
        for field,value in [('id','other'),('query','different'),('matches',1)]:
            with self.subTest(field=field):
                row=copy.deepcopy(original);row['homeSearch'][0][field]=value;self.write(path,row)
                with self.assertRaises(AssertionError):self.verify()

    def test_rejects_missing_search_observation(self):
        path=self.capture/'observations/ja.json';row=json.loads(path.read_text());row['homeSearch']=[];self.write(path,row)
        with self.assertRaises(AssertionError):self.verify()

    def test_rejects_missing_wide_unread_observation(self):
        path=self.capture/'observations/ja.json';row=json.loads(path.read_text());del row['unreadLabels']['4294967296'];self.write(path,row)
        with self.assertRaises(AssertionError):self.verify()

    def test_rejects_wrong_search_locale_even_with_correct_preferred_language(self):
        path=self.capture/'observations/ja.json';row=json.loads(path.read_text());row['locale']='en_US';self.write(path,row)
        with self.assertRaises(AssertionError):self.verify()

    def test_rejects_replaced_case_corpus_even_when_rehashed(self):
        path=self.capture/'HomeSearchCases.json';path.write_text('[]')
        self.provenance['generatedSha256'][path.name]=module.sha(path);self.write(self.capture/'Provenance.json',self.provenance)
        with self.assertRaises(AssertionError):self.verify()

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

    def test_rejects_changed_provider_source_even_with_rehashed_capture(self):
        path=self.capture/'FoundationProbe.swift';path.write_text('invented provider')
        self.provenance['generatedSha256'][path.name]=module.sha(path);self.write(self.capture/'Provenance.json',self.provenance)
        with self.assertRaises(AssertionError):self.verify()

    def test_rejects_missing_or_malformed_provider_facts(self):
        path=self.capture/'observations/en.json';original=json.loads(path.read_text())
        for field,value in [('scalarQueries',1112063),('scalarQueries',True),('caseMapType',0),('flags',1),('language','en'),
            ('characterSets',dict(letter=5,graphemeExtend=8)),('caseFold',{'d800':'x'}),('caseFold',{'41':'A'}),
            ('canonicalDecomposition',{'00e9':'e\u0301'}),('combiningClass',{'301':True}),('combiningClass',{'301':0}),
            ('letterRanges',[[65,90],[90,122]]),('letterRanges',[[0,0x10FFFF]]),('graphemeExtendRanges',[])]:
            with self.subTest(field=field,value=value):
                row=copy.deepcopy(original);row['foundationProvider'][field]=value;self.write(path,row)
                with self.assertRaises(AssertionError):self.verify()
        row=copy.deepcopy(original);del row['foundationProvider'];self.write(path,row)
        with self.assertRaises(KeyError):self.verify()

    def test_rejects_provider_in_the_wrong_language_launch(self):
        en=json.loads((self.capture/'observations/en.json').read_text())
        path=self.capture/'observations/ja.json';row=json.loads(path.read_text());row['foundationProvider']=en['foundationProvider'];self.write(path,row)
        with self.assertRaises(AssertionError):self.verify()

    def test_rejects_replaced_probe_library(self):
        (self.capture/'libChiefBundleProbe.dylib').write_bytes(b'different')
        with self.assertRaises(AssertionError):self.verify()

    def test_rejects_replaced_app_archive(self):
        (self.capture/'Original.app.zip').write_bytes(b'different')
        with self.assertRaises(AssertionError):self.verify()


if __name__=='__main__':unittest.main()
