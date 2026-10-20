import Foundation

// Inert Chief-only input model. Unsupported reactions/system/schedule fields
// stay empty; run planning and ordering below execute the actual pinned bodies.
public enum ConversationDelivery: Sendable, Equatable {
    case sending, sent, delivered, read(Date?), failed
    var isFailed: Bool { self == .failed }
}
public struct ConversationMessage: Sendable {
    public var id: String
    public var seq: Int?
    public var clientMessageID: String?
    public var senderID: String
    public var sentAt: Date
    public var delivery: ConversationDelivery?
    public var replyToID: String?
    public var isScheduled = false
    public var isUnsent = false
    public var isSystemEvent = false
    public var reactions: [String] = []
    public var scheduledAt: Date? = nil
    public var deliveredQuietly = false
    // PINNED_ROW_ID
}
final class Ordering {
    var messages: [ConversationMessage]
    var failedAnchorSeq: [String: Int]
    var indexByID: [String: Int] = [:]
    init(_ messages: [ConversationMessage], anchors: [String: Int]) { self.messages = messages; failedAnchorSeq = anchors }
    // PINNED_SORT
}

@main struct TimelineFixture {
    static func main() throws {
        func message(_ id: String, _ seq: Int?, _ sender: String, _ seconds: Double, _ delivery: ConversationDelivery? = nil,
                     client: String? = nil, reply: String? = nil) -> ConversationMessage {
            ConversationMessage(id: id, seq: seq, clientMessageID: client, senderID: sender,
                sentAt: Date(timeIntervalSince1970: seconds), delivery: delivery, replyToID: reply)
        }
        let cases: [(String, [ConversationMessage], [String: Int], [String])] = [
            ("one", [message("one", 1, "chief", 0)], [:], []),
            ("same-sender", [message("a", 1, "chief", 0), message("b", 2, "chief", 60)], [:], []),
            ("sender-change", [message("a", 1, "chief", 0), message("b", 2, "me", 60, .sent)], [:], []),
            ("run-gap-edge", [message("a", 1, "chief", 0), message("b", 2, "chief", 300)], [:], []),
            ("within-run", [message("a", 1, "chief", 0), message("b", 2, "chief", 299.999)], [:], []),
            ("hour-edge", [message("a", 1, "chief", 0), message("b", 2, "chief", 3600)], [:], []),
            ("before-hour", [message("a", 1, "chief", 0), message("b", 2, "chief", 3599.999)], [:], []),
            ("reply-break", [message("a", 1, "chief", 0), message("b", 2, "chief", 60, reply: "a")], [:], []),
            ("working-chief", [message("a", 1, "chief", 0)], [:], ["chief"]),
            ("working-other", [message("a", 1, "me", 0, .sent)], [:], ["chief"]),
            ("server-clock-back", [message("a", 1, "chief", 60), message("b", 2, "chief", 0)], [:], []),
            ("in-flight-trails", [message("local:c", nil, "me", 2, .sending, client: "c"), message("b", 2, "chief", 3), message("a", 1, "chief", 1)], [:], []),
            ("failed-anchor-beats-clock", [message("local:c", nil, "me", 100, .failed, client: "c"), message("b", 2, "chief", 0), message("a", 1, "chief", 500)], ["local:c": 1], []),
            ("failed-zero-anchor", [message("a", 1, "chief", 1), message("local:c", nil, "me", 100, .failed, client: "c")], ["local:c": 0], []),
            ("failed-time-fallback", [message("a", 1, "chief", 1), message("b", 2, "chief", 3), message("local:c", nil, "me", 2, .failed, client: "c")], [:], []),
            ("failed-and-flight", [message("local:f", nil, "me", 20, .failed, client: "f"), message("local:c", nil, "me", 2, .sending, client: "c"), message("b", 2, "chief", 3), message("a", 1, "chief", 1)], ["local:f": 1], []),
            ("same-anchor-failures", [message("a", 1, "chief", 1), message("b", 2, "chief", 4), message("local:f1", nil, "me", 2, .failed, client: "f1"), message("local:f2", nil, "me", 3, .failed, client: "f2")], ["local:f1": 1, "local:f2": 1], []),
            ("local-acked-row-id", [message("server", 1, "me", 1, .sent, client: "same-client")], [:], []),
            ("local-pending-row-id", [message("local:same-client", nil, "me", 1, .sending, client: "same-client")], [:], [])
        ]
        let vectors: [[String: Any]] = cases.map { name, messages, anchors, typing in
            let store = Ordering(messages, anchors: anchors); store.sortAndReindex()
            let plan = ConversationRunPlan(messages: store.messages, meID: "me", typingParticipantIDs: typing)
            return ["name": name, "typing": typing, "anchors": anchors,
                "input": messages.map { message -> [String: Any] in
                    ["id": message.id, "seq": message.seq.map { $0 as Any } ?? NSNull(), "client": message.clientMessageID.map { $0 as Any } ?? NSNull(),
                     "sender": message.senderID, "seconds": message.sentAt.timeIntervalSince1970,
                     "failed": message.delivery?.isFailed == true, "reply": message.replyToID.map { $0 as Any } ?? NSNull()]
                },
                "rows": zip(store.messages, plan.entries).map { message, entry -> [String: Any] in
                    ["id": message.id, "key": message.rowID, "timestamp": entry.showsTimestamp,
                     "first": entry.isFirstInRun, "last": entry.isLastInRun]
                }]
        }
        FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: vectors, options: [.sortedKeys]))
    }
}
