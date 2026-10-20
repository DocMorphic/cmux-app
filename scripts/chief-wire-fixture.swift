import Foundation

/// Only records fixture requests and returns synthetic local JSON; no transport.
public actor MobileCoreRPCClient {
    struct Record: Codable { let json: String; let timeoutNanoseconds: UInt64?; let response: String }
    private var records: [Record] = []
    public static func requestData(method: String, params: [String: Any]) throws -> Data {
        try JSONSerialization.data(withJSONObject: ["method": method, "params": params], options: [.sortedKeys])
    }
    public func sendRequest(_ request: Data, timeoutNanoseconds: UInt64?) async throws -> Data {
        let object = try JSONSerialization.jsonObject(with: request) as! [String: Any]
        let response: String
        switch object["method"] as! String {
        case "chief.status", "chief.conversations": response = "{}"
        case "chief.read": response = #"{"conversation":"chief-main"}"#
        case "chief.mark_read": response = #"{"conversation":"chief-main","up_to_seq":18446744073709551615,"marked":true}"#
        case "chief.send": response = #"{"conversation":"chief-main","message_id":"sent","client_message_id":"client-id","queued":true}"#
        case "chief.stop": response = #"{"conversation":"chief-main","stopped":true}"#
        case "chief.approve": response = #"{"conversation":"chief-main","request":"r-1","choice":"allow","decided":true}"#
        case "chief.answer": response = #"{"conversation":"chief-main","message":"q-1","answer":["US"]}"#
        case "chief.open": response = #"{"opened":true}"#
        default: fatalError("Unexpected fixture method")
        }
        records.append(Record(json: String(decoding: request, as: UTF8.self), timeoutNanoseconds: timeoutNanoseconds, response: response))
        return Data(response.utf8)
    }
    func snapshot() -> [Record] { records }
}

