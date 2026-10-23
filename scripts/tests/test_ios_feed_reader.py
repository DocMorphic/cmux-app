"""Synthetic integrity guards only; these images never establish original UI parity."""
from contextlib import redirect_stdout
from copy import deepcopy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from test_ios_chief_reference import png

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('feed_sheet_verifier', ROOT/'scripts/verify-ios-feed-reader.py')
V = importlib.util.module_from_spec(spec); spec.loader.exec_module(V)

class FeedSheetExportGuards(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.export = Path(self.tmp.name)/'export'; self.export.mkdir()
        self.output = Path(self.tmp.name)/'canonical'; self.commit = 'a'*40
        self.fixture = json.loads((V.TEMPLATES/'References.json').read_text())
        provenance = dict(V.reviewed_provenance(), projectSourceCommit=self.commit, projectSourceDirty=False)
        for index, scene in enumerate(self.fixture['scenes']):
            size = [160,120] if scene['orientation']=='landscape' else [120,160]
            pages = []
            for i, stage in enumerate(self.fixture['states']):
                raw = png(index*4+i, *size); (self.export/(scene['id']+'-'+stage+'.png')).write_bytes(raw)
                closed = stage=='closed'; language=scene['language']
                def control(label): return dict(exists=True, hittable=True, label=label, frame=[16,40,90,20])
                expected = 'Open reference sheet' if closed else self.fixture['probes']['top'] if stage=='loaded' else self.fixture['uiStrings'][stage if stage=='loading' else 'unavailable'][language]
                pages.append(dict(schema=1, referenceKind='originalFeedFullTextObservation', context='presentedOriginalFullTextSheet',
                    scene=scene['id'], stage=stage, probe='root' if closed else 'sheet', mode='loading' if stage=='loading' else 'retry',
                    provenance=provenance, ready=True, categoryMatches=True, device=scene['device'], style=scene['style'],
                    interfaceLevel=0 if closed else 1, sourceSessionCreated=False, interceptedURLs=[], platform='iOS',
                    runtimeVersion='Synthetic guard only', swiftUIDirection='rtl' if language=='ar' else 'ltr',
                    swiftUIAccessibilitySize=scene['category']!='large',
                    category='UICTContentSizeCategoryL' if scene['category']=='large' else 'UICTContentSizeCategoryAccessibilityM',
                    bodyPointSize=17 if scene['category']=='large' else 28, shellLocalizations=[language], preferredLanguages=[language],
                    viewport=size, scale=1, safeArea=[0,0,0,0], colors={k:[0,0,0,1] for k in ['background','label','secondaryLabel','tertiaryLabel','link','separator']},
                    loadAttempts=1 if stage in ['loading','error'] else 2, dismissed=closed, loadCancellations=0,
                    target=control('In progress, '+expected if stage=='loading' else expected), loadingActivityIndicator=stage=='loading', close=dict(exists=False) if closed else control(self.fixture['uiStrings']['close'][language]),
                    title=control(self.fixture['uiStrings']['title'][language]), unexpectedSourceControl=False,
                    navigationBars=[[0,30,size[0],54]], navigationLabels=[dict(text=self.fixture['uiStrings']['title'][language],
                        pointSize=17 if scene['category']=='large' else 21, lineHeight=21 if scene['category']=='large' else 26,
                        fontName='Synthetic integrity guard only', adjustsForContentSizeCategory=True, frame=[16,40,90,20])],
                    visibleLabels=[dict(text=self.fixture['uiStrings']['close'][language], pointSize=17, lineHeight=21,
                        fontName='Synthetic control integrity guard only', adjustsForContentSizeCategory=True, frame=[16,40,90,20])], visibleButtons=[],
                    captureMethod='XCUIScreen.main.screenshot', imageSha256=hashlib.sha256(raw).hexdigest()))
            dismissal=deepcopy(pages[0]); dismissal.update(probe='root', dismissed=True, loadCancellations=1)
            (self.export/(scene['id']+'.json')).write_text(json.dumps(dict(schema=1, referenceKind='originalFeedFullTextScene',
                scene=scene['id'], pages=pages, loadingDismissal=dismissal)))
    def verify(self):
        with redirect_stdout(io.StringIO()): return V.verify(self.export,self.output,self.commit)
    def rejects(self, change, message):
        p=self.export/'en-dark.json'; d=json.loads(p.read_text()); change(d); p.write_text(json.dumps(d))
        with self.assertRaisesRegex(ValueError,message): self.verify()
        self.assertFalse(self.output.exists())
    def test_all_52_declared_sheet_states_associate(self): self.assertEqual(self.verify()['capturedPageCount'],52)
    def test_missing_scene_rejected(self):
        (self.export/'en-dark.json').unlink()
        with self.assertRaisesRegex(ValueError,'Missing/unexpected'): self.verify()
    def test_modified_original_root_rejected(self):
        self.rejects(lambda d:d['pages'][0]['provenance']['sourceFiles'].update({'Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/AgentFeedFullTextView.swift':'f'*64}),'Unreviewed')
    def test_modified_adapter_rejected(self):
        self.rejects(lambda d:d['pages'][0]['provenance']['templateHashes'].update({'OriginalSheetAdapter.swift':'f'*64}),'Unreviewed')
    def test_dirty_source_rejected(self): self.rejects(lambda d:d['pages'][0]['provenance'].update(projectSourceDirty=True),'dirty')
    def test_missing_title_rejected(self): self.rejects(lambda d:d['pages'][0]['title'].update(exists=False),'visible original')
    def test_source_control_rejected(self): self.rejects(lambda d:d['pages'][0].update(unexpectedSourceControl=True),'Source control')
    def test_missing_retry_transition_rejected(self): self.rejects(lambda d:d['pages'][2].update(loadAttempts=1),'load/retry')
    def test_missing_loading_cancellation_rejected(self): self.rejects(lambda d:d['loadingDismissal'].update(loadCancellations=0),'cancellation')
    def test_wrong_module_language_rejected(self): self.rejects(lambda d:d['pages'][0].update(shellLocalizations=['ar']),'localization')
    def test_invalid_interface_level_rejected(self): self.rejects(lambda d:d['pages'][0].update(interfaceLevel=True),'interface level')
    def test_adaptive_base_level_accepted(self):
        p=self.export/'en-landscape.json'; d=json.loads(p.read_text())
        for page in d['pages']:page['interfaceLevel']=0
        p.write_text(json.dumps(d)); self.verify()
    def test_missing_state_rejected(self): self.rejects(lambda d:d['pages'].pop(),'states')
    def test_reused_state_image_rejected(self): self.rejects(lambda d:d['pages'][1].update(imageSha256=d['pages'][0]['imageSha256']),'Reused')
    def test_changed_image_rejected(self): self.rejects(lambda d:d['pages'][0].update(imageSha256='f'*64),'Missing/changed')
    def test_wrong_dimensions_rejected(self): self.rejects(lambda d:d['pages'][0].update(scale=2),'dimensions')
    def test_offscreen_content_rejected(self): self.rejects(lambda d:d['pages'][0]['target'].update(frame=[16,155,90,20]),'outside viewport')

    def test_loading_static_label_can_be_visible_without_being_tappable(self):
        p=self.export/'en-dark.json'; d=json.loads(p.read_text()); d['pages'][0]['target']['hittable']=False
        p.write_text(json.dumps(d)); self.verify()
    def test_close_must_remain_tappable(self): self.rejects(lambda d:d['pages'][0]['close'].update(hittable=False),'visible original')

    def test_zero_frame_nested_loading_label_rejected(self): self.rejects(lambda d:d['pages'][0]['target'].update(frame=[0,0,0,0]),'visible original')
    def test_wrong_loading_role_rejected(self): self.rejects(lambda d:d['pages'][0].update(loadingActivityIndicator=False),'Activity Indicator')
    def test_missing_title_font_rejected(self): self.rejects(lambda d:d['pages'][0].update(navigationLabels=[]),'title font observation')
    def test_body_sized_toolbar_font_rejected(self): self.rejects(lambda d:d['pages'][0]['navigationLabels'][0].update(pointSize=28),'title typography')
    def test_title_font_ax_mismatch_rejected(self): self.rejects(lambda d:d['pages'][0]['navigationLabels'][0].update(frame=[20,40,90,20]),'AX target')
    def test_missing_navigation_bar_rejected(self): self.rejects(lambda d:d['pages'][0].update(navigationBars=[]),'navigation bar')
    def test_missing_public_observations_rejected(self): self.rejects(lambda d:d['pages'][0].pop('visibleLabels'),'public label/button')
    def test_invalid_public_font_rejected(self): self.rejects(lambda d:d['pages'][0]['visibleLabels'][0].update(pointSize=float('nan')),'public control font')
    def test_offscreen_public_label_rejected(self): self.rejects(lambda d:d['pages'][0]['visibleLabels'][0].update(frame=[200,40,90,20]),'outside viewport')
    def test_invalid_link_color_rejected(self): self.rejects(lambda d:d['pages'][0]['colors'].update(link=[0,0,0,2]),'sheet colors')
    def test_public_button_with_title_label_and_attributed_fonts_associates(self):
        p=self.export/'en-dark.json'; d=json.loads(p.read_text()); label=d['pages'][0]['visibleLabels'][0]
        d['pages'][0]['visibleButtons']=[dict(title=label['text'], accessibilityLabel=label['text'], frame=label['frame'],
            titleLabel=deepcopy(label), attributedFonts=[{key:label[key] for key in ['text','fontName','pointSize','lineHeight']}])]
        p.write_text(json.dumps(d)); r=self.verify()
        self.assertEqual(r['publicControlObservations']['en-dark-loading']['buttons'],1)
        self.assertEqual(len(r['publicControlObservations']['en-dark-loading']['closeLabelCandidates']),1)
    def test_invalid_button_font_rejected(self):
        self.rejects(lambda d:d['pages'][0].update(visibleButtons=[dict(title='Close', accessibilityLabel='Close',frame=[16,40,90,20],
            attributedFonts=[dict(text='Close',fontName='Synthetic invalid font',pointSize=17,lineHeight=0)])]),'public control font')
