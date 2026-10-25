import Foundation

struct MobileChiefQuestion: Sendable { let isPending: Bool }
struct MobileChiefMessage: Sendable { let id: String; let seq: UInt64 }
struct MobileChiefReadResult: Sendable { let messages: [MobileChiefMessage] }
struct ConversationMessage: Sendable { let id: String }
struct ConversationBackendError: Error, Sendable { let code: Int; let message: String }
struct ReadFailure: Error {}
struct AnswerFailure: Error {}

actor SourceClient {
    struct Call: Sendable { let message: String; let choices: [String]; let text: String? }
    enum Read: Sendable { case failure, rows([MobileChiefMessage]) }
    var calls: [Call] = []
    var reads: [Read] = []
    var readBefore: [UInt64?] = []
    private var answers: [Int: CheckedContinuation<Int, any Error>] = [:]
    private var observers: [(Int, CheckedContinuation<Void, Never>)] = []
    func answer(conversation: String, message: String, choices: [String], text: String?) async throws -> Int {
        let index = calls.count
        calls.append(Call(message: message, choices: choices, text: text))
        return try await withCheckedThrowingContinuation { continuation in
            answers[index] = continuation
            let ready = observers.filter { $0.0 <= calls.count }
            observers.removeAll { $0.0 <= calls.count }
            for (_, waiter) in ready { waiter.resume() }
        }
    }
    func waitForCalls(_ count: Int) async {
        if calls.count >= count { return }
        await withCheckedContinuation { observers.append((count, $0)) }
    }
    func finish(_ index: Int, fail: Bool = false) {
        let continuation = answers.removeValue(forKey: index)!
        if fail { continuation.resume(throwing: AnswerFailure()) }
        else { continuation.resume(returning: index) }
    }
    func setReads(_ values: [Read]) { reads = values }
    func read(conversation: String, beforeSeq: UInt64? = nil, limit: Int) async throws -> MobileChiefReadResult {
        readBefore.append(beforeSeq)
        guard !reads.isEmpty else { throw ReadFailure() }
        switch reads.removeFirst() {
        case .failure: throw ReadFailure()
        case .rows(let rows): return MobileChiefReadResult(messages: rows)
        }
    }
}

/// Inert state/publication seams; the generated extension contains the whole
/// original answerQuestion method with no edits to its body or signature.
final class ChiefConversationBackend: @unchecked Sendable {
    static let questionNotWaiting = "not-waiting"
    static let answerRequired = "answer-required"
    static let answeredQuestionMissing = "answer-missing"
    struct State { var previousMessagesByID: [String: MobileChiefMessage] = [:] }
    let client: SourceClient
    let conversationID = "chief-main"
    private let lock = NSLock()
    private var state = State()
    private var pending: Bool?
    init(_ client: SourceClient, pending: Bool? = true, cached: Bool = true, seq: UInt64 = 7) {
        self.client = client; self.pending = pending
        if cached { state.previousMessagesByID["m"] = MobileChiefMessage(id: "m", seq: seq) }
    }
    func withState<T>(_ body: (inout State) -> T) -> T { lock.lock(); defer { lock.unlock() }; return body(&state) }
    func question(for messageID: String) -> MobileChiefQuestion? {
        lock.lock(); defer { lock.unlock() }
        return pending.map { MobileChiefQuestion(isPending: $0) }
    }
    func publishUpdatedMessage(_ message: MobileChiefMessage) -> ConversationMessage {
        withState { $0.previousMessagesByID[message.id] = message }
        return ConversationMessage(id: message.id)
    }
}

