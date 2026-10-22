import SwiftUI
import UIKit
import CmuxConversationCore
import CmuxConversationUI

struct TranslationReferences: Decodable {
    var schema: Int; var sameLanguageText: String; var rows: [TranslationRow]; var scenes: [TranslationScene]
}
struct TranslationRow: Decodable { var id: String; var text: String; var translated: String; var outgoing: Bool }
struct TranslationScene: Decodable {
    var id: String; var mode: String; var target: String; var category: String
    var style: String; var orientation: String; var direction: String
}
private func resource(_ name: String) -> Data {
    try! Data(contentsOf: Bundle.main.url(forResource: name, withExtension: nil)!)
}

@main struct CmuxChiefTranslationReferenceApp: App {
    var body: some Scene { WindowGroup { TranslationHost().ignoresSafeArea() } }
}

@MainActor private final class ControlledTranslator: ConversationTranslating {
    let values: [String: String]
    private(set) var calls = 0
    init(_ rows: [TranslationRow]) { values = Dictionary(uniqueKeysWithValues: rows.map { ($0.text, $0.translated) }) }
    func availability(from source: Locale.Language, to target: Locale.Language) async -> ConversationTranslationAvailability { .installed }
    func supportedLanguages() async -> [Locale.Language] { ["es", "en", "fr"].map { Locale.Language(identifier: $0) } }
    func translate(_ texts: [String: String], from source: Locale.Language, to target: Locale.Language) async throws -> [String: String] {
        calls += 1
        return try texts.mapValues { text in
            guard let value = values[text] else { throw ConversationTranslationFailure.failed }
            return value
        }
    }
}

private enum TranslationReferenceFailure: Error { case readOnly }
private final class TranslationBackend: ConversationBackend, @unchecked Sendable {
    let messages: [ConversationMessage]
    let info = ConversationInfo(id: "chief-main", title: "Chief", kind: .direct, participants: [
        ConversationParticipant(id: "chief", name: "Chief", initials: "C", colorHex: "#0284FE", isMe: false),
        ConversationParticipant(id: "me", name: "You", initials: "Y", colorHex: "#0284FE", isMe: true)])
    private let lock = NSLock(); private var writes = 0
    var writeAttempts: Int { lock.withLock { writes } }
    private func reject() -> TranslationReferenceFailure { lock.withLock { writes += 1 }; return .readOnly }
    init(_ rows: [TranslationRow]) {
        messages = rows.enumerated().map { index, row in
            ConversationMessage(id: row.id, seq: index + 1, clientMessageID: nil,
                senderID: row.outgoing ? "me" : "chief", sentAt: Date().addingTimeInterval(Double(-300 + index * 30)),
                text: row.text, delivery: row.outgoing ? .sent : nil)
        }
    }
    func events() -> AsyncStream<ConversationBackendEvent> { AsyncStream { c in
        c.yield(.connected(info: info, meID: "me", lagged: false))
        c.yield(.readState(ConversationReadState(lastReadSeq: messages.count, unreadCount: 0, headSeq: messages.count)))
        c.finish()
    } }
    func history(beforeSeq: Int?, limit: Int) async throws -> ConversationHistoryPage {
        ConversationHistoryPage(messages: Array(messages.filter { beforeSeq == nil || ($0.seq ?? 0) < beforeSeq! }.suffix(limit)), hasMore: false)
    }
    func send(_ draft: ConversationOutgoingDraft) async throws -> ConversationMessage { throw reject() }
    func react(messageID: String, reaction: ConversationReaction?) async throws -> ConversationMessage { throw reject() }
    func edit(messageID: String, text: String) async throws -> ConversationMessage { throw reject() }
    func unsend(messageID: String) async throws -> ConversationMessage { throw reject() }
    func uploadImage(_ data: Data, mimeType: String) async throws -> ConversationAttachment { throw reject() }
    func setTyping(_ isTyping: Bool) async {}
    func markRead(upToSeq: Int) async {}
    func close() {}
}

@MainActor private struct TranslationHost: UIViewControllerRepresentable {
    func makeCoordinator() -> TranslationObserver { TranslationObserver() }
    func makeUIViewController(context: Context) -> ConversationViewController { context.coordinator.start(); return context.coordinator.controller }
    func updateUIViewController(_ uiViewController: ConversationViewController, context: Context) {}
}