@main struct ChiefWireFixture {
    struct Model: Codable { let name: String; let kind: String; let input: String; let decoded: String? }
    struct Problem: Codable { let kind: String; let item: String?; let option: String? }
    struct QuestionCase: Codable { let name: String; let question: String; let answer: String; let problems: [Problem] }
    struct Vectors: Codable { let models: [Model]; let requests: [MobileCoreRPCClient.Record]; let questions: [QuestionCase] }
    static func questionCase(_ name: String, _ question: String, _ answer: String) throws -> QuestionCase {
        let coder = ChatWireCoding()
        let questionModel = try coder.decode(AgentQuestion.self, from: Data(question.utf8))
        let answerModel = try coder.decode(AgentQuestionAnswer.self, from: Data(answer.utf8))
        let problems = answerModel.problems(for: questionModel).map { problem in
            switch problem {
            case .unanswered(let item): Problem(kind: "UNANSWERED", item: item, option: nil)
            case .tooManyChoices(let item): Problem(kind: "TOO_MANY_CHOICES", item: item, option: nil)
            case .unknownOption(let item, let option): Problem(kind: "UNKNOWN_OPTION", item: item, option: option)
            case .otherNotAllowed(let item): Problem(kind: "OTHER_NOT_ALLOWED", item: item, option: nil)
            case .unknownItem(let item): Problem(kind: "UNKNOWN_ITEM", item: item, option: nil)
            case .notPending: Problem(kind: "NOT_PENDING", item: nil, option: nil)
            }
        }
        return QuestionCase(name: name, question: question, answer: answer, problems: problems)
    }
    static func model<T: Codable>(_ name: String, _ kind: String, _ input: String, _ type: T.Type) -> Model {
        let coder = ChatWireCoding()
        let output = try? coder.encode(coder.decode(type, from: Data(input.utf8)))
        return Model(name: name, kind: kind, input: input, decoded: output.map { String(decoding: $0, as: UTF8.self) })
    }
    static func main() async throws {
        let message = #"{"id":"m-1","seq":18446744073709551615,"author_id":"agent_mux","author":"Chief","author_kind":"chief","text":"I am on it.","at":"2026-10-16T12:30:45Z","parts":["text","work"],"part_payloads":[{"kind":"work","work":{"session":"acp-1","host":"builder","title":"Ship it","status":"running","preview":"Building"}},{"kind":"future"}],"thread_root":"m-0","client_message_id":"client-id"}"#
        let question = #"{"id":"q-1","source":{"harness":"chief","session":"chief-main","agentName":"Chief"},"items":[{"id":"q0","prompt":"Which region?","options":[{"id":"us","label":"US","detail":"Local","preview":{"format":"markdown","text":"**US**"}}]}],"state":{"kind":"pending"}}"#
        let answered = question.replacingOccurrences(of: #""state":{"kind":"pending"}"#,
            with: #""state":{"kind":"answered","answer":{"selections":{"q0":{"optionIDs":["us"],"other":"custom"}},"respondent":{"participant":"user_local","displayName":"Me","device":"Phone","isRemote":true},"answeredAtMs":1792495435000}}"#)
        let models = [
            model("status-defaults", "status", "{}", MobileChiefStatus.self),
            model("status-large-count", "status", #"{"started":true,"unread":2147483648,"conversation_count":3,"working":["chief-main"],"default_conversation":"chief-main"}"#, MobileChiefStatus.self),
            model("conversation-defaults", "conversation", #"{"id":"chief-main"}"#, MobileChiefConversation.self),
            model("message-wide-cursor", "message", message, MobileChiefMessage.self),
            model("message-legacy", "message", #"{"id":"legacy","seq":1,"author_id":"human","author":"Me","author_kind":"human","text":"Hello","at":"2026-10-16T12:30:45Z"}"#, MobileChiefMessage.self),
            model("read-defaults", "read", #"{"conversation":"chief-main"}"#, MobileChiefReadResult.self),
            model("question-defaults", "question", question, AgentQuestion.self),
            model("question-future-state", "question", question.replacingOccurrences(of: "pending", with: "future"), AgentQuestion.self),
            model("question-answered", "question", answered, AgentQuestion.self),
            model("approval", "part", #"{"kind":"approval","approval":{"request":"r-1","prompt":"Run deploy?","options":["allow","deny"],"decided":"allow"}}"#, MobileChiefPart.self),
            model("read-cursors", "read", #"{"conversation":"chief-main","working":true,"messages":[],"has_more":true,"last_read_seq":0,"unread_count":2147483648,"head_seq":18446744073709551615,"change_cursor":"opaque"}"#, MobileChiefReadResult.self),
            model("conversation-full", "conversation", #"{"id":"chief-main","title":"Chief","preview":"Working","unread":5,"pinned":true,"working":true,"updated_at":"2026-10-16T12:30:45Z"}"#, MobileChiefConversation.self),
            model("question-unknown-harness", "question", question.replacingOccurrences(of: #""harness":"chief""#, with: #""harness":"future""#), AgentQuestion.self),
            model("message-negative-seq", "message", message.replacingOccurrences(of: "18446744073709551615", with: "-1"), MobileChiefMessage.self),
            model("message-string-seq", "message", message.replacingOccurrences(of: "18446744073709551615", with: #""7""#), MobileChiefMessage.self),
            model("message-integral-decimal", "message", message.replacingOccurrences(of: "18446744073709551615", with: "7.0"), MobileChiefMessage.self),
            model("message-integral-exponent", "message", message.replacingOccurrences(of: "18446744073709551615", with: "7e0"), MobileChiefMessage.self),
            model("message-wide-decimal", "message", message.replacingOccurrences(of: "18446744073709551615", with: "18446744073709551615.0"), MobileChiefMessage.self),
            model("message-fractional-seq", "message", message.replacingOccurrences(of: "18446744073709551615", with: "7.5"), MobileChiefMessage.self),
            model("status-string-bool", "status", #"{"started":"true"}"#, MobileChiefStatus.self),
            model("message-missing-author", "message", message.replacingOccurrences(of: #""author":"Chief",""#, with: ""), MobileChiefMessage.self),
        ]
        let wire = MobileCoreRPCClient()
        let client = MobileChiefClient(client: wire)
        _ = try await client.status()
        _ = try await client.conversationsSnapshot(afterCursor: "owner-cursor", waitForChange: true)
        _ = try await client.read(conversation: "chief-main", beforeSeq: UInt64.max, limit: 500, afterSeq: 9, afterCursor: "transcript-cursor", waitForChange: true)
        _ = try await client.markRead(conversation: "chief-main", upToSeq: UInt64.max)
        _ = try await client.send("Hello", conversation: "chief-main", from: "user_local", startTurn: false, clientMessageID: "client-id")
        _ = try await client.stop(conversation: "chief-main")
        _ = try await client.approve(conversation: "chief-main", request: "r-1", choice: "allow")
        _ = try await client.answer(conversation: "chief-main", message: "q-1", choices: ["US"], text: "Other")
        _ = try await client.open(windowID: "window", workspaceID: "workspace")
        let questions = try [
            questionCase("unanswered", question, #"{"selections":{}}"#),
            questionCase("valid-option", question, #"{"selections":{"q0":{"optionIDs":["us"]}}}"#),
            questionCase("valid-other", question, #"{"selections":{"q0":{"optionIDs":[],"other":"Other"}}}"#),
            questionCase("unknown-option", question, #"{"selections":{"q0":{"optionIDs":["missing"]}}}"#),
            questionCase("unknown-items-ordered", question, #"{"selections":{"z":{"optionIDs":["x"]},"a":{"optionIDs":["x"]}}}"#),
            questionCase("too-many", question, #"{"selections":{"q0":{"optionIDs":["us"],"other":"Other"}}}"#),
            questionCase("other-not-allowed", question.replacingOccurrences(of: #""prompt":"Which region?""#, with: #""allowsOther":false,"prompt":"Which region?""#), #"{"selections":{"q0":{"optionIDs":[],"other":"Other"}}}"#),
            questionCase("owner-already-answered", answered, #"{"selections":{"q0":{"optionIDs":["us"]}}}"#),
        ]
        let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        FileHandle.standardOutput.write(try encoder.encode(Vectors(models: models, requests: await wire.snapshot(), questions: questions)))
        FileHandle.standardOutput.write(Data("\n".utf8))
    }
}
