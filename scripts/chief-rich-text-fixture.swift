import Foundation
import QuartzCore
import CmuxConversationCore

// This driver invokes the whole, unmodified pinned core. It does not reimplement its algorithms.
@main enum RichTextFixture {
    static func runs(_ rows: [[String: Any]]) -> [ConversationTextRun] {
        rows.map { row in
            ConversationTextRun(location: row["location"] as! Int, length: row["length"] as! Int,
                style: ConversationTextStyle(rawValue: row["style"] as! Int),
                effect: (row["effect"] as? String).flatMap(ConversationTextEffect.init(rawValue:)))
        }
    }
    static func encode(_ runs: [ConversationTextRun]) -> [[String: Any]] {
        runs.map { ["location": $0.location, "length": $0.length, "style": $0.style.rawValue,
            "effect": $0.effect.map { $0.rawValue as Any } ?? NSNull()] }
    }
    static func main() throws {
        let input = try JSONSerialization.jsonObject(with: FileHandle.standardInput.readDataToEndOfFile()) as! [String: Any]
        let cases = input["richText"] as! [[String: Any]]
        var rich: [[String: Any]] = []
        for row in cases {
            let text = row["text"] as! String
            let originalRuns = runs(row["runs"] as! [[String: Any]])
            let attributed = NSMutableAttributedString(string: text)
            ConversationRichText.apply(originalRuns, to: attributed)
            let trimmed = ConversationRichText.trimmed(text, runs: originalRuns)
            let range = NSRange(location: row["start"] as! Int, length: row["length"] as! Int)
            var actions: [[String: Any]] = []
            for action in row["actions"] as! [[String: Any]] {
                var result: [String: Any] = action
                if let style = action["style"] as? Int {
                    result["enabled"] = ConversationRichText.toggle(ConversationTextStyle(rawValue: style), in: range, of: attributed)
                } else {
                    let effect = ConversationTextEffect(rawValue: action["effect"] as! String)!
                    result["resultEffect"] = ConversationRichText.toggle(effect, in: range, of: attributed).map { $0.rawValue as Any } ?? NSNull()
                }
                result["runs"] = encode(ConversationRichText.runs(in: attributed))
                result["activeStyles"] = ConversationTextStyle.all.map {
                    ConversationRichText.range(range, of: attributed, hasAll: $0.style)
                }
                result["commonEffect"] = ConversationRichText.commonEffect(in: range, of: attributed).map { $0.rawValue as Any } ?? NSNull()
                actions.append(result)
            }
            rich.append(["input": row,
                "normalized": encode(ConversationRichText.normalized(originalRuns, utf16Count: text.utf16.count)),
                "carried": encode(ConversationRichText.carried(originalRuns, from: text, to: row["newText"] as! String)),
                "trimmed": ["text": trimmed.text, "runs": encode(trimmed.runs)], "actions": actions])
        }
        let motion = (input["motion"] as! [[String: Any]]).map { row -> [String: Any] in
            let effect = ConversationTextEffect(rawValue: row["effect"] as! String)!
            let index = row["index"] as! Int, count = row["count"] as! Int
            let pose = ConversationTextEffectMotion.pose(effect, index: index, count: count,
                time: row["time"] as! Double, seed: UInt64(row["seed"] as! String)!)
            return ["input": row, "fontScale": ConversationTextEffectMotion.fontScale(effect),
                "duration": ConversationTextEffectMotion.duration(effect),
                "delay": ConversationTextEffectMotion.delay(effect, index: index, count: count),
                "pose": [pose.dx, pose.dy, pose.rotation, pose.scale, pose.opacity]]
        }
        let seeds = (input["seeds"] as! [String]).map { ["text": $0, "seed": String(ConversationTextEffectMotion.seed($0))] }
        let keyframes = (input["keyframes"] as! [[String: Any]]).map { row -> [String: Any] in
            let effect = ConversationTextEffect(rawValue: row["effect"] as! String)!
            let frames = ConversationTextEffectLayer.loopKeyframes(effect, index: row["index"] as! Int,
                count: row["count"] as! Int, fontSize: row["fontSize"] as! Double, seed: UInt64(row["seed"] as! String)!)
            return ["input": row, "transformTimes": frames.transformTimes,
                "transforms": frames.transforms.map { [$0.m11, $0.m12, $0.m13, $0.m14,
                    $0.m21, $0.m22, $0.m23, $0.m24, $0.m31, $0.m32, $0.m33, $0.m34,
                    $0.m41, $0.m42, $0.m43, $0.m44] },
                "opacityTimes": frames.opacityTimes, "opacities": frames.opacities]
        }
        let output: [String: Any] = ["richText": rich, "motion": motion, "seeds": seeds,
            "keyframes": keyframes,
            "styleWire": [-1, 0, 1, 3, 15, 16, 33, (1 << 40) | 1, Int.max, Int.min].map { ["mask": $0, "names": ConversationTextStyle(rawValue: $0).wireNames] as [String: Any] },
            "effectOrder": ConversationTextEffect.allCases.map(\.rawValue), "cycle": ConversationTextEffectMotion.cycle]
        FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: output, options: [.sortedKeys]))
    }
}
