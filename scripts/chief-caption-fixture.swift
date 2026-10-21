import Foundation
import CoreGraphics

struct SourceFont {
    var pointSize: CGFloat
    var lineHeight: CGFloat
    var leading: CGFloat
    var ascender: CGFloat
}
@MainActor enum ConversationTheme {
    static var captionFont = SourceFont(pointSize: 11, lineHeight: 13, leading: 0, ascender: 10)
    static var timestampFont = captionFont
    static var displayScale: CGFloat = 3
    static var neutral: CGFloat = 13
    static var captionLineSpacing: CGFloat {
        ConversationTranscriptMetrics.captionLineSpacing(lineHeight: captionFont.lineHeight,
            leading: captionFont.leading, languageNeutralLineHeight: neutral)
    }
}

@main struct Main {
    @MainActor static func main() throws {
        var result: [[String: Any]] = []
        let inputs: [(CGFloat, CGFloat, CGFloat, CGFloat)] = [
            (11, 13.126953125, -0.126953125, 10.5), (20, 23.8671875, 1.1328125, 19),
            (8, 10, 0, 8), (12, 14.5, 1, 11), (13, 15.5, 2.5, 12),
            (15, 18, 2, 14), (17, 20.3, 1.7, 16), (22, 26.3, 1, 21),
            (24, 28.5, 0.5, 23), (29, 34.5, 0.5, 28), (34, 40, 1, 33), (40, 48, 0, 39), (48, 57, 2, 47)
        ]
        for (point, neutral, leading, ascender) in inputs {
            for scale: CGFloat in [0, 1, 2, 3] {
                for tall: CGFloat in [0, 2] {
                    ConversationTheme.captionFont = SourceFont(pointSize: point, lineHeight: neutral + tall, leading: leading, ascender: ascender)
                    ConversationTheme.timestampFont = SourceFont(pointSize: point, lineHeight: neutral, leading: 0, ascender: ascender)
                    ConversationTheme.neutral = neutral; ConversationTheme.displayScale = scale
                    let spacing = ConversationTheme.captionLineSpacing
                    let insets = ConversationTranscriptMetrics.captionAlignmentInsets(pointSize: point)
                    let sender = ConversationTranscriptMetrics.senderNameLine(lineHeight: neutral + tall, captionLineSpacing: spacing, scale: scale)
                    let label = sourceLabelFrame(width: 402)
                    result.append(["pointSize": point, "neutralLineHeight": neutral,
                        "lineHeight": neutral + tall, "leading": leading, "ascender": ascender, "scale": scale,
                        "spacing": spacing, "insets": [insets.top, insets.bottom], "sender": [sender.height, sender.bubbleOffset],
                        "lineGap": ConversationTranscriptMetrics.timestampLineGap(captionLineSpacing: spacing, captionPointSize: point, scale: scale),
                        "followingGap": ConversationTranscriptMetrics.timestampFollowingGap(captionLineSpacing: spacing, captionPointSize: point, scale: scale),
                        "headerGap": ConversationTranscriptMetrics.headerToTimestampGap(captionLineSpacing: spacing, captionPointSize: point, scale: scale),
                        "timestamp": [SourceTimestamp.lineTop, SourceTimestamp.height, SourceTimestamp.baselineOffset],
                        "labelFrame": [label.minX, label.minY, label.width, label.height]])
                }
            }
        }
        FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: ["schema": 1, "cases": result], options: [.prettyPrinted, .sortedKeys]))
    }
}
