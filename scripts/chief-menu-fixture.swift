import Foundation
import CoreGraphics
@main struct Main {
 @MainActor static func main() throws {
  let elapsed: [Double] = [-1,0,0.149,0.15,0.399999,0.4,0.400001,0.724999,0.725,0.725001,1]
  let releases = elapsed.map { ["elapsed":$0,"opens":MessagePressTiming.release(afterHolding:$0) == .open,"tap":MessagePressTiming.tapOpensItem(afterHolding:$0)] as [String:Any] }
  let sizes: [CGSize] = [.zero,CGSize(width:70,height:30),CGSize(width:208,height:80),CGSize(width:278,height:160),CGSize(width:40,height:400),CGSize(width:1,height:1)]
  let scales = sizes.map { ["size":[$0.width,$0.height],"press":MessagePressTiming.pressScale(for:$0),"lift":MessagePressTiming.liftScale(for:$0)] as [String:Any] }
  let gestures: [(String,MessagePressTiming.Gesture)] = [("press",.press),("tap",.tap),("pan",.pan),("other",.other)]
  var pairs = [[String:Any]]()
  for (a,ga) in gestures { for (b,gb) in gestures { for growing in [false,true] { pairs.append(["a":a,"b":b,"growing":growing,"together":MessagePressTiming.recognizeTogether(ga,gb,isGrowing:growing)]) } } }
  var placements = [[String:Any]]()
  for width: CGFloat in [320,402,440,874] { for height: CGFloat in [402,874] { for size in [CGSize(width:70,height:30),CGSize(width:208,height:80),CGSize(width:278,height:160),CGSize(width:278,height:1400)] { for y: CGFloat in [-40,200,780] { for outgoing in [false,true] { for rows in [4,5,6] { for safe: (CGFloat,CGFloat,CGFloat) in [(0,0,0),(59,34,160)] {
   let inset: CGFloat = width >= 440 ? 20:16
   let source = CGRect(x:outgoing ? width-inset-size.width:inset,y:y,width:size.width,height:size.height)
   let owner=SourceMenu();owner.bounds=CGRect(x:0,y:0,width:width,height:height);owner.safeAreaInsets=SourceInsets(top:safe.0,bottom:safe.1);owner.topInset=safe.2;owner.screenInset=inset;owner.sourceFrame=source;owner.isOutgoing=outgoing
   var value=owner.run(rows:rows);value.merge(["width":width,"height":height,"safeTop":safe.0,"safeBottom":safe.1,"header":safe.2,"inset":inset,"source":[source.minX,source.minY,source.width,source.height],"outgoing":outgoing,"rows":rows]) { _,new in new };placements.append(value)
  } } } } } } }
  let constants:[String:Any] = ["liftBegins":MessagePressTiming.liftBegins,"clickDown":MessagePressTiming.clickDown,"clickTimeout":MessagePressTiming.clickTimeout,"pressCurve":[MessagePressTiming.pressCurve.0.x,MessagePressTiming.pressCurve.0.y,MessagePressTiming.pressCurve.1.x,MessagePressTiming.pressCurve.1.y]]
  let value:[String:Any] = ["constants":constants,"metrics":SourceMenu.metrics,"releases":releases,"scales":scales,"gestures":pairs,"placements":placements]
  print(String(decoding:try JSONSerialization.data(withJSONObject:value,options:[.sortedKeys]),as:UTF8.self))
 }
}
