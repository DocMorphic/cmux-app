import SwiftUI
import UIKit
import CmuxConversationCore
import CmuxConversationUI

private struct PollReferences: Decodable {
    var schema: Int; var polls: [String: PollInput]; var scenes: [PollScene]
}
private struct PollInput: Decodable {
    struct Choice: Decodable { var id: String; var text: String; var addedByID: String? }
    struct Vote: Decodable { var participantID: String; var optionID: String }
    struct Participant: Decodable { var id: String; var name: String; var isMe: Bool }
    var question: String; var options: [Choice]; var votes: [Vote]; var participants: [Participant]
}
private struct PollScene: Decodable {
    var id: String; var model: String; var category: String; var style: String
    var orientation: String; var direction: String; var scroll: String
}
private func resource(_ name: String) -> Data {
    try! Data(contentsOf: Bundle.main.url(forResource: name, withExtension: nil)!)
}
private func selected() -> (PollReferences, PollScene) {
    let all = try! JSONDecoder().decode(PollReferences.self, from: resource("PollReferences.json"))
    return (all, all.scenes.first { $0.id == ProcessInfo.processInfo.environment["CMUX_POLL_REFERENCE"] }!)
}

@main struct CmuxChiefPollReferenceApp: App {
    var body: some Scene {
        WindowGroup {
            PollHost().ignoresSafeArea()
                .preferredColorScheme(selected().1.style == "dark" ? .dark : .light)
                .environment(\.layoutDirection, selected().1.direction == "rtl" ? .rightToLeft : .leftToRight)
        }
    }
}

private enum PollReferenceFailure: Error { case readOnly }
private final class PollBackend: ConversationBackend, @unchecked Sendable {
    let messages: [ConversationMessage]
    let info: ConversationInfo
    private let lock = NSLock(); private var writes = 0
    var writeAttempts: Int { lock.withLock { writes } }
    private func reject() -> PollReferenceFailure { lock.withLock { writes += 1 }; return .readOnly }
    init(_ input: PollInput) {
        info = ConversationInfo(id: "chief-main", title: "Chief", kind: .direct,
            participants: input.participants.map {
                ConversationParticipant(id: $0.id, name: $0.name, initials: String($0.name.prefix(2)),
                    colorHex: $0.isMe ? "#0284FE" : "#8E8E93", isMe: $0.isMe)
            })
        let poll = ConversationPoll(question: input.question,
            options: input.options.map { ConversationPollOption(id: $0.id, text: $0.text, addedByID: $0.addedByID) },
            votes: input.votes.map { ConversationPollVote(participantID: $0.participantID, optionID: $0.optionID) })
        messages = [ConversationMessage(id: "poll", seq: 1, clientMessageID: nil,
            senderID: "chief", sentAt: Date().addingTimeInterval(-300), text: "", poll: poll)]
    }
    func events() -> AsyncStream<ConversationBackendEvent> { AsyncStream { c in
        c.yield(.connected(info: info, meID: "me", lagged: false))
        c.yield(.readState(ConversationReadState(lastReadSeq: 1, unreadCount: 0, headSeq: 1)))
        c.finish()
    } }
    func history(beforeSeq: Int?, limit: Int) async throws -> ConversationHistoryPage {
        ConversationHistoryPage(messages: beforeSeq == nil ? messages : [], hasMore: false)
    }
    func send(_ draft: ConversationOutgoingDraft) async throws -> ConversationMessage { throw reject() }
    func react(messageID: String, reaction: ConversationReaction?) async throws -> ConversationMessage { throw reject() }
    func edit(messageID: String, text: String) async throws -> ConversationMessage { throw reject() }
    func unsend(messageID: String) async throws -> ConversationMessage { throw reject() }
    func uploadImage(_ data: Data, mimeType: String) async throws -> ConversationAttachment { throw reject() }
    func votePoll(messageID: String, optionID: String, selected: Bool) async throws -> ConversationMessage { throw reject() }
    func setTyping(_ isTyping: Bool) async {}
    func markRead(upToSeq: Int) async {}
    func close() {}
}

@MainActor private struct PollHost: UIViewControllerRepresentable {
    func makeCoordinator() -> PollObserver { PollObserver() }
    func makeUIViewController(context: Context) -> ConversationViewController {
        context.coordinator.start(); return context.coordinator.controller
    }
    func updateUIViewController(_ uiViewController: ConversationViewController, context: Context) {}
}

