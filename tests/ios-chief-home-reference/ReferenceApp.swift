import SwiftUI
import UIKit
import Foundation
import Combine
import CMUXMobileCore
import CmuxMobileRPC
import CmuxMobileShell
@testable import CmuxMobileShellUI

private struct HomeScene: Decodable, Sendable {
    let id: String; let mode: String; let language: String; let style: String; let category: String
    let primaryTabs: Bool?
    let unreadCount: Int?
    static func selected() -> Self {
        let data = try! Data(contentsOf: Bundle.main.url(forResource: "Scenes", withExtension: "json")!)
        let scenes = try! JSONDecoder().decode([Self].self, from: data)
        return scenes.first { $0.id == ProcessInfo.processInfo.environment["CMUX_HOME_REFERENCE"] }!
    }
}

@main struct ChiefHomeReferenceApp: App {
    private let scene = HomeScene.selected()
    private let client: MobileChiefClient
    private let diagnostics: HomeDiagnostics
    init() {
        let scene = HomeScene.selected()
        let diagnostics = HomeDiagnostics()
        self.diagnostics = diagnostics
        let identity = try! CmxIrohPeerIdentity(endpointID: String(repeating: "ab", count: 32))
        let route = try! CmxAttachRoute(id: "offline-reference", kind: .iroh,
            endpoint: .peer(identity: identity, pathHints: []))
        let ticket = try! CmxAttachTicket(workspaceID: "", terminalID: nil,
            macDeviceID: "offline-reference-mac", macDisplayName: "Offline reference",
            routes: [route], expiresAt: nil, authToken: nil)
        let runtime = HomeRuntime(transportFactory: HomeFactory(transport: HomeTransport(mode: scene.mode, unreadCount: scene.unreadCount ?? 2, diagnostics: diagnostics)))
        client = MobileChiefClient(client: MobileCoreRPCClient(runtime: runtime, route: route, ticket: ticket))
    }
    var body: some Scene {
        WindowGroup {
            // The original compiled view owns state, navigation, list and sheet.
            Group {
                if scene.primaryTabs == true {
                    HomeReferenceTabs(client: client)
                } else {
                    ChiefHomeView(client: client)
                }
            }
                .overlay(alignment: .topLeading) { HomeObservation(diagnostics: diagnostics) }
                .preferredColorScheme(scene.style == "dark" ? .dark : .light)
                .environment(\.sizeCategory, scene.category == "large" ? .large : .accessibilityExtraExtraExtraLarge)
        }
    }
}

/// Keep the original tab/search coordinator and platform container. The unused
/// sibling destinations are explicit authored placeholders, not app acceptance.
@MainActor private struct HomeReferenceTabs: View {
    let client: MobileChiefClient
    @State private var selection: MobilePrimaryTab = .home
    @State private var searchCoordinator = MobilePrimarySearchCoordinator()
    var body: some View {
        MobilePrimaryTabScaffold(selection: $selection, searchCoordinator: searchCoordinator,
            notificationUnreadCount: 0, homeContent: AnyView(ChiefHomeView(client: client))) {
                Text("Offline workspace placeholder")
            } feed: {
                Text("Offline Feed placeholder")
            } notifications: {
                Text("Offline notification placeholder")
            } cloud: {
                Text("Offline Cloud placeholder")
            } search: {
                Text("Offline primary search placeholder")
            }
    }
}

