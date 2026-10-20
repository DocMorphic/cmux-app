import Foundation

struct ConversationMessage {
    var id: String
    var seq: Int?
    var senderID: String
    var isSystemEvent = false
    var rowID: String { "s:\(id)" }
}
struct ConversationReadState { var lastReadSeq: Int; var unreadCount: Int; var headSeq: Int }
enum Older { case idle, exhausted }
enum Change { case readState }
@MainActor final class Backend { func markRead(upToSeq: Int) async {} }

@MainActor final class ReadingStore {
    var messages: [ConversationMessage] = []
    var meID: String? = "me"
    var hasLoadedNewest = true
    var older = Older.idle
    var lastReadSeq = 0
    var unreadCount = 0
    var catchUpMarker: Int?
    var catchUpCount = 0
    var isViewing = false
    var catchUpCaptured = false
    var pendingReadSeq = 0
    var serverRead: ConversationReadState?
    let backend = Backend()
    func notify(_ change: Change) {}
    // PINNED_METHODS
    func accept(_ state: ConversationReadState) {
        // PINNED_READ_EVENT
    }
    func run(_ action: String, _ mark: Int) {
        switch action {
        case "accept": accept(serverRead!)
        case "viewOn": setViewing(true)
        case "viewOff": setViewing(false)
        case "endVisit": endVisit()
        case "dismiss": dismissCatchUp()
        case "mark": markRead(through: mark)
        default: refreshReadState()
        }
    }
}

@main struct ReadingFixture {
    @MainActor static func main() throws {
        func input(_ name: String, seqs: [Int] = [1, 2, 3], human: [Int] = [], read: Int = 0,
                   unread: Int = 3, head: Int = 3, viewing: Bool = false, captured: Bool = false,
                   marker: Int? = nil, count: Int = 0, pending: Int = 0, last: Int = 0,
                   loaded: Bool = true, exhausted: Bool = false, server: Bool = true,
                   action: String = "refresh", mark: Int = 0) -> [String: Any] {
            ["name": name, "seqs": seqs, "human": human, "read": read, "unread": unread, "head": head,
             "viewing": viewing, "captured": captured, "marker": marker.map { $0 as Any } ?? NSNull(), "count": count,
             "pending": pending, "last": last, "loaded": loaded, "exhausted": exhausted, "server": server,
             "action": action, "mark": mark]
        }
        let cases = [
            input("not-viewing-exact-window"),
            input("foreground-captures-and-reads", viewing: true),
            input("own-messages-are-not-unread", human: [1, 2, 3], viewing: true),
            input("backlog-above-window", seqs: Array(80...100), read: 10, unread: 90, head: 100, viewing: true, last: 10),
            input("loaded-through-marker", seqs: Array(11...100), read: 100, unread: 0, head: 100, viewing: true, captured: true, marker: 10, count: 90, last: 100),
            input("exhausted-window-counts-exactly", seqs: Array(80...100), read: 10, unread: 90, head: 100, viewing: true, last: 10, exhausted: true),
            input("stale-read-protected-by-pending", viewing: true, captured: true, marker: 0, count: 3, pending: 3, last: 3, action: "accept"),
            input("server-ack-clears-pending", read: 3, unread: 0, viewing: true, captured: true, marker: 0, count: 3, pending: 3, last: 3, action: "accept"),
            input("resume-captures-backlog", seqs: Array(1...5), read: 3, unread: 2, head: 5, last: 3, action: "viewOn"),
            input("resume-keeps-older-target", seqs: Array(1...5), read: 3, unread: 2, head: 5, marker: 0, count: 3, last: 3, action: "viewOn"),
            input("inactive-resets-capture", viewing: true, captured: true, marker: 0, count: 3, last: 3, action: "viewOff"),
            input("end-visit-clears-target", viewing: true, captured: true, marker: 0, count: 3, last: 3, action: "endVisit"),
            input("dismiss-preserves-visit", viewing: true, captured: true, marker: 0, count: 3, last: 3, action: "dismiss"),
            input("manual-read-through", action: "mark", mark: 2),
            input("unloaded-does-not-capture", viewing: true, loaded: false),
            input("no-service-read-state", server: false)
        ]
        let vectors = cases.map { input -> [String: Any] in
            let store = ReadingStore()
            let seqs = input["seqs"] as! [Int], human = input["human"] as! [Int]
            store.messages = seqs.map { .init(id: "m\($0)", seq: $0, senderID: human.contains($0) ? "me" : "chief") }
            store.hasLoadedNewest = input["loaded"] as! Bool
            store.older = input["exhausted"] as! Bool ? .exhausted : .idle
            store.lastReadSeq = input["last"] as! Int; store.pendingReadSeq = input["pending"] as! Int
            store.unreadCount = input["unread"] as! Int; store.catchUpCount = input["count"] as! Int
            store.catchUpMarker = input["marker"] as? Int
            store.catchUpCaptured = input["captured"] as! Bool; store.isViewing = input["viewing"] as! Bool
            if input["server"] as! Bool { store.serverRead = .init(lastReadSeq: input["read"] as! Int,
                unreadCount: input["unread"] as! Int, headSeq: input["head"] as! Int) }
            store.run(input["action"] as! String, input["mark"] as! Int)
            return ["input": input, "expected": ["last": store.lastReadSeq, "unread": store.unreadCount,
                "marker": store.catchUpMarker.map { $0 as Any } ?? NSNull(), "count": store.catchUpCount,
                "viewing": store.isViewing, "captured": store.catchUpCaptured, "pending": store.pendingReadSeq,
                "target": store.catchUpTarget?.id as Any? ?? NSNull()]]
        }
        FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: vectors, options: [.sortedKeys]))
    }
}