@main enum Fixture {
    static func result(_ task: Task<ConversationMessage, any Error>) async -> String {
        do { return "row:" + (try await task.value).id }
        catch let error as ConversationBackendError { return error.message }
        catch is ReadFailure { return "read-failed" }
        catch is AnswerFailure { return "answer-failed" }
        catch { fatalError("Unexpected source error: \(error)") }
    }
    static func main() async throws {
        var cases: [[String: Any]] = []
        for (id, pending, choices, text) in [
            ("missing", nil as Bool?, ["a"], nil as String?),
            ("settled", false, ["a"], nil),
            ("empty", true, [], " \n\t")
        ] {
            let client = SourceClient(); let backend = ChiefConversationBackend(client, pending: pending)
            let task = Task { try await backend.answerQuestion(messageID: "m", choices: choices, text: text) }
            let value = await result(task)
            precondition(value == (id == "empty" ? "answer-required" : "not-waiting"))
            let count = await client.calls.count; precondition(count == 0)
            cases.append(["id": id, "result": value, "answerCalls": count])
        }
        do {
            let client = SourceClient(); let backend = ChiefConversationBackend(client)
            let first = Task { try await backend.answerQuestion(messageID: "m", choices: ["item\u{001F}first"], text: nil) }
            await client.waitForCalls(1)
            let second = Task { try await backend.answerQuestion(messageID: "m", choices: ["item\u{001F}second"], text: nil) }
            await client.waitForCalls(2)
            await client.finish(0, fail: true)
            let firstResult = await result(first); precondition(firstResult == "answer-failed")
            await client.setReads([.failure]); await client.finish(1)
            let secondResult = await result(second); precondition(secondResult == "row:m")
            // A committed answer plus failed refresh does not reserve the pending
            // retained card: the next explicit presentation can submit again.
            let third = Task { try await backend.answerQuestion(messageID: "m", choices: ["item\u{001F}third"], text: nil) }
            await client.waitForCalls(3); await client.setReads([.failure]); await client.finish(2)
            let thirdResult = await result(third); precondition(thirdResult == "row:m")
            let calls = await client.calls
            precondition(calls.map(\.choices) == [["item\u{001F}first"], ["item\u{001F}second"], ["item\u{001F}third"]] && calls.allSatisfy { $0.text == nil })
            cases.append(["id": "reopened-pending-and-retained-after-commit", "answerCalls": calls.count,
                          "choices": calls.map(\.choices),
                          "results": [firstResult, secondResult, thirdResult]])
        }
        do {
            let client = SourceClient(); let backend = ChiefConversationBackend(client)
            let task = Task { try await backend.answerQuestion(messageID: "m", choices: [], text: " Canada \n") }
            await client.waitForCalls(1); await client.setReads([.failure]); await client.finish(0)
            let value = await result(task); let calls = await client.calls
            precondition(value == "row:m" && calls[0].text == "Canada")
            cases.append(["id": "trim-other", "result": value, "choices": calls[0].choices, "text": calls[0].text!])
        }
        for (id, cached, seq, reads, expected, before) in [
            ("targeted-read", true, UInt64(7), [SourceClient.Read.rows([]), .rows([MobileChiefMessage(id: "m", seq: 7)])], "row:m", [nil, 8] as [UInt64?]),
            ("targeted-empty-fallback", true, UInt64(7), [.rows([]), .rows([])], "row:m", [nil, 8]),
            ("targeted-failed-fallback", true, UInt64(7), [.rows([]), .failure], "row:m", [nil, 8]),
            ("max-sequence-fallback", true, UInt64.max, [.rows([])], "row:m", [nil]),
            ("read-error-without-retained-row", false, UInt64(7), [.failure], "read-failed", [nil]),
            ("missing-without-retained-row", false, UInt64(7), [.rows([])], "answer-missing", [nil]),
            ("refreshed-row-without-cache", false, UInt64(7), [.rows([MobileChiefMessage(id: "m", seq: 7)])], "row:m", [nil])
        ] {
            let client = SourceClient(); let backend = ChiefConversationBackend(client, cached: cached, seq: seq)
            await client.setReads(reads)
            let task = Task { try await backend.answerQuestion(messageID: "m", choices: ["a"], text: nil) }
            await client.waitForCalls(1); await client.finish(0)
            let value = await result(task); precondition(value == expected)
            let actualBefore = await client.readBefore; precondition(actualBefore == before)
            cases.append(["id": id, "result": value, "readBefore": actualBefore.map { $0.map(String.init) as Any? ?? NSNull() }])
        }
        FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: ["cases": cases], options: [.prettyPrinted, .sortedKeys]))
    }
}