/// Public UIKit observation only. The source package and controls are unchanged.
/// A transparent one-point AX node transports measurements, never screenshot pixels.
@MainActor private final class PollObserver {
    let controller: ConversationViewController
    let store: ConversationStore
    let backend: PollBackend
    let fixture: PollScene
    let observation = UIView(frame: CGRect(x: 1, y: 1, width: 1, height: 1))
    private var task: Task<Void, Never>?
    init() {
        let (all, scene) = selected(); fixture = scene
        if let bundle = Bundle.main.bundleIdentifier { UserDefaults.standard.removePersistentDomain(forName: bundle) }
        backend = PollBackend(all.polls[scene.model]!); store = ConversationStore(backend: backend)
        controller = ConversationViewController(store: store, options: ConversationPresentationOptions(
            placeholder: "Message Chief", serviceTitle: "Chief", serviceSubtitle: "Runs your agents on your Mac",
            avatarImage: UIImage(data: resource("ChiefAvatar.png")), trailingSymbol: "stop.fill", trailingAccessibilityLabel: "Stop Chief",
            supportsReactions: false, supportsEditing: false, supportsUnsend: false, supportsAttachments: false))
        controller.overrideUserInterfaceStyle = scene.style == "light" ? .light : .dark
        controller.loadViewIfNeeded()
        controller.view.semanticContentAttribute = scene.direction == "rtl" ? .forceRightToLeft : .forceLeftToRight
        observation.backgroundColor = .clear; observation.isUserInteractionEnabled = false
        observation.isAccessibilityElement = true; observation.accessibilityTraits = .staticText
        observation.accessibilityIdentifier = "reference.poll.observation"
    }
    func start() {
        task = Task { [weak self] in
            while !Task.isCancelled {
                if let self, let window = self.controller.view.window { self.record(window) }
                try? await Task.sleep(for: .milliseconds(80))
            }
        }
    }
    private func descendants(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap(descendants) }
    private func visible(_ view: UIView, window: UIWindow) -> Bool {
        guard view.window === window else { return false }
        var cursor: UIView? = view; var alpha: CGFloat = 1
        var box = view.convert(view.bounds, to: window).intersection(window.bounds)
        while let item = cursor {
            alpha *= item.alpha
            if item.isHidden || alpha <= 0.01 { return false }
            if item.clipsToBounds { box = box.intersection(item.convert(item.bounds, to: window)) }
            cursor = item.superview
        }
        return !box.isNull && !box.isEmpty
    }
    private func rect(_ frame: CGRect) -> [CGFloat] { [frame.minX, frame.minY, frame.width, frame.height] }
    private func frame(_ view: UIView, _ window: UIWindow) -> [CGFloat] { rect(view.convert(view.bounds, to: window)) }
    private func color(_ value: UIColor, traits: UITraitCollection) -> [CGFloat] {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        value.resolvedColor(with: traits).getRed(&r, green: &g, blue: &b, alpha: &a)
        return [r, g, b, a]
    }
    private func record(_ window: UIWindow) {
        if observation.superview !== window { window.addSubview(observation) }
        observation.frame.origin.y = window.safeAreaInsets.top + 1
        window.layoutIfNeeded()
        let views = descendants(window).filter { $0 !== observation && visible($0, window: window) }
        let labels = views.compactMap { view -> [String: Any]? in
            guard let label = view as? UILabel, let text = label.text, !text.isEmpty else { return nil }
            return ["text": text, "frame": frame(label, window), "fontSize": label.font.pointSize,
                "lineHeight": label.font.lineHeight, "lines": label.numberOfLines,
                "colorRGBA": color(label.textColor, traits: label.traitCollection)]
        }
        let table = views.compactMap { $0 as? UITableView }.first { $0.accessibilityIdentifier == "conversation.poll.details" }
        var tableInfo: [String: Any] = [:]
        if let table {
            tableInfo = ["frame": frame(table, window), "style": table.style.rawValue,
                "contentSize": [table.contentSize.width, table.contentSize.height],
                "contentOffset": [table.contentOffset.x, table.contentOffset.y],
                "insets": [table.adjustedContentInset.top, table.adjustedContentInset.left, table.adjustedContentInset.bottom, table.adjustedContentInset.right],
                "backgroundRGBA": color(table.backgroundColor ?? .clear, traits: table.traitCollection)]
            tableInfo["sections"] = (0..<table.numberOfSections).map { section -> [String: Any] in
                let header = table.dataSource?.tableView?(table, titleForHeaderInSection: section) ?? ""
                let footer = table.dataSource?.tableView?(table, titleForFooterInSection: section) ?? ""
                let rows = (0..<table.numberOfRows(inSection: section)).map { row -> [String: Any] in
                    let index = IndexPath(row: row, section: section)
                    let cell = table.cellForRow(at: index)
                    let config = cell?.contentConfiguration as? UIListContentConfiguration
                    return ["row": row, "frame": rect(table.convert(table.rectForRow(at: index), to: window)),
                        "visible": cell.map { visible($0, window: window) } ?? false,
                        "text": config?.text ?? "", "selectionStyle": cell?.selectionStyle.rawValue ?? -1]
                }
                return ["title": header, "footer": footer, "headerFrame": rect(table.convert(table.rectForHeader(inSection: section), to: window)),
                    "footerFrame": rect(table.convert(table.rectForFooter(inSection: section), to: window)), "rows": rows]
            }
        }
        let navigation = controller.presentedViewController as? UINavigationController
        let payload: [String: Any] = ["schema": 1, "scene": fixture.id, "ready": !store.messages.isEmpty,
            "provenance": try! JSONSerialization.jsonObject(with: resource("Provenance.json")),
            "viewport": [window.bounds.width, window.bounds.height], "scale": window.screen.scale,
            "runtime": ["systemVersion": UIDevice.current.systemVersion, "operatingSystemVersion": ProcessInfo.processInfo.operatingSystemVersionString],
            "categoryMatches": UIApplication.shared.preferredContentSizeCategory == (fixture.category == "accessibilityMedium" ? .accessibilityMedium : .large),
            "style": controller.traitCollection.userInterfaceStyle == .dark ? "dark" : "light",
            "direction": controller.view.effectiveUserInterfaceLayoutDirection == .rightToLeft ? "rtl" : "ltr",
            "labels": labels, "table": tableInfo, "presented": navigation != nil,
            "navigationFrame": navigation.map { frame($0.navigationBar, window) } ?? [],
            "navigationPrompt": navigation?.topViewController?.navigationItem.prompt ?? "",
            "backendWriteAttempts": backend.writeAttempts]
        observation.accessibilityLabel = String(data: try! JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]), encoding: .utf8)
    }
}
