import Foundation
import CoreGraphics

@main enum Fixture {
    static func rect(_ r: CGRect) -> [Double] { [Double(r.minX),Double(r.minY),Double(r.width),Double(r.height)] }
    static func main() throws {
        var compact: [[String: Any]] = []
        for width in [320.0,402,440,844] { for name in [0.0,44,200] { for safeTop in [0.0,24,48] { for chevron in [CGSize(width: 4.7, height: 9), CGSize(width: 25.0/3, height: 44.0/3)] {
            let r = ConversationHeaderGeometry.compactLayout(width: width, safeTop: safeTop,
                nameSize: CGSize(width: name, height: 21), chevronSize: chevron)
            compact.append(["width":width,"name":name,"safeTop":safeTop,"chevronWidth":Double(chevron.width),"chevronHeight":Double(chevron.height),"avatar":rect(r.avatar),"label":rect(r.name),"chevron":rect(r.chevron),"height":Double(r.barTop+r.barHeight)])
        } } } }
        var details: [[String: Any]] = []
        for width in [402.0,440] { for offset in [-100.0,-40,0,30,60,97.23,200] {
            for title in [101.0/3,80] { for action in [48.0,73] {
                let r = ConversationDetailsHeaderGeometry.layout(width:width,safeTop:0,offset:offset,showsTabs:false,isGroup:false,titleHeight:title,quickActionSize:action)
                details.append(["width":width,"offset":offset,"titleHeight":title,"actionSize":action,"avatar":rect(r.avatar),"actions":rect(r.quickActions),"titleTop":Double(r.titleTop),"titleScale":Double(r.titleScale),"alpha":Double(r.quickActionsAlpha),"height":Double(r.height)])
            } }
        } }
        let unread = [0.0,6,23,80,150].map { w -> [String:Any] in ["text":w,"width":Double(ConversationHeaderGeometry.backWidth(unreadTextWidth:w)),"pill":rect(ConversationHeaderGeometry.unreadPillFrame(textWidth:w))] }
        let names = [0.0,14,17,19,21,23,28,53].map { ["input":$0,"output":Double(ConversationHeaderGeometry.namePointSize(scaled:$0))] }
        var wash: [[String: Any]] = []
        for height in [0.0,70,138,200,300] { for header in [-20.0,0,54,92,154] { for dark in [false,true] {
            let stops = ConversationTopEdgeGeometry.ramp(height:height,headerBottom:header,dark:dark)
            wash.append(["height":height,"header":header,"dark":dark,"stops":stops.map { [Double($0.location),Double($0.wash)] }])
        } } }
        var transcriptInsets: [[String: Any]] = []
        for header in [54.0,92,154] { for footer in [44.0,80,220] { for overhang in [0.0,6.5,12] { for compact in [false,true] {
            let (top,bottom) = sourceTranscriptInsets(headerBottom:header,footerHeight:footer,compact:compact)
            transcriptInsets.append(["header":header,"footer":footer,"overhang":overhang,"compact":compact,
                "top":Double(top),"paddingBottom":max(0,Double(bottom)+6-overhang)])
        } } } }
        let state = ConversationListState()
        let result: [String:Any] = ["compact":compact,"details":details,"wash":wash,"transcriptInsets":transcriptInsets,"unread":unread,"names":names,
            "quickSizes":ConversationDetailsHeaderGeometry.quickActionSizes.map(Double.init),
            "defaults":["muted":state.muted,"readReceipts":state.sendReadReceipts]]
        FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:result, options:[.sortedKeys,.prettyPrinted]))
    }
}
