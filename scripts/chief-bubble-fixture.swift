import CoreGraphics
import Foundation

@main struct BubbleFixture {
    static func main() throws {
        let dimensions: [(Double, Double, Double)] = [(180, 40.287109375, 20.1435546875),
            (280.6666666666667, 100.3333333333, 20.1435546875), (48, 40.3333333333, 20.1435546875),
            (346.6666666666667, 57.3333333333, 28.705), (48, 57.3333333333, 28.705),
            (5, 8, 40), (180, 20, 0), (180, 100, -2), (0, 0, 20)]
        var paths: [[String: Any]] = []
        for (width, height, radius) in dimensions {
            for outgoing in [false, true] {
                for tail in [false, true] {
                    let path = ConversationBubbleGeometry.iOSPath(in: CGRect(x: 0, y: 0, width: width, height: height),
                        side: outgoing ? .trailing : .leading, tail: tail, radius: radius)
                    var commands: [[String: Any]] = []
                    path.applyWithBlock { pointer in
                        let element = pointer.pointee
                        let kind: String, count: Int
                        switch element.type {
                        case .moveToPoint: kind = "move"; count = 1
                        case .addLineToPoint: kind = "line"; count = 1
                        case .addCurveToPoint: kind = "curve"; count = 3
                        case .addQuadCurveToPoint: kind = "quad"; count = 2
                        case .closeSubpath: kind = "close"; count = 0
                        @unknown default: fatalError("Unexpected CoreGraphics command")
                        }
                        commands.append(["kind": kind, "points": (0..<count).map { i in
                            [Double(element.points[i].x), Double(element.points[i].y)] }])
                    }
                    paths.append(["width": width, "height": height, "radius": radius,
                        "outgoing": outgoing, "tail": tail, "commands": commands])
                }
            }
        }
        let widths = [-10.0, 0, 20, 48, 300, 370, 400, 594, 596, 800, 1200].map { width in
            ["available": width, "maxWidth": Double(ConversationTranscriptMetrics.balloonMaxWidth(betweenMargins: width))]
        }
        let accessibilityWidths = [-10.0, 0, 20, 48, 300, 370, 400, 800, 1200].map { width in
            ["available": width, "maxWidth": Double(ReferenceBubbleTheme.maxBubbleWidth(forAvailableWidth: width))]
        }
        let rounding = [(278.0000001, 3.0), (277.7, 3.0), (1.0004, 2.0), (1.2, 0.0), (4.5, -2.0)].map { v, s in
            ["value": v, "scale": s, "rounded": Double(ConversationTranscriptMetrics.ceilToPixel(v, scale: s))]
        }
        let drops = [0.0, 20.1435546875, 28.705, 40].map { r in
            ["radius": r, "drop": Double(ConversationBubbleGeometry.iOSTailDrop(radius: r))]
        }
        let spaces: [[String: Double]] = [13.0, 18, 20, 22, 25, 29, 35].flatMap { line -> [[String: Double]] in
            [4.0, 6, 10, 16].map { base in
                ["base": base, "captionLineSpacing": line, "scale": 3.0,
                 "space": Double(ConversationTranscriptMetrics.space(base, captionLineSpacing: line, scale: 3))]
            }
        }
        FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:
            ["paths": paths, "widths": widths, "accessibilityWidths": accessibilityWidths, "rounding": rounding, "drops": drops, "spaces": spaces], options: [.sortedKeys]))
    }
}