/// Observation only: a transparent 1-point AX channel transports public UIKit geometry to the UI-test process.
/// It never replaces source controls, invokes private selectors or draws into captured pixels.
@MainActor private final class TranslationObserver {
    let controller: ConversationViewController
    let store: ConversationStore
    let engine: ControlledTranslator
    let backend: TranslationBackend
    let fixture: TranslationScene
    let observation = UIView(frame: CGRect(x: 1, y: 1, width: 1, height: 1))
    private var task: Task<Void, Never>?
    init() {
        let all = try! JSONDecoder().decode(TranslationReferences.self, from: resource("TranslationReferences.json"))
        let selected = all.scenes.first { $0.id == ProcessInfo.processInfo.environment["CMUX_TRANSLATION_REFERENCE"] }!
        fixture = selected
        if let bundle = Bundle.main.bundleIdentifier { UserDefaults.standard.removePersistentDomain(forName: bundle) }
        let rows = all.rows.map { row -> TranslationRow in
            guard selected.mode == "from", row.id == selected.target else { return row }
            return TranslationRow(id: row.id, text: all.sameLanguageText, translated: row.translated, outgoing: row.outgoing)
        }
        backend = TranslationBackend(rows); store = ConversationStore(backend: backend); engine = ControlledTranslator(rows)
        controller = ConversationViewController(store: store, options: ConversationPresentationOptions(
            placeholder: "Message Chief", serviceTitle: "Chief", serviceSubtitle: "Runs your agents on your Mac",
            avatarImage: UIImage(data: resource("ChiefAvatar.png")), trailingSymbol: "stop.fill", trailingAccessibilityLabel: "Stop Chief",
            supportsReactions: false, supportsEditing: false, supportsUnsend: false, supportsAttachments: false))
        controller.overrideUserInterfaceStyle = fixture.style == "light" ? .light : .dark
        controller.loadViewIfNeeded()
        controller.view.semanticContentAttribute = fixture.direction == "rtl" ? .forceRightToLeft : .forceLeftToRight
        store.translations.translator = engine
        observation.backgroundColor = .clear; observation.isUserInteractionEnabled = false
        observation.isAccessibilityElement = true; observation.accessibilityTraits = .staticText
        observation.accessibilityIdentifier = "reference.translation.observation"
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
    private func rect(_ view: UIView, window: UIWindow) -> [CGFloat] {
        let box = view.convert(view.bounds, to: window); return [box.minX, box.minY, box.width, box.height]
    }
    private func record(_ window: UIWindow) {
        if observation.superview !== window { window.addSubview(observation) }
        observation.frame.origin.y = window.safeAreaInsets.top + 1
        window.layoutIfNeeded()
        let views = descendants(window).filter { $0 !== observation && visible($0, window: window) }
        let labels = views.compactMap { view -> [String: Any]? in
            guard let label = view as? UILabel, let text = label.text, !text.isEmpty else { return nil }
            let attributed = label.attributedText
            let attributes = attributed.flatMap { $0.length > 0 ? $0.attributes(at: 0, effectiveRange: nil) : nil } ?? [:]
            let font = attributes[.font] as? UIFont ?? label.font!
            let alignment = (attributes[.paragraphStyle] as? NSParagraphStyle)?.alignment ?? label.textAlignment
            return ["identifier": label.accessibilityIdentifier ?? "", "class": String(describing: type(of: label)), "text": text,
                "frame": rect(label, window: window), "fontSize": font.pointSize, "lineHeight": font.lineHeight,
                "alignment": alignment.rawValue]
        }
        let controls = views.compactMap { view -> [String: Any]? in
            guard let control = view as? UIControl else { return nil }
            return ["identifier": control.accessibilityIdentifier ?? "", "label": control.accessibilityLabel ?? "",
                "class": String(describing: type(of: control)), "frame": rect(control, window: window), "enabled": control.isEnabled]
        }
        let presentations = store.messages.map { message -> [String: Any] in
            let p = store.translations.presentation(for: message)
            return ["id": message.id, "original": message.text, "shown": p?.text ?? message.text,
                "caption": p.map { String(describing: $0.caption) } ?? "none"]
        }
        let alert = controller.presentedViewController as? UIAlertController
        let payload: [String: Any] = ["schema": 1, "scene": fixture.id, "ready": store.hasLoadedNewest && store.isViewing,
            "provenance": try! JSONSerialization.jsonObject(with: resource("Provenance.json")), "translator": "controlled",
            "viewport": [window.bounds.width, window.bounds.height], "scale": window.screen.scale,
            "controllerFrame": rect(controller.view, window: window),
            "safeArea": [window.safeAreaInsets.top, window.safeAreaInsets.left, window.safeAreaInsets.bottom, window.safeAreaInsets.right],
            "category": UIApplication.shared.preferredContentSizeCategory.rawValue,
            "categoryMatches": UIApplication.shared.preferredContentSizeCategory == (fixture.category == "accessibilityMedium" ? .accessibilityMedium : .large),
            "style": controller.traitCollection.userInterfaceStyle == .dark ? "dark" : "light",
            "direction": controller.view.effectiveUserInterfaceLayoutDirection == .rightToLeft ? "rtl" : "ltr",
            "labels": labels, "controls": controls, "presentations": presentations,
            "alertActions": alert?.actions.compactMap(\.title) ?? [], "alertTitle": alert?.title ?? "",
            "automatic": store.translations.autoTranslation != nil, "translationCalls": engine.calls,
            "backendWriteAttempts": backend.writeAttempts]
        observation.accessibilityLabel = String(data: try! JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]), encoding: .utf8)
    }
}
