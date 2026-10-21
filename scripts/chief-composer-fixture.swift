import Foundation
import CoreGraphics

@main struct ComposerFixture {
    static func main() throws {
        let whitespace = (0...0x10ffff).compactMap(UnicodeScalar.init).filter { CharacterSet.whitespacesAndNewlines.contains($0) }
        let inputs = ["", " \n\t", " hello ", "\r\nhello\nworld\n", "\u{1c}hello\u{1c}", "\u{200b}hello\u{200b}",
            " \u{301}hello ", "🙂", " [link](https://example.com) ", "\u{200d}"]
            + whitespace.flatMap { scalar -> [String] in [String(scalar), String(scalar) + "x" + String(scalar)] }
        let trims = inputs.map { ["text": $0, "trimmed": ConversationRichText.trimmed($0, runs: []).text] }
        var rows: [[String: Any]] = []
        for width in [320.0, 402, 440, 800] {
            for progress in [-1.0, 0, 0.5, 1, 2] {
                for height in [40.287109375, 100.287109375, 59.7] {
                    let inset = ComposerBarGeometry.sideInset(keyboardProgress: progress, keyboardInset: 16)
                    let r = ComposerBarGeometry.layout(width: width, fieldBottom: height + 4, fieldHeight: height,
                        oneLineHeight: 40.287109375, sideInset: inset, scale: 3, showsPlus: false)
                    func rect(_ r: CGRect) -> [Double] { [r.minX,r.minY,r.width,r.height].map(Double.init) }
                    rows.append(["width":width,"progress":progress,"height":height,"bottom":height+4,
                        "oneLine":40.287109375,"scale":3,"inset":inset,"field":rect(r.field),"send":rect(r.send)])
                }
            }
        }
        let measures: [[String: Double]] = [(40.287109375, 20.287109375, -0.287109375),
            (60.287109375,20.287109375,-0.287109375),(100.287109375,20.287109375,-0.287109375),
            (20,0,0),(60,33.4,-0.4)].map { text, line, leading in
                let lines = ComposerFieldMeasurement.lineCount(textHeight:text, verticalPadding:10,lineHeight:line,leading:leading)
                let height = ComposerFieldMeasurement.fieldHeight(lines:lines,oneLineHeight:line+20,lineHeight:line,leading:leading)
                return ["textHeight":text,"line":line,"leading":leading,"padding":10,"lines":lines,"height":height]
            }
        FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:
            ["whitespaceScalars":whitespace.map { Int($0.value) },"trims":trims,"rows":rows,"measures":measures],options:[.sortedKeys]))
    }
}
