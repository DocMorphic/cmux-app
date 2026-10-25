import SwiftUI
import Foundation
import CMUXMobileCore
import CmuxMobileRPC
import CmuxMobileShell
@testable import CmuxMobileShellUI

private struct HomeScene: Decodable, Sendable {
    let id: String; let mode: String; let language: String; let style: String; let category: String
    static func selected() -> Self {
        let data = try! Data(contentsOf: Bundle.main.url(forResource: "Scenes", withExtension: "json")!)
        let scenes = try! JSONDecoder().decode([Self].self, from: data)
        return scenes.first { $0.id == ProcessInfo.processInfo.environment["CMUX_HOME_REFERENCE"] }!
    }
}

@main struct ChiefHomeReferenceApp: App {
    private let scene = HomeScene.selected()
    private let client: MobileChiefClient
    init() {
        let scene = HomeScene.selected()
        let route = try! CmxAttachRoute(id: "offline-reference", kind: .debugLoopback,
            endpoint: .hostPort(host: "127.0.0.1", port: 59999))
        let ticket = try! CmxAttachTicket(workspaceID: "", terminalID: nil,
            macDeviceID: "offline-reference-mac", macDisplayName: "Offline reference",
            routes: [route], expiresAt: nil, authToken: nil)
        let runtime = HomeRuntime(transportFactory: HomeFactory(transport: HomeTransport(mode: scene.mode)))
        client = MobileChiefClient(client: MobileCoreRPCClient(runtime: runtime, route: route, ticket: ticket))
    }
    var body: some Scene {
        WindowGroup {
            // The original compiled view owns state, navigation, list and sheet.
            ChiefHomeView(client: client)
                .preferredColorScheme(scene.style == "dark" ? .dark : .light)
                .environment(\.sizeCategory, scene.category == "large" ? .large : .accessibilityExtraExtraExtraLarge)
        }
    }
}

/// The sole seam is the byte transport. No sockets, account or live Mac exist.
private struct HomeRuntime: MobileSyncRuntime {
    let transportFactory: any CmxByteTransportFactory
    let stackAccessTokenProvider: @Sendable () async throws -> String = { "offline-reference-only" }
    let stackAccessTokenForceRefresher: @Sendable () async throws -> String = { "offline-reference-only" }
    let rpcRequestTimeoutNanoseconds: UInt64 = 30_000_000_000
    let pairingRequestTimeoutNanoseconds: UInt64 = 30_000_000_000
    let now: @Sendable () -> Date = Date.init
    let supportedRouteKinds: [CmxAttachTransportKind] = [.debugLoopback]
    let supportsServerPushEvents = false
}
private struct HomeFactory: CmxByteTransportFactory {
    let transport: HomeTransport
    func makeTransport(for route: CmxAttachRoute) throws -> any CmxByteTransport {
        precondition(route.kind == .debugLoopback)
        return transport
    }
}

private actor HomeTransport: CmxByteTransport {
    let mode: String
    private var queued: [Data] = []
    private var readers: [CheckedContinuation<Data?, Never>] = []
    private var input = Data()
    private var closed = false
    private var answered = false
    private var requests: [[String: Any]] = []
    init(mode: String) { self.mode = mode }
    func connect() async throws {}
    func receive() async throws -> Data? {
        if !queued.isEmpty { return queued.removeFirst() }
        if closed { return nil }
        return await withCheckedContinuation { readers.append($0) }
    }
    func close() async {
        closed = true
        let pending = readers; readers = []
        pending.forEach { $0.resume(returning: nil) }
    }
    func send(_ data: Data) async throws {
        input.append(data)
        for bytes in try MobileSyncFrameCodec.decodeFrames(from: &input) {
            let request = try JSONSerialization.jsonObject(with: bytes) as! [String: Any]
            let method = request["method"] as! String
            let id = request["id"] as! String
            let params = request["params"] as? [String: Any] ?? [:]
            requests.append(["method": method, "params": params])
            let report = try JSONSerialization.data(withJSONObject: ["mode": mode, "requests": requests], options: [.sortedKeys])
            let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            try report.write(to: documents.appendingPathComponent("reference-rpc.json"), options: .atomic)
            // A held event-backed read must not become an artificial busy loop.
            if params["wait_for_change"] as? Bool == true { continue }
            if mode == "loading" && method == "chief.conversations" { continue }
            var result: [String: Any]
            switch method {
            case "chief.conversations":
                result = ["conversations": conversations(), "change_cursor": "offline-home-1"]
            case "chief.status":
                result = ["started": false, "start_failure": mode == "failure" ? "Reference Chief could not start" : ""]
            case "chief.read":
                result = ["conversation": "chief-main", "working": false, "has_more": false,
                    "change_cursor": answered ? "offline-read-2" : "offline-read-1", "messages": questionMessages()]
            case "chief.mark_read":
                result = ["conversation": "chief-main", "up_to_seq": 1, "marked": true]
            case "chief.answer":
                // Only the explicit reference question may be answered.
                guard mode == "question", params["message"] as? String == "question-message",
                    params["choices"] as? [String] == ["region\u{001F}us"], params["text"] == nil else {
                    preconditionFailure("Unexpected reference mutation")
                }
                answered = true
                result = ["conversation": "chief-main", "message": "question-message", "answer": ["region\u{001F}us"]]
            default:
                preconditionFailure("Unsupported reference RPC: \(method)")
            }
            let payload = try JSONSerialization.data(withJSONObject: ["id": id, "ok": true, "result": result])
            let frame = try MobileSyncFrameCodec.encodeFrame(payload)
            if !readers.isEmpty { readers.removeFirst().resume(returning: frame) } else { queued.append(frame) }
        }
    }
    private func conversations() -> [[String: Any]] {
        if mode == "empty" || mode == "failure" { return [] }
        let pinned: [String: Any] = ["id": "chief-main", "title": "Chief", "preview": "Ready on your Mac",
            "pinned": true, "working": false, "unread": 2]
        if mode == "pinned" || mode == "question" { return [pinned] }
        return [pinned,
            ["id": "working", "title": "Build", "preview": "Compiling the app", "working": true, "unread": 3],
            ["id": "unicode", "title": "Straße", "preview": "Café and a canonically composed search", "unread": 1],
            ["id": "empty-preview", "title": "No preview", "preview": "", "unread": 0]]
    }
    private func questionMessages() -> [[String: Any]] {
        guard mode == "question" else { return [] }
        let state: [String: Any] = answered
            ? ["kind": "answered", "answer": ["selections": ["region": ["optionIDs": ["us"]]]]]
            : ["kind": "pending"]
        let question: [String: Any] = ["id": "question-1", "source": ["harness": "chief", "session": "chief-main", "agentName": "Chief"],
            "items": [["id": "region", "prompt": "Which region?", "allowsOther": true,
                "options": [["id": "us", "label": "United States", "detail": "Recommended"], ["id": "eu", "label": "Europe"]]]], "state": state]
        return [["id": "question-message", "seq": 1, "author_id": "agent_chief", "author": "Chief", "author_kind": "chief",
            "text": "Which region?", "at": "2026-10-25T12:00:00Z", "parts": ["question"],
            "part_payloads": [["kind": "question", "question": question]]]]
    }
}
