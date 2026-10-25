import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('chief_home_prepare',ROOT/'scripts/prepare-ios-chief-home-reference.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class OriginalHomeHostPreparation(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.base=Path(self.temp.name);self.root=self.base/'repo';self.upstream=self.base/'upstream';self.out=self.base/'output'
        self.root.mkdir();self.upstream.mkdir()
        for root in [self.root,self.upstream]:
            self.command(root,'init','-q')
            self.command(root,'config','user.email','reference@example.invalid')
            self.command(root,'config','user.name','Reference test')
        shutil.copytree(ROOT/'tests/ios-chief-home-reference',self.root/'tests/ios-chief-home-reference')
        (self.root/'third_party/cmux-chief').mkdir(parents=True)
        paths={name:path+'/Package.swift' for name,path in module.PACKAGES.items()}
        for path in list(paths.values())+['Home.swift','Client.swift','Project','ios/cmux/Localizable.xcstrings']:
            target=self.upstream/path;target.parent.mkdir(parents=True,exist_ok=True);target.write_text('original '+path)
        self.command(self.upstream,'add','.');self.command(self.upstream,'commit','-qm','Original')
        self.revision=self.command(self.upstream,'rev-parse','HEAD').strip()
        digest=lambda path:hashlib.sha256((self.upstream/path).read_bytes()).hexdigest()
        self.manifest=dict(revision=self.revision,questionPresentationFiles={'Home.swift':digest('Home.swift')},
            nativeBundleFiles={'Project':digest('Project')},files={'Client.swift':digest('Client.swift')},
            homeStrings=dict(catalogs=[dict(path='ios/cmux/Localizable.xcstrings',sha256=digest('ios/cmux/Localizable.xcstrings'))]))
        self.write_manifest();self.command(self.root,'add','.');self.command(self.root,'commit','-qm','Reference')
        previous=module.ROOT;module.ROOT=self.root;self.addCleanup(setattr,module,'ROOT',previous)

    @staticmethod
    def command(root,*args):return subprocess.check_output(['git','-C',str(root),*args],text=True,stderr=subprocess.DEVNULL)
    def write_manifest(self):(self.root/'third_party/cmux-chief/manifest.json').write_text(json.dumps(self.manifest))
    def prepare(self):return module.prepare(self.upstream,self.out)

    def test_links_four_whole_original_packages_without_copying_or_editing_them(self):
        report=self.prepare()
        self.assertEqual(self.revision,report['revision']);self.assertFalse(report['projectSourceDirty'])
        self.assertEqual(set(module.PACKAGES),set(report['packages']))
        self.assertEqual('',self.command(self.upstream,'status','--porcelain'))
        project=(self.out/'ChiefHomeReference.xcodeproj/project.pbxproj').read_text()
        for name,path in module.PACKAGES.items():
            self.assertIn('../upstream/'+path,project);self.assertIn('productName = '+name+';',project)
            self.assertEqual(40,len(report['packages'][name]['tree']))
        self.assertIn('ENABLE_TESTABILITY = YES;',project)
        self.assertEqual((self.upstream/'ios/cmux/Localizable.xcstrings').read_bytes(),(self.out/'Localizable.xcstrings').read_bytes())
        scheme=ET.parse(self.out/'ChiefHomeReference.xcodeproj/xcshareddata/xcschemes/ChiefHomeReference.xcscheme')
        self.assertEqual('NO',scheme.find('.//TestableReference').attrib['parallelizable'])
        self.assertEqual('ChiefHomeUITests',scheme.find('.//TestableReference/BuildableReference').attrib['BlueprintName'])

    def test_rejects_wrong_original_checkout(self):
        self.manifest['revision']='0'*40;self.write_manifest()
        with self.assertRaises(AssertionError):self.prepare()

    def test_rejects_changed_original_sources(self):
        (self.upstream/'Home.swift').write_text('changed')
        with self.assertRaises(AssertionError):self.prepare()

    def test_rejects_unreviewed_original_source_hash(self):
        self.manifest['files']['Client.swift']='0'*64;self.write_manifest()
        with self.assertRaises(AssertionError):self.prepare()

    def test_rejects_changed_main_catalog_hash(self):
        self.manifest['homeStrings']['catalogs'][0]['sha256']='0'*64;self.write_manifest()
        with self.assertRaises(AssertionError):self.prepare()

    def test_never_overwrites_an_existing_reference_directory(self):
        self.out.mkdir();(self.out/'keep').write_text('saved')
        with self.assertRaises(FileExistsError):self.prepare()
        self.assertEqual('saved',(self.out/'keep').read_text())

    def test_records_dirty_reference_source_without_claiming_clean_execution(self):
        (self.root/'tests/ios-chief-home-reference/ReferenceApp.swift').write_text('different')
        self.assertTrue(self.prepare()['projectSourceDirty'])

    def test_fixture_covers_all_native_branches_without_replacing_the_view(self):
        fixtures=ROOT/'tests/ios-chief-home-reference'
        scenes=json.loads((fixtures/'Scenes.json').read_text())
        self.assertEqual(9,len(scenes));self.assertEqual(9,len({r['id'] for r in scenes}))
        self.assertEqual({'loading','empty','failure','pinned','recent','question'},{r['mode'] for r in scenes})
        source=(fixtures/'ReferenceApp.swift').read_text()
        self.assertIn('@testable import CmuxMobileShellUI',source)
        self.assertIn('ChiefHomeView(client: client)',source)
        self.assertNotIn('URLSession',source)
        self.assertNotIn('.debugLoopback',source)
        self.assertIn('route.kind == .iroh',source)
        self.assertIn('throw HomeReferenceError.tokenProviderForbidden',source)
        self.assertIn('request["auth"] == nil',source)
        self.assertIn('view.accessibilityIdentifier == "conversation.poll.option.__chief_answer_questions__"',source)
        self.assertIn('view.convert(view.bounds, to: window)',source)
        self.assertIn('params["wait_for_change"] as? Bool == true { continue }',source)
        self.assertIn('params["message"] as? String == "question-message"',source)


if __name__=='__main__':unittest.main()
