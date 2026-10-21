import CoreGraphics
import Foundation

// Only identity, frame and the two source row classes are substituted. The
// complete source threadRows body is compiled without rewriting its logic.
@MainActor class UIView {
    let key: String
    var frame: CGRect
    var subviews: [UIView] = []
    init(_ key: String, y: CGFloat = 0, height: CGFloat = 0) {
        self.key = key
        self.frame = CGRect(x: 0, y: y, width: 402, height: height)
    }
}
struct SourceMessage { let id: String }
struct SourceModel { let message: SourceMessage }
@MainActor final class MessageCell: UIView {
    let model: SourceModel?
    init(_ key: String, id: String?, y: CGFloat, height: CGFloat) {
        self.model = id.map { SourceModel(message: SourceMessage(id: $0)) }
        super.init(key, y: y, height: height)
    }
}
@MainActor final class TimestampCell: UIView {}
@MainActor final class ReplyThreadOverlay {
    let content = UIView("content")
}

@main struct Main {
    static func state(_ trip: ReplyTrip) -> [String: Any] {
        ["startY": trip.startY, "targetY": trip.targetY, "y": trip.y, "offsetX": trip.offsetX,
         "stepCount": trip.stepCount, "resting": trip.isResting]
    }

    static func trajectory(_ name: String, start: CGFloat, target: CGFloat, offset: CGFloat,
                           easing: CGFloat, scale: CGFloat = 3, out: Bool = false,
                           durations: [Double] = [1.0 / 60], moves: [Int: CGFloat] = [:],
                           count: Int = 90, stopAtRest: Bool = true) -> [String: Any] {
        let snap = ConversationReplyMotion.snapDistance(scale: scale, animatingOut: out)
        var trip = ReplyTrip(startY: start, targetY: target, initialOffsetX: offset, easing: easing, snapDistance: snap)
        let initial = state(trip)
        var actions: [[String: Any]] = []
        for index in 0..<count {
            if let delta = moves[index] {
                trip.containerMoved(by: delta)
                actions.append(["delta": delta, "state": state(trip)])
            }
            let duration = durations[index % durations.count]
            trip.step(frameDuration: duration)
            actions.append(["duration": duration, "state": state(trip)])
            if stopAtRest && trip.isResting { break }
        }
        if stopAtRest { precondition(trip.isResting, "Source trajectory did not land: " + name) }
        return ["name": name, "startY": start, "targetY": target, "initialOffsetX": offset,
                "easing": easing, "snapDistance": snap, "initial": initial, "actions": actions]
    }

    @MainActor static func projection(_ name: String, starts: [String: CGFloat], anchor: String,
                                     offset: CGFloat, includeMessages: Bool = true) -> [String: Any] {
        let overlay = ReplyThreadOverlay()
        let views: [UIView] = includeMessages ? [TimestampCell("stamp", y: 0, height: 28.6666666667),
            MessageCell("parent", id: "root", y: 28.6666666667, height: 84.5),
            UIView("non-row", y: 50, height: 2),
            MessageCell("reply", id: "reply", y: 123.1666666667, height: 47.1666666667),
            MessageCell("unidentified", id: nil, y: 180, height: 20)] : [UIView("non-row")]
        overlay.content.subviews = views
        let origins = Dictionary(uniqueKeysWithValues: views.compactMap { view in starts[view.key].map { (ObjectIdentifier(view), $0) } })
        let eligible = views.filter { $0 is MessageCell || $0 is TimestampCell }
        let inputs = eligible.map { view -> [String: Any] in
            ["key": view.key, "messageID": (view as? MessageCell)?.model?.message.id as Any? ?? NSNull(),
             "y": view.frame.minY, "height": view.frame.height]
        }
        let result = SourceOwner().project(overlay, origins, anchor, offset).map { row -> [String: Any] in
            ["key": row.view.key, "baseY": row.baseY, "startY": row.startY,
             "targetY": row.targetY, "initialOffsetX": row.initialOffsetX]
        }
        return ["name": name, "slots": inputs, "starts": starts, "anchorID": anchor,
                "anchorOffset": offset, "plan": result]
    }

