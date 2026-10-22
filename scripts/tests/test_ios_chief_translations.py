"""Adversarial export guards; synthetic images are never native UI evidence."""
from contextlib import redirect_stdout
import binascii, copy, hashlib, importlib.util, io, json, struct, tempfile, unittest
from pathlib import Path
from test_ios_chief_reference import png
ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('translation_verifier', ROOT/'scripts/verify-ios-chief-translations.py')
V = importlib.util.module_from_spec(spec); spec.loader.exec_module(V)

def exif_png(pixels, orientation, endian='>'):
    payload=(b'MM' if endian=='>' else b'II')+struct.pack(endian+'HI',42,8)
    payload+=struct.pack(endian+'HHHIH',1,274,3,1,orientation)+b'\x00\x00'+struct.pack(endian+'I',0)
    return exif_payload_png(pixels,payload)

def exif_payload_png(pixels,payload):
    chunk=struct.pack('>I',len(payload))+b'eXIf'+payload+struct.pack('>I',binascii.crc32(b'eXIf'+payload)&0xffffffff)
    return pixels[:-12]+chunk+pixels[-12:]

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

    def test_real_oriented_landscape_encoding_is_preserved(self):
        pixels=exif_png(png(200,402,874),6)
        name='translation-landscape-dark'
        (self.export/(name+'.png')).write_bytes(pixels)
        self.mutate(lambda v:v.update(imageSha256=hashlib.sha256(pixels).hexdigest()),name)
        self.verify()
        self.assertEqual(pixels,(self.output/(name+'.png')).read_bytes())
        geometry=json.loads((self.output/'receipt.json').read_text())['imageGeometry'][name]
        self.assertEqual(dict(encodedSize=[402,874],displaySize=[874,402],exifOrientation=6),geometry)

    def test_swapped_axes_without_orientation_still_fail(self):
        pixels=png(200,402,874);name='translation-landscape-dark'
        (self.export/(name+'.png')).write_bytes(pixels)
        self.mutate(lambda v:v.update(imageSha256=hashlib.sha256(pixels).hexdigest()),name)
        with self.assertRaisesRegex(ValueError,'image dimensions'):self.verify()

    def test_both_tiff_byte_orders_and_unmirrored_rotations(self):
        for endian in ('>','<'):
            for orientation in (1,3,6,8):
                with self.subTest(endian=endian,orientation=orientation):
                    geometry=V.png_geometry(exif_png(png(20,2,3),orientation,endian))
                    self.assertEqual([3,2] if orientation in (6,8) else [2,3],geometry['displaySize'])
                    self.assertEqual(orientation,geometry['exifOrientation'])

    def test_mirrored_and_invalid_orientation_is_rejected(self):
        for orientation in (0,2,4,5,7,9):
            with self.subTest(orientation=orientation),self.assertRaisesRegex(ValueError,'orientation'):
                V.png_geometry(exif_png(png(20),orientation))

    def test_duplicate_or_truncated_exif_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'Duplicate compositor EXIF'):
            V.png_geometry(exif_png(exif_png(png(20),1),1))
        with self.assertRaisesRegex(ValueError,'EXIF directory'):
            V.png_geometry(exif_payload_png(png(20),b'MM'+struct.pack('>HI',42,1000)))

    def test_png_checksums_and_complete_pixels_are_required(self):
        pixels=bytearray(png(20));pixels[29]^=1
        with self.assertRaisesRegex(ValueError,'checksum'):V.png_geometry(bytes(pixels))
        with self.assertRaisesRegex(ValueError,'Incomplete'):V.png_geometry(png(20)[:-12])

if __name__=='__main__': unittest.main()
