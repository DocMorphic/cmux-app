import Foundation

struct ConversationMessage {
    var id: String
    var seq: Int?
    var clientMessageID: String?
    var replyToID: String?
    // PINNED_ROW_ID
}
enum Older { case idle, exhausted, loading, retrying }
enum Change { case reset }
struct Polls {
    var pendingVotes: [String: [String]] = [:]
    var pendingOptions: [String: [String]] = [:]
    var server: [String: Int] = [:]
}
final class WindowStore {
    var messages: [ConversationMessage] = []
    var indexByID: [String: Int] = [:]
    var hasLoadedNewest = true
    var older = Older.idle
    var olderWanted = false
    var catchUpMarker: Int?
    var unreadCount = 0
    var lastReadSeq = 0
    var polls = Polls()
    var pollFailures: [String: Int] = [:]
    func notify(_ change: Change) {}
    func refreshReadState() {}
    // PINNED_CONSTANTS
    // PINNED_TRIM
    // PINNED_IF_LARGE
}

@main struct WindowFixture {
    static func main() throws {
        func input(_ name: String, total: Int = 10, keep: Int = 3, loaded: Bool = true, paging: String = "idle",
                   marker: Int? = nil, unread: Int = 0, last: Int = 0, pinned: [String] = [],
                   votes: [String] = [], options: [String] = [], replies: [String: String] = [:],
                   clients: [String: String] = [:], local: Int? = nil, large: Bool = false) -> [String: Any] {
            ["name": name, "total": total, "keep": keep, "loaded": loaded, "paging": paging,
             "marker": marker.map { $0 as Any } ?? NSNull(), "unread": unread, "last": last, "pinned": pinned,
             "votes": votes, "options": options, "replies": replies, "clients": clients,
             "local": local.map { $0 as Any } ?? NSNull(), "large": large]
        }
        let cases = [
            input("ordinary"), input("not-loaded", loaded: false), input("zero-keep", keep: 0),
            input("too-short", total: 2), input("loading", paging: "loading"), input("retrying", paging: "retrying"),
            input("catch-up-context", marker: 4), input("unread-context", unread: 8, last: 2),
            input("two-floors", marker: 6, unread: 8, last: 2), input("pinned-id", pinned: ["m2"]),
            input("pinned-row-key", pinned: ["c:owner"], clients: ["m2": "owner"]),
            input("pending-vote", votes: ["m3"]), input("pending-option", options: ["m4"]),
            input("quoted-parent", replies: ["m10": "m2"]), input("quoted-chain", replies: ["m10": "m2", "m2": "m1"]),
            input("missing-parent", replies: ["m10": "missing"]), input("local-before-cut", local: 2),
            input("local-at-tail", local: 10), input("at-threshold", total: 1500, large: true),
            input("over-threshold", total: 1501, large: true),
            input("large-keeps-targets", total: 1501, marker: 400, pinned: ["m200"], large: true)
        ]
        let vectors = cases.map { input -> [String: Any] in
            let store = WindowStore(), total = input["total"] as! Int
            let replies = input["replies"] as! [String: String], clients = input["clients"] as! [String: String]
            store.messages = (1...total).map { seq in
                let id = "m\(seq)"
                return .init(id: id, seq: seq, clientMessageID: clients[id], replyToID: replies[id])
            }
            if let index = input["local"] as? Int {
                store.messages.insert(.init(id: "local:pending", seq: nil, clientMessageID: "pending", replyToID: nil), at: index)
            }
            store.indexByID = Dictionary(uniqueKeysWithValues: store.messages.enumerated().map { ($0.element.id, $0.offset) })
            store.hasLoadedNewest = input["loaded"] as! Bool
            store.older = ["idle": .idle, "loading": .loading, "retrying": .retrying, "exhausted": .exhausted][input["paging"] as! String]!
            store.catchUpMarker = input["marker"] as? Int
            store.unreadCount = input["unread"] as! Int; store.lastReadSeq = input["last"] as! Int
            for id in input["votes"] as! [String] { store.polls.pendingVotes[id] = ["choice"] }
            for id in input["options"] as! [String] { store.polls.pendingOptions[id] = ["choice"] }
            let count = store.messages.count, pinned = Set(input["pinned"] as! [String])
            let changed = input["large"] as! Bool ? store.trimOlderIfLarge(preserving: pinned)
                : store.trimOlder(keepingNewest: input["keep"] as! Int, preserving: pinned)
            return ["input": input, "cut": count - store.messages.count, "changed": changed,
                    "first": store.messages.first?.rowID as Any? ?? NSNull(), "remaining": store.messages.count]
        }
        FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: ["threshold": WindowStore.windowTrimThreshold,
            "keep": WindowStore.windowKeepCount, "cases": vectors], options: [.sortedKeys]))
    }
}
