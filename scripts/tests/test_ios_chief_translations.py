"""Adversarial export guards; synthetic images are never native UI evidence."""
from contextlib import redirect_stdout
import copy, hashlib, importlib.util, io, json, tempfile, unittest
from pathlib import Path
from test_ios_chief_reference import png
ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('translation_verifier', ROOT/'scripts/verify-ios-chief-translations.py')
V = importlib.util.module_from_spec(spec); spec.loader.exec_module(V)

class TranslationExportGuards(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.export = Path(self.tmp.name)/'export'; self.export.mkdir(); self.output = Path(self.tmp.name)/'out'
        self.commit = 'a'*40
        template = ROOT/'tests/ios-chief-reference'
        fixture = json.loads((template/'TranslationReferences.json').read_text())
        manifest = json.loads((ROOT/'third_party/cmux-chief/manifest.json').read_text())
        provenance = dict(projectSourceCommit=self.commit, projectSourceDirty=False,
            upstreamRevision=manifest['revision'], packageTree=manifest['referencePackage']['tree'],
            referenceAppSha256=V.digest(template/'TranslationReferenceApp.swift'),
            translationUiTestsSha256=V.digest(template/'TranslationUITests.swift'),
            translationFixtureSha256=V.digest(template/'TranslationReferences.json'))
        for index, scene in enumerate(fixture['scenes']):
            name = scene['id']; mode = scene['mode']; translated = mode not in ('choice','from')
            w,h = (874,402) if scene['orientation']=='landscape' else (402,874)
            image = png(index,w,h); labels = [dict(frame=[16,90,250,70],fontSize=28,class_='ConversationEffectLabel')]
            labels[0]['class'] = labels[0].pop('class_')
            controls = []
            if translated:
                labels.append(dict(identifier='conversation.message.translation',frame=[16,164,250,20],fontSize=12))
            if mode=='menu': controls.append(dict(identifier='conversation.menu.translate',label='Show Original',frame=[16,200,250,42]))
            if mode=='conversation': controls.append(dict(identifier='conversation.translation.indicator',frame=[60,300,250,32]))
            presentations = []
            for row in fixture['rows']:
                original = fixture['sameLanguageText'] if mode=='from' and row['id']==scene['target'] else row['text']
                showing = translated and (row['id']==scene['target'] or mode=='conversation' and not row['outgoing'])
                presentations.append(dict(id=row['id'],original=original,
                    shown=original if not showing or mode=='original' else row['translated'],
                    caption=('viewTranslation' if mode=='original' else 'showOriginal') if showing else 'none'))
            value = dict(schema=1,scene=name,translationScene=name,fixture=scene,
                fixtureSha256=provenance['translationFixtureSha256'],provenance=provenance,
                captureMethod='XCUIScreen.main.screenshot',activation='physicalPressAndSourceControls',ready=True,
                categoryMatches=True,style=scene['style'],direction=scene['direction'],translator='controlled',
                backendWriteAttempts=0,viewport=[w,h],scale=1,controllerFrame=[0,0,w,h],
                labels=labels,controls=controls,presentations=presentations,translationCalls=int(translated),
                automatic=mode=='conversation',alertActions=(['Translate This Message','Translate Conversation','Cancel'] if mode=='choice' else ['French','Spanish','Cancel'] if mode=='from' else []),
                alertTitle='Translate From' if mode=='from' else '',imageSha256=hashlib.sha256(image).hexdigest())
            (self.export/(name+'.png')).write_bytes(image)
            (self.export/(name+'.json')).write_text(json.dumps(value))
    def verify(self):
        with redirect_stdout(io.StringIO()): V.verify(self.export,self.output,self.commit)
    def mutate(self, change, name='translation-incoming-dark'):
        path=self.export/(name+'.json'); value=json.loads(path.read_text());change(value);path.write_text(json.dumps(value))
    def test_complete_set_and_missing_scene(self):
        self.verify(); self.assertEqual(11,len(list(self.output.glob('*.png'))))
        (self.export/'translation-choice-dark.json').unlink()
        with self.assertRaisesRegex(ValueError,'Missing/unexpected'): self.verify()
    def test_duplicate_attachment_is_rejected(self):
        (self.export/'duplicate.json').write_bytes((self.export/'translation-incoming-dark.json').read_bytes())
        with self.assertRaisesRegex(ValueError,'Duplicate'): self.verify()
    def test_stale_commit_and_dirty_tree_are_rejected(self):
        for change in (dict(projectSourceCommit='b'*40),dict(projectSourceCommit=self.commit,projectSourceDirty=True)):
            with self.subTest(change=change):
                self.mutate(lambda v:v['provenance'].update(change))
                with self.assertRaisesRegex(ValueError,'Wrong/dirty'): self.verify()
    def test_template_drift_is_rejected(self):
        self.mutate(lambda v:v['provenance'].update(referenceAppSha256='b'*64))
        with self.assertRaisesRegex(ValueError,'templates changed'): self.verify()
    def test_wrong_capture_path_is_rejected(self):
        self.mutate(lambda v:v.update(captureMethod='drawHierarchy'))
        with self.assertRaisesRegex(ValueError,'compositor/physical'): self.verify()
    def test_actual_traits_are_required(self):
        self.mutate(lambda v:v.update(categoryMatches=False))
        with self.assertRaisesRegex(ValueError,'actual translation traits'): self.verify()
    def test_non_fullscreen_controller_is_rejected(self):
        self.mutate(lambda v:v.update(controllerFrame=[0,59,402,781]))
        with self.assertRaisesRegex(ValueError,'fill capture viewport'): self.verify()
    def test_original_mutation_is_rejected(self):
        self.mutate(lambda v:v['presentations'][0].update(original='translated storage'))
        with self.assertRaisesRegex(ValueError,'original was changed'): self.verify()
    def test_automatic_must_not_translate_outgoing(self):
        self.mutate(lambda v:v['presentations'][1].update(shown='wrong outgoing'), 'translation-conversation-dark')
        with self.assertRaisesRegex(ValueError,'wrong senders'): self.verify()
    def test_missing_caption_geometry_is_rejected(self):
        self.mutate(lambda v:v['labels'].pop())
        with self.assertRaisesRegex(ValueError,'visible translation caption'): self.verify()
    def test_real_accessibility_font_is_required(self):
        self.mutate(lambda v:v['labels'][0].update(fontSize=17),'translation-large-dark')
        with self.assertRaisesRegex(ValueError,'accessibility body type'): self.verify()
    def test_inference_before_choice_is_rejected(self):
        self.mutate(lambda v:v.update(translationCalls=1),'translation-choice-dark')
        with self.assertRaisesRegex(ValueError,'before admission'): self.verify()
    def test_wrong_source_picker_is_rejected(self):
        self.mutate(lambda v:v.update(alertActions=['English','Spanish','Cancel']),'translation-from-dark')
        with self.assertRaisesRegex(ValueError,'action sheet'): self.verify()
    def test_changed_image_is_rejected(self):
        (self.export/'translation-incoming-dark.png').write_bytes(png(250,402,874))
        with self.assertRaisesRegex(ValueError,'Missing/changed compositor'): self.verify()
    def test_backend_write_is_rejected(self):
        self.mutate(lambda v:v.update(backendWriteAttempts=1))
        with self.assertRaisesRegex(ValueError,'translator/backend'): self.verify()
    def test_nonfinite_viewport_is_rejected(self):
        self.mutate(lambda v:v.update(scale=float('inf')))
        with self.assertRaisesRegex(ValueError,'viewport'): self.verify()

if __name__=='__main__': unittest.main()
