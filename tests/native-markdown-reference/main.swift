import Foundation

let data = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
let cases = try JSONSerialization.jsonObject(with: data) as! [String: [String]]
let documents = cases["documents"]!.map { input -> [String: Any] in
    let blocks = ChatArtifactMarkdownDocument(markdown: input).blocks.map { block -> [String: Any] in
        var value: [String: Any] = ["index": block.index, "text": block.text]
        switch block.kind {
        case .heading(let level): value["kind"] = "HEADING"; value["level"] = level
        case .paragraph: value["kind"] = "PARAGRAPH"
        case .bullet(let indent): value["kind"] = "BULLET"; value["indent"] = indent
        case .ordered(let marker, let indent): value["kind"] = "ORDERED"; value["marker"] = marker; value["indent"] = indent
        case .quote: value["kind"] = "QUOTE"
        case .rule: value["kind"] = "RULE"
        case .code(let language): value["kind"] = "CODE"; value["language"] = language.map { $0 as Any } ?? NSNull()
        case .tableRow(let header): value["kind"] = "TABLE_ROW"; value["header"] = header
        }
        return value
    }
    return ["input": input, "blocks": blocks]
}
let inline = cases["inline"]!.map { input -> [String: Any] in
    let text = renderedInline(input)
    let runs = text.runs.map { run -> [String: Any] in
        let intent = run.inlinePresentationIntent
        return ["text": String(text.characters[run.range]),
            "bold": intent?.contains(.stronglyEmphasized) == true,
            "italic": intent?.contains(.emphasized) == true,
            "code": intent?.contains(.code) == true,
            "strike": intent?.contains(.strikethrough) == true,
            "link": run.link.map { $0.absoluteString as Any } ?? NSNull()]
    }
    return ["input": input, "runs": runs]
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: ["documents": documents, "inline": inline], options: [.sortedKeys, .prettyPrinted]))
