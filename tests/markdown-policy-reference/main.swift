import Foundation

// These calls exercise the unchanged source policies without fetching any URL.
let navInputs: [(String?, Bool, Bool, Bool)] = [
    ("https://example.com/docs", false, true, false),
    ("https://example.com/docs", true, true, false),
    ("about:blank#usage", false, true, false),
    ("about:blank#usage", true, true, false),
    ("about:blank#usage", false, false, false),
    ("about:blank#usage", true, false, false),
    ("about:blank", false, true, true),
    ("about:blank", false, false, true),
    (nil, true, true, true),
    ("about:blank", false, true, false),
    ("file:///private/file", false, true, false),
    ("https://example.com/docs", true, false, false),
]
let navigation = navInputs.map { raw, activated, main, shell -> [String: Any] in
    let decision = MarkdownViewerNavigationPolicy().decide(url: raw.flatMap(URL.init(string:)),
        isUserLinkActivation: activated, isMainFrame: main,
        isInPageFragment: raw?.hasPrefix("about:blank#") == true, isShellDocumentLoad: shell)
    let result: String
    switch decision {
    case .allow: result = "ALLOW"
    case .openExternally: result = "OPEN_EXTERNALLY"
    case .cancel: result = "CANCEL"
    }
    return ["url": raw.map { $0 as Any } ?? NSNull(), "activated": activated,
        "main": main, "shell": shell, "result": result]
}
let approvalInputs = ["https://example.com/a.png", "http://example.com/a.png",
    "https://user:pw@example.com/a.png", "https://127.0.0.1/a.png",
    "https://localhost/a.png", "https://example.com:8443/a.png", "javascript:alert(1)"]
let approval = approvalInputs.map { raw -> [String: Any] in
    var record = MarkdownRemoteImageApprovals()
    return ["url": raw, "approved": record.approve(raw) != nil]
}
var record = MarkdownRemoteImageApprovals()
record.approve("https://example.com/a.png")
let requestInputs = ["https://example.com/a.png", "https://example.com/a.png?x=1",
    "https://example.com/b.png", "https://other.example/a.png"]
let requests = requestInputs.map { raw -> [String: Any] in
    var parts = URLComponents()
    parts.scheme = "cmux-remote-image"; parts.host = "image"
    parts.queryItems = [URLQueryItem(name: "url", value: raw)]
    return ["url": raw, "allowed": record.approvedRemoteImageURL(for: parts.url!) != nil]
}
record.revokeAll()
let revoked = !record.isApproved(URL(string: "https://example.com/a.png")!)
let opens = ["https://example.com/a.png", "http://example.com/a.png", "javascript:alert(1)",
    "file:///etc/passwd", "https://user:pw@example.com/", "cmux-remote-image://image?url=x"].map { raw in
    ["url": raw, "allowed": MarkdownRemoteImageApprovals.openableRemoteImageURL(raw) != nil] as [String: Any]
}
let result: [String: Any] = ["navigation": navigation, "approval": approval,
    "requestsAfterApprovingA": requests, "revocationCleared": revoked, "open": opens]
let data = try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted, .sortedKeys])
FileHandle.standardOutput.write(data)