    @MainActor static func main() throws {
        var easings: [[String: Any]] = []
        for viewHeight: CGFloat in [-1, 0, 1, 956] {
            for height: CGFloat in [0, 62, 160, 1200] {
                for center: CGFloat in [-100, 0, 401, 478, 1100] {
                    for out in [false, true] {
                        easings.append(["center": center, "height": height, "viewHeight": viewHeight, "out": out,
                            "value": ConversationReplyMotion.threadEasing(startCenterY: center, itemHeight: height, viewHeight: viewHeight, animatingOut: out)])
                    }
                }
            }
        }
        var steps: [[String: Any]] = []
        for easing: CGFloat in [-1, 0, 0.5, 0.89, 1, 1.1] {
            for duration in [-1.0, 0, 1.0 / 120, 1.0 / 90, 1.0 / 60, 1.0 / 30, 0.25] {
                steps.append(["easing": easing, "duration": duration,
                    "value": ConversationReplyMotion.step(easing: easing, frameDuration: duration)])
            }
        }
        let curves: [[String: Any]] = [-1.0, 0, 0.001, 0.1, 0.25, 0.5, 0.75, 0.9, 0.999, 1, 2].map { value in
            ["progress": value, "offset": ConversationReplyMotion.offsetCurve(progress: value),
             "easeInOut": ConversationReplyMotion.easeInOut(value)]
        }
        var snaps: [[String: Any]] = []
        for scale: CGFloat in [-1, 0, 0.5, 1, 2, 3, 4] {
            for out in [false, true] {
                snaps.append(["scale": scale, "out": out, "value": ConversationReplyMotion.snapDistance(scale: scale, animatingOut: out)])
            }
        }
        let enter = ConversationReplyMotion.threadEasing(startCenterY: 401, itemHeight: 62, viewHeight: 956, animatingOut: false)
        let exit = ConversationReplyMotion.threadEasing(startCenterY: 527, itemHeight: 62, viewHeight: 956, animatingOut: true)
        let trips: [[String: Any]] = [
            trajectory("source-observed-enter", start: 0, target: 128.66, offset: 64, easing: enter),
            trajectory("source-observed-exit", start: 0, target: -128.67, offset: 0, easing: exit, out: true),
            trajectory("same-y-walk", start: 0, target: 0, offset: 30, easing: 0.88),
            trajectory("sub-snap-walk-and-landed-container", start: 0, target: 0.05, offset: 30, easing: 0.88, moves: [1: -24]),
            trajectory("short-negative-y", start: 100, target: 99.5, offset: 30, easing: 0.89),
            trajectory("below-two", start: 0, target: 1.99999, offset: 30, easing: 0.89),
            trajectory("exact-two", start: 0, target: 2, offset: 30, easing: 0.89),
            trajectory("travelling-container", start: 0, target: 100, offset: 64, easing: 0.89, moves: [1: -24, 3: 19]),
            trajectory("landed-container", start: 0, target: 100, offset: 0, easing: 0.5, moves: [20: -24], count: 22, stopAtRest: false),
            trajectory("120-hz", start: -120, target: 500, offset: 49, easing: enter, durations: [1.0 / 120], count: 180),
            trajectory("mixed-frames", start: 90, target: -110, offset: 43, easing: exit, out: true, durations: [1.0 / 120, 1.0 / 30, 1.0 / 90, 0.1]),
            trajectory("nonpositive-first-frame", start: 0, target: 90, offset: 40, easing: 0.89, durations: [-0.01, 0, 1.0 / 60], count: 200),
            trajectory("zero-easing-zero-time", start: 0, target: 80, offset: 30, easing: 0, durations: [0]),
            trajectory("clamped-easing", start: 0, target: 80, offset: 30, easing: 1.1, count: 6, stopAtRest: false),
            trajectory("negative-offset-not-normalized", start: 0, target: 80, offset: -1, easing: 0.5, count: 20, stopAtRest: false),
            trajectory("zero-scale-out", start: 0, target: 128, offset: 0, easing: exit, scale: 0, out: true)
        ]
        let plans = [projection("visible-twins", starts: ["stamp": 70, "parent": 100, "reply": 210], anchor: "reply", offset: 48),
            projection("anchor-only", starts: ["reply": 200], anchor: "reply", offset: 49),
            projection("no-anchor-twin", starts: ["parent": 10], anchor: "reply", offset: 40),
            projection("missing-anchor", starts: ["parent": 20], anchor: "absent", offset: 60),
            projection("empty", starts: [:], anchor: "reply", offset: 45, includeMessages: false)]
        let result: [String: Any] = ["schema": 1, "easings": easings, "steps": steps, "curves": curves, "snaps": snaps,
            "trips": trips, "plans": plans, "sourceObservations": ["intoY": SourceObservations.deviceIntoY,
                "intoX": SourceObservations.deviceIntoX, "backY": SourceObservations.deviceBackY]]
        FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted, .sortedKeys]))
    }
}
