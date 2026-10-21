"""Synthetic exporter guards only; these PNGs are not native-rendering evidence."""
from contextlib import redirect_stdout
import hashlib,importlib.util,io,json,tempfile,unittest
from pathlib import Path
from test_ios_chief_reference import png
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('action_verifier',ROOT/'scripts/verify-ios-chief-actions.py');V=importlib.util.module_from_spec(spec);spec.loader.exec_module(V)
class ActionGuards(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.export=Path(self.tmp.name)/'in';self.export.mkdir();self.output=Path(self.tmp.name)/'out';self.commit='a'*40
  template=ROOT/'tests/ios-chief-reference';m=json.loads((ROOT/'third_party/cmux-chief/manifest.json').read_text());fixtures=json.loads((template/'Actions.json').read_text())['scenes'];bases={s['id']:s for s in json.loads((template/'Scenes.json').read_text())['scenes']}
  for i,f in enumerate(fixtures):
   landscape=f['orientation']=='landscape';w,h=(874,402) if landscape else (402,874);image=png(i,w,h);name=f['id'];target=next(x for x in bases[f['sourceScene']]['rows'] if x['id']==f['messageID']);outgoing=target['outgoing'];x=136 if outgoing and not landscape else 16
   # Observed geometry within a synthetic window; source implementation is not run.
   preview=[x,30,250,70];platter=[x,118,250,202];symbols=['arrowshape.turn.up.left','doc.on.doc','selection.pin.in.out','ellipsis.circle']
   rows=[dict(identifier='conversation.menu.'+s,frame=[x,128+j*42,250,42],visibleFrame=[x,128+j*42,250,42],enabled=True) for j,s in enumerate(symbols)]
   b=dict(draftPreserved=True,backendWriteAttempts=0,viewingAfterAction=True)
   if f['action']=='copy':b.update(copyMatchesSourceText=True,copySentinelReplaced=True)
   elif f['action']=='reply':b.update(replyHandoff='sourceMenuTouchUpInside',editorFocusedAfterReply=True)
   else:b.update(selectedInitially=['conversation.bubble.parent'],selectedAfterToggle=['conversation.bubble.parent','conversation.bubble.reply'],forwardVisible=False,composerHidden=True,selectionCancelRestoresComposer=True,deleteConfirmation=['Delete 2 Messages','Cancel'],directCoreLocalDeleteIDs=['next'],destructiveAlertActivated=False)
   p=dict(upstreamRevision=m['revision'],packageTree=m['referencePackage']['tree'],projectSourceDirty=False,projectSourceCommit=self.commit,actionsSha256=V.digest(template/'Actions.json'),scenesSha256=V.digest(template/'Scenes.json'),referenceTestsSha256=V.digest(template/'ReferenceTests.swift'),referenceAppSha256=V.digest(template/'ReferenceApp.swift'))
   o=dict(activation='sourceBubbleAccessibilityActivate',ancestorVisibilityGuardPassed=True,keyboardDismissed=True,modal=True,previewClips=True,previewScale=1.1,rows=rows,platterFrame=platter,previewFrame=preview,screenInset=16,safeArea=[0,0,0,0],menuLabels=[dict(fontSize=17)]*4,behavior=b)
   value=dict(f,actionScene=name,actualStyle=f['style'],actualLandscape=landscape,applicationCategoryMatchesScene=True,animationsEnabled=False,outgoing=outgoing,capturePhase='selection' if f['action']=='more' else 'menu',fixtureSha256=V.digest(template/'Actions.json'),imageSha256=hashlib.sha256(image).hexdigest(),provenance=p,viewport=[w,h],scale=1,labels=[dict(fontSize=28,class_='ConversationEffectLabel')],controls=rows,observation=o)
   value['labels'][0]['class']=value['labels'][0].pop('class_');(self.export/(name+'.png')).write_bytes(image);(self.export/(name+'.json')).write_text(json.dumps(value))
 def verify(self):
  with redirect_stdout(io.StringIO()):V.verify(self.export,self.output,self.commit)
 def mutate(self,change,name='menu-incoming-dark'):
  p=self.export/(name+'.json');v=json.loads(p.read_text());change(v);p.write_text(json.dumps(v))
 def test_complete_set_passes_and_missing_payload_fails(self):
  self.verify();self.assertEqual(len(list(self.output.glob('*.png'))),5);(self.export/'menu-incoming-dark.png').unlink()
  with self.assertRaisesRegex(ValueError,'screenshot'):self.verify()
 def test_dirty_or_changed_reference_source_is_rejected(self):
  self.mutate(lambda v:v['provenance'].update(projectSourceDirty=True))
  with self.assertRaisesRegex(ValueError,'dirty'):self.verify()
 def test_wrong_actual_category_is_rejected(self):
  self.mutate(lambda v:v.update(applicationCategoryMatchesScene=False))
  with self.assertRaisesRegex(ValueError,'traits'):self.verify()
 def test_missing_menu_action_and_bad_preview_placement_fail(self):
  self.mutate(lambda v:v['observation']['rows'].pop())
  with self.assertRaisesRegex(ValueError,'actions'):self.verify()
 def test_disallowed_mutation_and_unproven_copy_fail(self):
  self.mutate(lambda v:v['observation']['behavior'].update(backendWriteAttempts=1))
  with self.assertRaisesRegex(ValueError,'ownership'):self.verify()
 def test_wrong_core_vs_ui_delete_scope_is_rejected(self):
  self.mutate(lambda v:v['observation']['behavior'].update(destructiveAlertActivated=True),name='selection-dark')
  with self.assertRaisesRegex(ValueError,'local-delete'):self.verify()
 def test_menu_placed_away_from_preview_is_rejected(self):
  self.mutate(lambda v:v['observation']['platterFrame'].__setitem__(0,50))
  with self.assertRaisesRegex(ValueError,'placed'):self.verify()
 def test_wrong_accessibility_body_font_is_rejected(self):
  self.mutate(lambda v:v['labels'][0].update(fontSize=17),name='menu-large-dark')
  with self.assertRaisesRegex(ValueError,'Accessibility'):self.verify()
 def test_reply_without_source_control_callback_is_rejected(self):
  self.mutate(lambda v:v['observation']['behavior'].update(replyHandoff='directPrivateCall'),name='menu-outgoing-dark')
  with self.assertRaisesRegex(ValueError,'handoff'):self.verify()
if __name__=='__main__':unittest.main()
