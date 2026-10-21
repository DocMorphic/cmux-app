#!/usr/bin/env python3
"""Execute pinned press/menu geometry and actual Chief-only UIKit layout bodies with inert views."""
import argparse,hashlib,json,subprocess
from pathlib import Path

def body(source,marker):
 start=source.index(marker);first=source.index('{',start);depth=0
 for i in range(first,len(source)):
  if source[i]=='{':depth+=1
  elif source[i]=='}':
   depth-=1
   if depth==0:return source[start:i+1]
 raise ValueError('Unbalanced pinned source declaration')

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--upstream',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--vectors',type=Path);a=p.parse_args()
 root=Path(__file__).resolve().parents[1];m=json.loads((root/'third_party/cmux-chief/manifest.json').read_text());out=a.output.resolve();out.mkdir(parents=True,exist_ok=True);sources={}
 for path,digest in m['menuFiles'].items():
  data=subprocess.check_output(['git','-C',str(a.upstream.resolve()),'show',m['revision']+':'+path]);assert hashlib.sha256(data).hexdigest()==digest,'Unreviewed menu source: '+path;sources[Path(path).name]=data.decode()
 files=[]
 for name in ('MessagePressTiming.swift','ConversationMenuGlass.swift'):
  path=out/name;path.write_text(sources[name]);files.append(path)
 overlay=sources['MessageActionOverlay.swift'];gestures=sources['ConversationViewController+Gestures.swift']
 assert 'if hypot(point.x - pressStart.x, point.y - pressStart.y) > 10' in gestures
 assert 'static let replyHandoffDelay: TimeInterval = 0.12' in overlay
 extracted='\n'.join(body(overlay,x) for x in ('    enum Metrics {','    private var menuSize: CGSize {','    private func liftedFrame(for rest: CGRect)','    private func layoutFinal()'))
 owner=out/'SourceMenu.swift';owner.write_text('''import Foundation
import CoreGraphics
struct SourceInsets { var top: CGFloat = 0; var bottom: CGFloat = 0 }
class SourceLayer { var cornerRadius: CGFloat = 0 }
class SourceView {
 var bounds = CGRect.zero
 var frame = CGRect.zero { didSet { bounds.size = frame.size } }
 var isHidden = false
 var layer = SourceLayer()
 var contentSize = CGSize.zero
 var contentOffset = CGPoint.zero
 var arrangedSubviews: [SourceView] = []
}
final class MenuRow: SourceView {}
enum ConversationTheme { static let bubbleCornerRadius: CGFloat = 20 }
enum ReactionDetailCardGeometry { static let topOffset: CGFloat = 0 }
extension CGRect { init(center: CGPoint, size: CGSize) { self.init(x:center.x-size.width/2,y:center.y-size.height/2,width:size.width,height:size.height) } }
@MainActor final class SourceMenu {
 var bounds = CGRect.zero
 var safeAreaInsets = SourceInsets()
 var sourceFrame = CGRect.zero
 var screenInset: CGFloat = 16
 var topInset: CGFloat = 0
 let offersReactions = false
 var isOutgoing = false
 let snapshotClip = SourceView(), menu = SourceView(), menuStack = SourceView()
 let reactionBar = SourceView(), reactionScroll = SourceView(), emojiButton = SourceView(), tailDot = SourceView()
 let reactionButtons: [SourceView] = []
 let detailCard: SourceView? = nil
 var finalPreviewCenter = CGPoint.zero
 var finalPreviewFrame = CGRect.zero
 var finalBarFrame = CGRect.zero
 var restingSmileyFrame = CGRect.zero
 var restingTailFrame = CGRect.zero
 var previewScale: CGFloat { MessagePressTiming.liftScale(for: sourceFrame.size) }
 var barContentWidth: CGFloat { CGFloat(reactionButtons.count) * Metrics.barCell + Metrics.barPadding * 2 }
 func layoutEmojiFace() {} // Chief disables reactions/custom emoji; no menu/preview mutation.
 static let replyHandoffDelay: TimeInterval = 0.12
'''+extracted+'''
 func run(rows: Int) -> [String: Any] {
  for i in 0..<rows { if i==1 && rows>3 { menuStack.arrangedSubviews.append(SourceView()) };menuStack.arrangedSubviews.append(MenuRow()) }
  layoutFinal()
  func rect(_ r: CGRect) -> [CGFloat] { [r.minX,r.minY,r.width,r.height] }
  return ["preview":rect(finalPreviewFrame),"menu":rect(menu.frame),"clipped":snapshotClip.layer.cornerRadius>0,"scale":previewScale]
 }
 static var metrics: [String: Any] { ["row":Metrics.menuRow,"width":Metrics.menuWidth,"gap":Metrics.menuGap,"verticalInset":Metrics.menuVerticalInset,"iconCenter":Metrics.menuIconCenter,"labelLeading":Metrics.menuLabelLeading,"labelTrailing":Metrics.menuLabelTrailing,"handoff":replyHandoffDelay] }
}
''');files.append(owner)
 binary=out/'fixture';r=subprocess.run(['/usr/bin/swiftc','-swift-version','6',*map(str,files),str(root/'scripts/chief-menu-fixture.swift'),'-o',str(binary)],capture_output=True,timeout=90);(out/'build.log').write_bytes(r.stdout+r.stderr);r.check_returncode()
 vectors=json.loads(subprocess.check_output([str(binary)],timeout=30));encoded=(json.dumps(vectors,sort_keys=True,indent=2)+'\n').encode();(out/'vectors.json').write_bytes(encoded)
 target=a.vectors or root/'app/src/test/resources/parity/chief-menu.json'
 if a.vectors:target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(encoded)
 elif target.read_bytes()!=encoded:raise ValueError('Menu vectors differ')
 receipt=dict(revision=m['revision'],sourcePins=m['menuFiles'],vectorsSha256=hashlib.sha256(encoded).hexdigest(),placements=len(vectors['placements']),gesturePairs=len(vectors['gestures']),releases=len(vectors['releases']),scaleCases=len(vectors['scales']),scope='Whole pinned press/menu-glass geometry and verbatim menu-size/lift/layoutFinal bodies; Chief reactions false, inert views and excluded reaction/emoji-face branches. Not UIKit rendering, physical gestures, keyboard/menu lifetime, animation timing or mounted Android acceptance.')
 (out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt,indent=2))
if __name__=='__main__':main()