@MainActor private final class HomeDiagnostics: ObservableObject {
    @Published var value = "{}"
    private var rpc = "{}"
    func record(_ value: String) { rpc = value; observeWindow() }
    func observeWindow() {
        var report = ((try? JSONSerialization.jsonObject(with: Data(rpc.utf8))) as? [String: Any]) ?? [:]
        var frames: [[Double]] = []
        func walk(_ view: UIView, window: UIWindow) {
            guard !view.isHidden, view.alpha > 0 else { return }
            if view.accessibilityIdentifier == "conversation.poll.option.__chief_answer_questions__" {
                let frame = view.convert(view.bounds, to: window)
                if frame.width > 0, frame.height > 0, frame.intersects(window.bounds) {
                    frames.append([Double(frame.minX), Double(frame.minY), Double(frame.width), Double(frame.height)])
                }
            }
            for child in view.subviews { walk(child, window: window) }
        }
        for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
            for window in scene.windows where !window.isHidden { walk(window, window: window) }
        }
        report["questionEntryFrames"] = frames
        if let bytes = try? JSONSerialization.data(withJSONObject: report, options: [.sortedKeys]) {
            let next = String(decoding: bytes, as: UTF8.self)
            if value != next { value = next }
        }
    }
}
@MainActor private struct HomeObservation: View {
    @ObservedObject var diagnostics: HomeDiagnostics
    var body: some View {
        Color.clear.frame(width: 1, height: 1).allowsHitTesting(false)
            .accessibilityElement(children: .ignore)
            .accessibilityIdentifier("reference.home.observation")
            .accessibilityLabel("Offline Home RPC observation")
            .accessibilityValue(diagnostics.value)
            .task {
                while !Task.isCancelled {
                    diagnostics.observeWindow()
                    do { try await Task.sleep(for: .milliseconds(100)) } catch { return }
                }
            }
    }
}
private enum HomeReferenceError: Error { case tokenProviderForbidden }

/// The sole seam is the byte transport. No sockets, account or live Mac exist.
private struct HomeRuntime: MobileSyncRuntime {
    let transportFactory: any CmxByteTransportFactory
    let stackAccessTokenProvider: @Sendable () async throws -> String = { throw HomeReferenceError.tokenProviderForbidden }
    let stackAccessTokenForceRefresher: @Sendable () async throws -> String = { throw HomeReferenceError.tokenProviderForbidden }
    let rpcRequestTimeoutNanoseconds: UInt64 = 30_000_000_000
    let pairingRequestTimeoutNanoseconds: UInt64 = 30_000_000_000
    let now: @Sendable () -> Date = Date.init
    let supportedRouteKinds: [CmxAttachTransportKind] = [.iroh]
    let supportsServerPushEvents = false
}
private struct HomeFactory: CmxByteTransportFactory {
    let transport: HomeTransport
    func makeTransport(for route: CmxAttachRoute) throws -> any CmxByteTransport {
        precondition(route.kind == .iroh)
        guard case let .peer(identity, hints) = route.endpoint,
            identity.endpointID == String(repeating: "ab", count: 32), hints.isEmpty else {
            preconditionFailure("Unexpected reference peer")
        }
        return transport
    }
}

private actor HomeTransport: CmxByteTransport {
    let mode: String
    let unreadCount: Int
    let diagnostics: HomeDiagnostics
    private var queued: [Data] = []
    private var readers: [CheckedContinuation<Data?, Never>] = []
    private var input = Data()
    private var closed = false
    private var answered = false
    private var requests: [[String: Any]] = []
    init(mode: String, unreadCount: Int, diagnostics: HomeDiagnostics) {
        precondition(unreadCount > 0)
        self.mode = mode; self.unreadCount = unreadCount; self.diagnostics = diagnostics
    }
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
            precondition(request["auth"] == nil, "Offline admitted reference must not request account credentials")
            let method = request["method"] as! String
            let id = request["id"] as! String
            let params = request["params"] as? [String: Any] ?? [:]
            requests.append(["method": method, "params": params])
            let report = try JSONSerialization.data(withJSONObject: ["mode": mode, "unreadCount": unreadCount, "requests": requests], options: [.sortedKeys])
            let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            try report.write(to: documents.appendingPathComponent("reference-rpc.json"), options: .atomic)
            await diagnostics.record(String(decoding: report, as: UTF8.self))
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
            "pinned": true, "working": false, "unread": unreadCount]
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
