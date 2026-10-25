import Foundation
import CmuxAuthRuntime

private final class Observation: @unchecked Sendable {
    private let lock = NSLock()
    private var lines: [String] = []
    private var requests: [URLRequest] = []
    func log(_ line: String) { lock.lock(); defer { lock.unlock() }; lines.append(line) }
    func request(_ value: URLRequest) { lock.lock(); defer { lock.unlock() }; requests.append(value) }
    func snapshot() -> ([String], [URLRequest]) {
        lock.lock(); defer { lock.unlock() }; return (lines, requests)
    }
}
private let observation = Observation()
func cmuxDebugLog(_ value: @autoclosure () -> String) { observation.log(value()) }

enum MobileHostIdentity {
    static func deviceID() -> String { "fixture-mac" }
    static func instanceTag() -> String { CommandLine.arguments[1].contains("stable") ? "default" : "fixture-instance" }
}
@MainActor enum PresenceHeartbeatClient {
    static var serviceURL: URL? = URL(string: "https://fixture.invalid")
    static func resolvedServiceURL() -> URL? { serviceURL }
}

private final class FixtureProtocol: URLProtocol, @unchecked Sendable {
    static let scenario = CommandLine.arguments[1]
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        observation.request(request)
        if CommandLine.arguments.count == 3 {
            let query = Dictionary(uniqueKeysWithValues: (URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems ?? []).map {
                ($0.name, $0.value.map { $0 as Any } ?? NSNull())
            })
            var bytes = request.httpBody ?? Data()
            if bytes.isEmpty, let stream = request.httpBodyStream {
                stream.open(); defer { stream.close() }
                var buffer = [UInt8](repeating: 0, count: 4096)
                while true { let count = stream.read(&buffer, maxLength: buffer.count)
                    precondition(count >= 0); if count == 0 { break }; bytes.append(contentsOf: buffer.prefix(count)) }
            }
            let body: Any = bytes.isEmpty ? NSNull() : try! JSONSerialization.jsonObject(with: bytes)
            let record: [String: Any] = ["query": query, "body": body]
            try! JSONSerialization.data(withJSONObject: record, options: [.sortedKeys])
                .write(to: URL(fileURLWithPath: CommandLine.arguments[2]))
        }
        if Self.scenario.contains("transport-") {
            let code: URLError.Code = Self.scenario.hasSuffix("cancelled") ? .cancelled : .timedOut
            client?.urlProtocol(self, didFailWithError: URLError(code,
                userInfo: [NSLocalizedDescriptionKey: "fixture-secret-error-description"]))
            return
        }
        let response: URLResponse
        if Self.scenario == "non-http" {
            response = URLResponse(url: request.url!, mimeType: "application/json", expectedContentLength: 0, textEncodingName: nil)
        } else {
            let status = Int(Self.scenario.split(separator: "-").last!) ?? 200
            response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil,
                headerFields: ["Retry-After": "30"])!
        }
        let body: String
        switch Self.scenario {
        case "malformed-json": body = "fixture-secret-response-body"
        case "plaintext-rejected":
            body = #"{"replies":[{"replyId":"r","macDeviceId":"fixture-mac","text":"fixture-secret-reply","createdAtMs":1,"expiresAtMs":2}]}"#
        case "encrypted":
            body = #"{"replies":[{"replyId":"r","macDeviceId":"fixture-mac","encryptedPayload":{"marker":"inert"},"createdAtMs":1,"expiresAtMs":2}]}"#
        default: body = #"{"replies":[]}"#
        }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@main private struct InboxFixture {
    @MainActor static func main() async {
        let scenario = FixtureProtocol.scenario
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [FixtureProtocol.self]
        let session = URLSession(configuration: config)
        defer { session.invalidateAndCancel() }
        let client = PhoneReplyInboxClient(session: session)
        let auth = AuthCoordinator()
        auth.fails = scenario == "token-fail"
        if scenario != "auth-missing" { client.configure(auth: auth) }
        if scenario == "service-missing" { PresenceHeartbeatClient.serviceURL = nil }
        let isAck = scenario.hasPrefix("ack-")
        if isAck {
            let accepted = await client.acknowledge(replyIds: scenario == "ack-empty" ? [] : ["fixture-reply"])
            precondition(accepted == (scenario == "ack-empty" || scenario == "ack-success" || scenario == "ack-stable-success"))
        } else {
            let replies = await client.fetchPending()
            if scenario == "empty" || scenario == "stable-empty" { precondition(replies == []) }
            else if scenario == "encrypted" { precondition(replies?.count == 1 && replies?.first?.encryptedPayload != nil) }
            else { precondition(replies == nil) }
        }
        let (logs, requests) = observation.snapshot()
        let expected: (String, Int)?
        switch scenario {
        case "auth-missing", "token-fail": expected = ("authUnavailable", 0)
        case "service-missing": expected = ("serviceUnavailable", 0)
        case "http-401": expected = ("http", 401)
        case "http-403", "ack-http-403": expected = ("http", 403)
        case "http-429": expected = ("http", 429)
        case "http-503": expected = ("http", 503)
        case "non-http": expected = ("nonHTTP", 0)
        case "malformed-json", "plaintext-rejected": expected = ("decoding", 0)
        case "transport-timeout", "ack-transport-timeout": expected = ("transport", -1001)
        case "transport-cancelled": expected = ("cancelled", -999)
        default: expected = nil
        }
        if let (cause, code) = expected {
            precondition(logs == ["phoneReply.requestFailed operation=\(isAck ? "ack" : "fetch") cause=\(cause) code=\(code)"])
        } else { precondition(logs.isEmpty) }
        precondition(logs.allSatisfy { !$0.contains("fixture-") && !$0.contains("https:") })
        if ["auth-missing", "token-fail", "service-missing", "ack-empty"].contains(scenario) {
            precondition(requests.isEmpty)
        } else {
            precondition(requests.count == 1)
            let request = requests[0]
            precondition(request.value(forHTTPHeaderField: "Authorization") == "Bearer fixture-secret-access")
            precondition(request.timeoutInterval == 10)
            precondition(request.url?.host == "fixture.invalid")
            if isAck {
                precondition(request.httpMethod == "POST" && request.url?.path == "/v1/replies/e2e/ack")
                precondition(request.value(forHTTPHeaderField: "content-type") == "application/json")
            } else {
                precondition(request.httpMethod == "GET" && request.url?.path == "/v1/replies/e2e")
                var expected = [URLQueryItem(name: "macDeviceId", value: "fixture-mac")]
                expected.append(URLQueryItem(name: "macInstanceTag", value: MobileHostIdentity.instanceTag()))
                expected.append(URLQueryItem(name: "macBuildID", value: Bundle.main.bundleIdentifier ?? "cmux"))
                precondition(URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems == expected)
            }
        }
        print("PASS \(scenario): outcome, coarse event, request contract and privacy")
    }
}
