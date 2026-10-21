import Foundation
import CoreGraphics

@MainActor struct UIImpactFeedbackGenerator {
    enum Style { case soft }
    static var count = 0
    init(style: Style) {}
    func impactOccurred() { Self.count += 1 }
}
@MainActor final class SourceIndicator {
    var progress: CGFloat = 0
    var pulses = 0
    func update(progress: CGFloat, isOutgoing: Bool) { self.progress = progress }
    func pulse(isOutgoing: Bool, haptic: @escaping () -> Void) { pulses += 1; haptic() }
}
struct SourceModel { var isOutgoing = false }
@MainActor final class MessageCell {
    var model: SourceModel? = SourceModel()
    var replyDrag: CGFloat = 0
    let replyIndicator = SourceIndicator()
}
@MainActor final class SourceCollection {
    let cell = MessageCell()
    func cellForItem(at: IndexPath) -> Any? { cell }
}

@main struct Main {
    @MainActor static func main() throws {
        let translations: [CGFloat] = [-10, 0, 21.999, 22, 22.001, 39.999, 40, 40.001, 41, 80, 400, 1_000_000]
        let offsets = translations.map { t -> [String: Any] in
            let offset = ConversationReplyMotion.bubbleOffset(forTranslation: t)
            var row: [String: Any] = ["translation": t, "offset": offset,
                "progress": ConversationReplyMotion.indicatorProgress(forOffset: offset),
                "scale": ConversationReplyMotion.indicatorScale(forProgress: ConversationReplyMotion.indicatorProgress(forOffset: offset))]
            for outgoing in [false, true] {
                let frame = ConversationReplyMotion.indicatorFrame(restingCenter: CGPoint(x: 80, y: 60), isOutgoing: outgoing, offset: offset)
                row[outgoing ? "outgoingIndicator" : "incomingIndicator"] = frame.map { [$0.minX, $0.minY, $0.width] as Any } ?? NSNull()
            }
            return row
        }
        var areas: [[String: Any]] = []
        for width: CGFloat in [40, 155.999, 156, 200] {
            for outgoing in [false, true] {
                let rect = ConversationReplyMotion.swipeArea(bubble: CGRect(x: 12, y: 18, width: width, height: 44), isOutgoing: outgoing)
                areas.append(["width": width, "outgoing": outgoing, "area": [rect.minX, rect.minY, rect.width, rect.height]])
            }
        }
        let velocities: [(CGFloat, CGFloat)] = [(0,0),(0,100),(100,0),(-100,0),(100,32),(100,33),(100,-32),(100,-33),(-100,32),(-100,33),(1,1),(100,100), (100,100 * tan(18 * .pi / 180)), (100,100 * tan(18.001 * .pi / 180))]
        let angles = velocities.map { x, y in ["x": x, "y": y, "allowed": ConversationReplyMotion.isSwipeAngle(velocity: CGPoint(x: x, y: y))] as [String: Any] }
        let sequences: [[(CGFloat, CGFloat)]] = [
            [(22,100),(40,100),(40.001,100)],
            [(80,100),(30,-100),(80,100)],
            [(80,100),(20,0),(20,100),(20,-100)],
            [(80,100),(40,-100),(40.001,-100)],
            [(80,-100),(400,0),(400,100)],
            [(-10,100),(0,0),(39.999,100)]
        ]
        var crossings: [[String: Any]] = []
        for steps in sequences {
            let owner = SourceOwner(); UIImpactFeedbackGenerator.count = 0
            var rows: [[String: Any]] = []
            for (translation, velocity) in steps {
                owner.updateReplySwipe(rowID: "fixture", translation: translation, velocity: velocity)
                rows.append(["translation": translation, "velocity": velocity, "offset": owner.replyDragOffset,
                    "committed": owner.replyHapticFired, "haptics": UIImpactFeedbackGenerator.count,
                    "pulses": owner.collectionView.cell.replyIndicator.pulses,
                    "indicatorProgress": owner.collectionView.cell.replyIndicator.progress])
            }
            crossings.append(["steps": rows, "releaseCommit": owner.sourceRelease(ended: true), "cancelCommit": owner.sourceRelease(ended: false)])
        }
        let result: [String: Any] = ["schema": 1, "offsets": offsets, "areas": areas, "angles": angles, "crossings": crossings]
        FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted, .sortedKeys]))
    }
}
