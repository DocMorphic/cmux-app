import importlib.util
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
def module(name):
    spec=importlib.util.spec_from_file_location(name,ROOT/'scripts'/name);value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value
PREPARE=module('prepare-ios-signin-methods-reference.py')
class SignInSourceGuards(unittest.TestCase):
    def test_complete_original_declaration_includes_nested_closures_and_quoted_braces(self):
        source='before\n    var view: some View { VStack { Text("}"); Button { go() } label: { Text("ok") } } }\nafter'
        self.assertEqual(source.split('\n')[1],PREPARE.declaration(source,'    var view:'))
    def test_missing_or_truncated_original_declaration_is_rejected(self):
        with self.assertRaises(ValueError):PREPARE.declaration('struct X {}','var view:')
        with self.assertRaises(ValueError):PREPARE.declaration('var view: X { VStack { }','var view:')
    def test_generated_host_is_offline_and_original_imports_are_not_replaced_by_network_stubs(self):
        adapter=(ROOT/'tests/ios-signin-methods-reference/OriginalContext.swift').read_text()
        self.assertIn('/* ORIGINAL_MEMBERS */',adapter)
        self.assertNotIn('URLSession',adapter);self.assertNotIn('StackClientApp',adapter)
        self.assertIn('isRestoringSession',adapter)

VERIFY=module('verify-ios-signin-methods.py')
class SignInObservationGuards(unittest.TestCase):
    def nominal(self):
        scene=dict(id='idle',style='dark',device='phone',mode='idle',category='large',language='en')
        provenance=dict(projectSourceCommit='a'*40,projectSourceDirty=False)
        def control(name):return dict(identifier=name,enabled=True,frame=[10,20,300,50])
        value=dict(referenceKind='signinMethods',schema=1,scene='idle',provenance=provenance,ready=True,categoryMatches=True,
            platform='iOS',style='dark',device='phone',mode='idle',swiftUIAccessibilitySize=False,swiftUIDirection='ltr',
            bundleLocalizations=['en'],fixtureAuthService=True,sourceSessionCreated=False,captureMethod='XCUIScreen.main.screenshot',
            imageSha256='b'*64,providers=[control('signin.'+p) for p in ['apple','google','github']],email=control('Email'),request=control('signin.emailCode'))
        value['request']['enabled']=False
        return value,scene,provenance
    def test_real_control_shapes_and_clean_source_are_required(self):
        value,scene,provenance=self.nominal();VERIFY.validate(value,scene,provenance,'a'*40)
        for field in ['captureMethod','categoryMatches','fixtureAuthService']:
            bad={**value,field:None}
            with self.assertRaises(ValueError):VERIFY.validate(bad,scene,provenance,'a'*40)
        with self.assertRaises(ValueError):VERIFY.validate(value,scene,provenance,'c'*40)
    def test_busy_source_email_stays_enabled_and_provider_state_is_bound(self):
        value,scene,provenance=self.nominal();scene['mode']='google';value['mode']='google'
        for p in value['providers']:p['enabled']=False
        VERIFY.validate(value,scene,provenance,'a'*40)
        value['email']['enabled']=False
        with self.assertRaises(ValueError):VERIFY.validate(value,scene,provenance,'a'*40)
