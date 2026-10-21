import Foundation

@main struct EmojiFixture {
    static func main() throws {
        let whitespace = (0...0x10ffff).compactMap(UnicodeScalar.init).filter { CharacterSet.whitespacesAndNewlines.contains($0) }
        let tagFlag = "🏴\u{e0067}\u{e0062}\u{e0065}\u{e006e}\u{e0067}\u{e007f}"
        var inputs = ["", " ", "👍", "👍🏽", "👍🏻👍🏿", "👨‍👩‍👧‍👦", "🇮🇳", "1", "1️⃣", "#️⃣", "*️⃣", "♥", "♥️", "©", "©️", "™", "™️",
            "🏳️‍🌈", "🧑‍🚀", "🙂🎉", "🙂 🎉", "🙂\n🎉", "🙂🙂🙂", "🙂🙂🙂🙂", "A👍", "👍A", "hello", "\u{1c}👍\u{1c}", "\t👍\r\n",
            "\u{200d}", "\u{fe0f}", " \u{301}👍 ", "👍\u{0}", "\u{1fae9}", tagFlag, tagFlag + tagFlag + tagFlag,
            "1\u{301}", "©\u{301}", "A\u{fe0f}", "a\u{200d}👍"]
        inputs += whitespace.flatMap { scalar in [String(scalar) + "👍" + String(scalar), "🙂" + String(scalar) + "🎉"] }
        inputs += [String(repeating: "\u{200b}", count: 100) + "👍", String(repeating: " ", count: 300) + "👍", String(repeating: "🙂", count: 49)]
        let cases: [[String: Any]] = inputs.map { text in
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return ["text": text, "trimmed": trimmed, "prefilter": ConversationTextScan.mayBeEmojiOnly(text),
                "emojiOnly": ConversationRowBuilder.isEmojiOnly(text), "count": MessageCellLayout.emojiCount(text),
                "fontSize": ConversationTheme.emojiOnlyFontSize(count: MessageCellLayout.emojiCount(text)),
                "composerPointSize": ComposerReference.emojiPointSize(for: text).map { $0 as Any } ?? NSNull(),
                "clusters": text.map(String.init), "trimmedClusters": trimmed.map(String.init)]
        }
        let scalars = Set(inputs.flatMap { $0.unicodeScalars.map { $0.value } }).sorted().map { value -> [String: Any] in
            let scalar = UnicodeScalar(value)!
            return ["scalar": value, "emoji": scalar.properties.isEmoji,
                "presentation": scalar.properties.isEmojiPresentation, "whitespace": scalar.properties.isWhitespace]
        }
        FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: ["cases": cases, "scalars": scalars], options: [.sortedKeys]))
    }
}
