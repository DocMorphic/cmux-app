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
                def layout(): return dict(className='Synthetic navigation layout integrity guard only',
                    frame=[0,30,size[0],54], bounds=[0,0,size[0],54], safeArea=[0,0,0,0], margins=[0,16,0,16],
                    directionalMargins=[0,16,0,16], guideFrame=[16,0,size[0]-32,54],
                    effectiveDirection=1 if language=='ar' else 0, preservesSuperviewMargins=False,
                    insetsMarginsFromSafeArea=True, horizontalSizeClass=1, verticalSizeClass=1 if scene['orientation']=='landscape' else 2)
                bar=layout(); bar['children']=[layout()]
                chrome=dict(sceneOrientation=3 if scene['orientation']=='landscape' else 1,
                    horizontalSizeClass=1, verticalSizeClass=1 if scene['orientation']=='landscape' else 2,
                    statusBar=dict(available=True, hidden=scene['orientation']=='landscape',
                        frameInScene=[0,0,0,0] if scene['orientation']=='landscape' else [0,0,size[0],20], style=0))
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
                    windowChrome=chrome, navigationBarMetrics=[] if closed else [bar], navigationBars=[] if closed else [[0,30,size[0],54]], navigationLabels=[dict(text=self.fixture['uiStrings']['title'][language],
                        pointSize=17 if scene['category']=='large' else 21, lineHeight=21 if scene['category']=='large' else 26,
                        fontName='Synthetic integrity guard only', adjustsForContentSizeCategory=True, frame=[16,40,90,20])],
                    visibleLabels=[dict(text=self.fixture['uiStrings']['close'][language], pointSize=17, lineHeight=21,
                        fontName='Synthetic control integrity guard only', adjustsForContentSizeCategory=True, frame=[16,40,90,20])], visibleButtons=[],
                    captureMethod='XCUIScreen.main.screenshot', imageSha256=hashlib.sha256(raw).hexdigest()))
            dismissal=deepcopy(pages[0]); dismissal.update(probe='root', dismissed=True, loadCancellations=1,
                close=dict(exists=False), navigationBars=[], navigationLabels=[], navigationBarMetrics=[], visibleLabels=[])
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

    def test_window_chrome_and_navigation_layout_receipts_are_complete(self):
        result=self.verify()
        self.assertEqual(len(result['windowChromeObservations']),52)
        self.assertEqual(len(result['navigationLayoutObservations']),52)
        self.assertEqual(len(result['loadingDismissalWindowChrome']),13)
    def test_missing_window_chrome_rejected(self): self.rejects(lambda d:d['pages'][0].pop('windowChrome'),'window chrome')
    def test_invalid_scene_orientation_rejected(self): self.rejects(lambda d:d['pages'][0]['windowChrome'].update(sceneOrientation=True),'scene orientation')
    def test_invalid_scene_size_class_rejected(self): self.rejects(lambda d:d['pages'][0]['windowChrome'].update(verticalSizeClass=9),'size class')
    def test_unavailable_status_manager_rejected(self): self.rejects(lambda d:d['pages'][0]['windowChrome']['statusBar'].update(available=False),'status-bar manager')
    def test_nonboolean_hidden_status_rejected(self): self.rejects(lambda d:d['pages'][0]['windowChrome']['statusBar'].update(hidden=1),'status-bar manager')
    def test_invalid_status_frame_rejected(self): self.rejects(lambda d:d['pages'][0]['windowChrome']['statusBar'].update(frameInScene=[0,0,-1,0]),'status-bar frame')
    def test_invalid_status_style_rejected(self): self.rejects(lambda d:d['pages'][0]['windowChrome']['statusBar'].update(style=True),'status-bar style')
    def test_observed_hidden_empty_manager_raw_minus_one_is_retained(self):
        p=self.export/'en-landscape.json'; d=json.loads(p.read_text())
        for page in d['pages']+[d['loadingDismissal']]:page['windowChrome']['statusBar']['style']=-1
        p.write_text(json.dumps(d));r=self.verify()
        self.assertEqual(r['windowChromeObservations']['en-landscape-loaded']['statusBar']['style'],-1)
        self.assertEqual(r['loadingDismissalWindowChrome']['en-landscape']['statusBar']['style'],-1)
    def test_raw_minus_one_visible_status_is_rejected(self): self.rejects(lambda d:d['pages'][0]['windowChrome']['statusBar'].update(style=-1),'status-bar style')
    def test_raw_minus_one_hidden_nonempty_status_is_rejected(self): self.rejects(lambda d:d['pages'][0]['windowChrome']['statusBar'].update(style=-1,hidden=True),'status-bar style')
    def test_other_unknown_hidden_style_is_rejected(self): self.rejects(lambda d:d['pages'][0]['windowChrome']['statusBar'].update(style=-2,hidden=True,frameInScene=[0,0,0,0]),'status-bar style')
    def test_missing_navigation_metrics_rejected(self): self.rejects(lambda d:d['pages'][0].pop('navigationBarMetrics'),'navigation layout')
    def test_navigation_metric_frame_binding_rejected(self): self.rejects(lambda d:d['pages'][0]['navigationBarMetrics'][0].update(frame=[2,30,120,54]),'bound to observed bar')
    def test_nonfinite_navigation_margin_rejected(self): self.rejects(lambda d:d['pages'][0]['navigationBarMetrics'][0].update(margins=[0,float('inf'),0,16]),'layout insets')
    def test_negative_navigation_safe_area_rejected(self): self.rejects(lambda d:d['pages'][0]['navigationBarMetrics'][0].update(safeArea=[0,-1,0,0]),'layout insets')
    def test_invalid_directional_margins_rejected(self): self.rejects(lambda d:d['pages'][0]['navigationBarMetrics'][0].update(directionalMargins=[0,16]),'layout insets')
    def test_invalid_layout_guide_rejected(self): self.rejects(lambda d:d['pages'][0]['navigationBarMetrics'][0].update(guideFrame=[0,0,float('nan'),10]),'layout rectangle')
    def test_nonboolean_margin_behavior_rejected(self): self.rejects(lambda d:d['pages'][0]['navigationBarMetrics'][0].update(insetsMarginsFromSafeArea=1),'margin behavior')
    def test_invalid_navigation_direction_rejected(self): self.rejects(lambda d:d['pages'][0]['navigationBarMetrics'][0].update(effectiveDirection=3),'navigation direction')
    def test_invalid_navigation_size_class_rejected(self): self.rejects(lambda d:d['pages'][0]['navigationBarMetrics'][0].update(horizontalSizeClass=True),'navigation size class')
    def test_missing_navigation_children_rejected(self): self.rejects(lambda d:d['pages'][0]['navigationBarMetrics'][0].pop('children'),'layout shape')
    def test_invalid_navigation_child_bounds_rejected(self): self.rejects(lambda d:d['pages'][0]['navigationBarMetrics'][0]['children'][0].update(bounds=[0,0,-1,1]),'layout rectangle')
    def test_zero_sized_layout_guide_and_hidden_status_are_valid_observations(self):
        p=self.export/'en-dark.json'; d=json.loads(p.read_text()); d['pages'][0]['navigationBarMetrics'][0]['children'][0]['guideFrame']=[0,0,0,0]
        d['pages'][0]['windowChrome']['statusBar'].update(hidden=True,frameInScene=[0,0,0,0]);p.write_text(json.dumps(d));self.verify()
    def test_signed_finite_margins_are_preserved_as_observed(self):
        p=self.export/'en-dark.json'; d=json.loads(p.read_text()); d['pages'][0]['navigationBarMetrics'][0]['margins']=[-1,16,-2,16]
        p.write_text(json.dumps(d));result=self.verify();self.assertEqual(result['navigationLayoutObservations']['en-dark-loading'][0]['margins'],[-1,16,-2,16])
    def test_loading_dismissal_chrome_is_required(self): self.rejects(lambda d:d['loadingDismissal'].pop('windowChrome'),'window chrome')
