import XCTest
import UIKit
import QuartzCore
import CryptoKit
import CmuxConversationCore
import CmuxConversationUI

private struct RichComposerCases: Decodable { var schema: Int; var cases: [RichComposerCase] }
private struct RichComposerCase: Decodable {
    var id: String; var style: String; var backend: String; var actions: [RichComposerAction]
}
private struct RichComposerAction: Codable {
    var kind: String; var text: String?; var start: Int?; var length: Int?; var selection: String?; var target: String?
}
private enum RichComposerFailure: Error { case fixtureRejected, invalidAction, missingSelection }
private func richNullable<T>(_ value: T?) -> Any { value.map { $0 as Any } ?? NSNull() }

/// Authored, in-memory transport only. A plain ACK is deliberately a fixture choice,
/// not an execution or emulation of ChiefConversationBackend's read/correlation logic.
private final class RichComposerBackend: ConversationBackend, @unchecked Sendable {
    let mode: String
    let info = ConversationInfo(id: "chief-rich-fixture", title: "Chief", kind: .direct, participants: [
        ConversationParticipant(id: "chief", name: "Chief", initials: "C", colorHex: "#0284FE", isMe: false),
        ConversationParticipant(id: "me", name: "You", initials: "Y", colorHex: "#0284FE", isMe: true)])
    private let lock = NSLock()
    private var captured: [ConversationOutgoingDraft] = []
    private var unsupported = 0
    var drafts: [ConversationOutgoingDraft] { lock.withLock { captured } }
    var unsupportedWrites: Int { lock.withLock { unsupported } }
    init(mode: String) { self.mode = mode }
    private func reject() -> RichComposerFailure { lock.withLock { unsupported += 1 }; return .fixtureRejected }
    func events() -> AsyncStream<ConversationBackendEvent> { AsyncStream { c in
        c.yield(.connected(info: info, meID: "me", lagged: false))
        c.yield(.readState(ConversationReadState(lastReadSeq: 0, unreadCount: 0, headSeq: 0)))
        c.finish()
    } }
    func history(beforeSeq: Int?, limit: Int) async throws -> ConversationHistoryPage {
        ConversationHistoryPage(messages: [], hasMore: false)
    }
    func send(_ draft: ConversationOutgoingDraft) async throws -> ConversationMessage {
        lock.withLock { captured.append(draft) }
        if mode == "reject" { throw RichComposerFailure.fixtureRejected }
        return ConversationMessage(id: "fixture-ack-" + draft.clientMessageID, seq: 1,
            clientMessageID: draft.clientMessageID, senderID: "me", sentAt: Date(), text: draft.text, delivery: .sent)
    }
    func react(messageID: String, reaction: ConversationReaction?) async throws -> ConversationMessage { throw reject() }
    func edit(messageID: String, text: String) async throws -> ConversationMessage { throw reject() }
    func unsend(messageID: String) async throws -> ConversationMessage { throw reject() }
    func uploadImage(_ data: Data, mimeType: String) async throws -> ConversationAttachment { throw reject() }
    func setTyping(_ isTyping: Bool) async {}
    func markRead(upToSeq: Int) async {}
    func close() {}
}

/// Native public-API semantic observations. The actual pinned controller, UITextView
/// delegate/menu action and palette controls run unchanged. These are programmatic
/// API/control actions, not keyboard gestures, XCUIScreen captures or Android acceptance.
final class CmuxChiefRichComposerTests: XCTestCase {
    @MainActor private func descendants(_ root: UIView) -> [UIView] { [root] + root.subviews.flatMap(descendants) }
    @MainActor private func wait(_ description: String, _ predicate: () -> Bool) async throws {
        let deadline = CACurrentMediaTime() + 10
        while !predicate() {
            if CACurrentMediaTime() > deadline { XCTFail("Timed out: " + description); throw RichComposerFailure.invalidAction }
            try await Task.sleep(for: .milliseconds(30))
        }
    }
    @MainActor private func settle(_ text: UITextView) async throws {
        try await Task.sleep(for: .milliseconds(180))
        text.window?.layoutIfNeeded(); text.layoutIfNeeded(); text.inputView?.layoutIfNeeded()
    }
    @MainActor private func runs(_ values: [ConversationTextRun]) -> [[String: Any]] {
        values.map { ["location": $0.location, "length": $0.length, "style": $0.style.rawValue,
                      "effect": richNullable($0.effect?.rawValue)] }
    }
    @MainActor private func color(_ value: Any?, traits: UITraitCollection) -> Any {
        guard let ink = value as? UIColor else { return NSNull() }
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        guard ink.resolvedColor(with: traits).getRed(&r, green: &g, blue: &b, alpha: &a) else { return NSNull() }
        return [r, g, b, a]
    }
    @MainActor private func attributes(_ values: [NSAttributedString.Key: Any], traits: UITraitCollection) -> [String: Any] {
        let font = values[.font] as? UIFont
        let paragraph = values[.paragraphStyle] as? NSParagraphStyle
        return ["style": values[.conversationTextStyle] as? Int ?? 0,
            "effect": richNullable(values[.conversationTextEffect] as? String),
            "fontSize": richNullable(font?.pointSize), "fontName": richNullable(font?.fontName),
            "fontTraits": richNullable(font?.fontDescriptor.symbolicTraits.rawValue),
            "underline": values[.underlineStyle] as? Int ?? 0, "strikethrough": values[.strikethroughStyle] as? Int ?? 0,
            "foregroundRGBA": color(values[.foregroundColor], traits: traits),
            "effectInkRGBA": color(values[NSAttributedString.Key("cmuxConversationEffectInk")], traits: traits),
            "minimumLineHeight": richNullable(paragraph?.minimumLineHeight), "maximumLineHeight": richNullable(paragraph?.maximumLineHeight)]
    }
    @MainActor private func state(_ text: UITextView, controller: UIViewController, store: ConversationStore,
        backend: RichComposerBackend) -> [String: Any] {
        let string = text.attributedText ?? NSAttributedString(string: "")
        var spans: [[String: Any]] = []
        string.enumerateAttributes(in: NSRange(location: 0, length: string.length)) { attrs, range, _ in
            spans.append(["location": range.location, "length": range.length, "attributes": attributes(attrs, traits: text.traitCollection)])
        }
        let marked = text.markedTextRange.map { range in
            ["location": text.offset(from: text.beginningOfDocument, to: range.start),
             "length": text.offset(from: range.start, to: range.end)]
        }
        let palette = text.inputView
        let controls = palette.map { root in descendants(root).compactMap { view -> [String: Any]? in
            guard let control = view as? UIControl else { return nil }
            let box = view.convert(view.bounds, to: root)
            return ["identifier": control.accessibilityIdentifier ?? "", "label": control.accessibilityLabel ?? "",
                "class": String(describing: type(of: control)), "enabled": control.isEnabled, "selected": control.isSelected,
                "accessibilitySelected": control.accessibilityTraits.contains(.selected),
                "frameInPalette": [box.minX, box.minY, box.width, box.height]]
        } } ?? []
        let layers = (text.layer.sublayers ?? []).filter { String(describing: type(of: $0)) == "ConversationTextEffectLayer" }
        return ["text": text.text ?? "", "utf16Count": string.length,
            "selection": ["location": text.selectedRange.location, "length": text.selectedRange.length],
            "marked": richNullable(marked), "runs": runs(ConversationRichText.runs(in: string)), "attributes": spans,
            "typing": attributes(text.typingAttributes, traits: text.traitCollection),
            "firstResponder": text.isFirstResponder, "editorIdentity": String(describing: ObjectIdentifier(text)),
            "delegateClass": text.delegate.map { String(describing: type(of: $0)) } ?? "",
            "canUndo": text.undoManager?.canUndo ?? false, "canRedo": text.undoManager?.canRedo ?? false,
            "undoGroupingLevel": text.undoManager?.groupingLevel ?? 0,
            "inputViewClass": richNullable(palette.map { String(describing: type(of: $0)) }),
            "inputViewIdentifier": richNullable(palette?.accessibilityIdentifier), "paletteInWindow": palette?.window != nil,
            "paletteBounds": richNullable(palette.map { [$0.bounds.width, $0.bounds.height] }), "controls": controls,
            "editorBounds": [text.bounds.width, text.bounds.height], "contentSize": [text.contentSize.width, text.contentSize.height],
            "contentOffset": [text.contentOffset.x, text.contentOffset.y],
            "effectLayerCount": layers.count, "effectGlyphLayers": layers.reduce(0) { $0 + ($1.sublayers?.count ?? 0) },
            "effectAnimatedLayers": layers.flatMap { $0.sublayers ?? [] }.filter { ($0.animationKeys() ?? []).contains("cmux.textEffect") }.count,
            "storeMessages": store.messages.map { ["text": $0.text, "runs": runs($0.textRuns), "sequence": richNullable($0.seq),
                "delivery": String(describing: $0.delivery)] },
            "backendDrafts": backend.drafts.map { ["text": $0.text, "runs": runs($0.textRuns)] },
            "unsupportedWrites": backend.unsupportedWrites, "viewing": store.isViewing,
            "actualCategory": UIApplication.shared.preferredContentSizeCategory.rawValue,
            "controllerCategory": controller.traitCollection.preferredContentSizeCategory.rawValue,
            "reduceMotion": UIAccessibility.isReduceMotionEnabled, "boldText": UIAccessibility.isBoldTextEnabled]
    }
    @MainActor private func trigger(_ identifier: String, root: UIView) throws {
        let control = try XCTUnwrap(descendants(root).first { $0.accessibilityIdentifier == identifier } as? UIControl)
        XCTAssertTrue(control.isEnabled); control.sendActions(for: .touchUpInside)
    }
    @MainActor private func perform(_ action: RichComposerAction, text: UITextView, controller: UIViewController,
        store: ConversationStore, backend: RichComposerBackend) async throws -> [String: Any] {
        var observed: [String: Any] = [:]
        switch action.kind {
        case "focus": XCTAssertTrue(text.becomeFirstResponder())
        case "resign": observed["resigned"] = text.resignFirstResponder()
        case "insert": text.insertText(try XCTUnwrap(action.text))
        case "deleteBackward": text.deleteBackward()
        case "select":
            let count = (text.text ?? "").utf16.count
            let range: NSRange
            switch action.selection {
            case "all": range = NSRange(location: 0, length: count)
            case "end": range = NSRange(location: count, length: 0)
            case "start": range = NSRange(location: 0, length: 0)
            default: range = NSRange(location: try XCTUnwrap(action.start), length: try XCTUnwrap(action.length))
            }
            guard range.location >= 0, range.length >= 0, NSMaxRange(range) <= count else { throw RichComposerFailure.invalidAction }
            text.selectedRange = range
        case "openEffects":
            let first = UIMenu(title: "Fixture before", children: [UIAction(title: "Fixture one") { _ in }])
            let last = UIMenu(title: "Fixture after", children: [UIAction(title: "Fixture two") { _ in }])
            let menu = try XCTUnwrap(text.delegate?.textView?(text, editMenuForTextIn: text.selectedRange, suggestedActions: [first, last]))
            XCTAssertEqual(menu.children.map(\.title), ["Fixture before", "Text Effects", "Fixture after"])
            let sourceAction = try XCTUnwrap(menu.children[1] as? UIAction)
            // Public API bridge into the actual menu action; no private selector or source edit.
            let bridge = UIButton(type: .system)
            bridge.addAction(sourceAction, for: .primaryActionTriggered)
            bridge.sendActions(for: .primaryActionTriggered)
            try await wait("source palette installed") { text.inputView?.accessibilityIdentifier == "conversation.textEffects.palette" }
            observed["menuChildren"] = menu.children.map(\.title)
            observed["invocation"] = "source-menu-UIAction-via-UIButton-primaryAction"
        case "toggle":
            let palette = try XCTUnwrap(text.inputView)
            try trigger("conversation.textEffects." + (try XCTUnwrap(action.target)), root: palette)
        case "closeEffects":
            try trigger("conversation.textEffects.close", root: try XCTUnwrap(text.inputView))
            try await wait("source palette closed") { text.inputView == nil }
        case "marked":
            text.setMarkedText(try XCTUnwrap(action.text), selectedRange: NSRange(location: try XCTUnwrap(action.start), length: try XCTUnwrap(action.length)))
        case "unmark": text.unmarkText()
        case "replace":
            guard let range = text.selectedTextRange else { throw RichComposerFailure.missingSelection }
            text.replace(range, withText: try XCTUnwrap(action.text))
        case "paste":
            UIPasteboard.general.string = try XCTUnwrap(action.text); text.paste(nil)
        case "undo":
            observed["performed"] = text.undoManager?.canUndo ?? false
            if text.undoManager?.canUndo == true { text.undoManager?.undo() }
        case "redo":
            observed["performed"] = text.undoManager?.canRedo ?? false
            if text.undoManager?.canRedo == true { text.undoManager?.redo() }
        case "send":
            let count = backend.drafts.count
            try trigger("conversation.composer.send", root: controller.view)
            try await wait("source send reaches fixture backend and settles") {
                backend.drafts.count == count + 1 && store.messages.contains {
                    backend.mode == "reject" ? $0.delivery?.isFailed == true : $0.seq == 1
                }
            }
        default: throw RichComposerFailure.invalidAction
        }
        try await settle(text)
        return observed
    }
    @MainActor func testCaptureOriginalRichComposerTransactions() async throws {
        let bundle = Bundle(for: Self.self)
        let input = try Data(contentsOf: XCTUnwrap(bundle.url(forResource: "RichComposerCases", withExtension: "json")))
        let fixture = try JSONDecoder().decode(RichComposerCases.self, from: input)
        XCTAssertEqual(fixture.schema, 1)
        let provenance = try JSONSerialization.jsonObject(with: Data(contentsOf: XCTUnwrap(bundle.url(forResource: "Provenance", withExtension: "json"))))
        let digest = SHA256.hash(data: input).map { String(format: "%02x", $0) }.joined()
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        try await wait("active host scene") { scene.activationState == .foregroundActive }
        for specification in fixture.cases {
            let previous = scene.keyWindow
            let backend = RichComposerBackend(mode: specification.backend)
            let store = ConversationStore(backend: backend)
            let controller = ConversationViewController(store: store, options: ConversationPresentationOptions(
                placeholder: "Message Chief", serviceTitle: "Chief", serviceSubtitle: "Runs your agents on your Mac",
                trailingSymbol: "stop.fill", trailingAccessibilityLabel: "Stop Chief", supportsReactions: false,
                supportsEditing: false, supportsUnsend: false, supportsAttachments: false))
            let window = UIWindow(windowScene: scene); window.frame = scene.coordinateSpace.bounds
            window.overrideUserInterfaceStyle = specification.style == "light" ? .light : .dark
            window.rootViewController = controller; window.makeKeyAndVisible()
            var records: [[String: Any]] = []
            defer {
                window.endEditing(true); store.stop(); window.isHidden = true; window.rootViewController = nil
                previous?.makeKeyAndVisible()
                let payload: [String: Any] = ["schema": 1, "referenceKind": "chiefRichComposer", "caseID": specification.id,
                    "fixtureSha256": digest, "provenance": provenance, "records": records,
                    "completed": records.count == specification.actions.count + 1, "backendMode": specification.backend,
                    "style": specification.style, "platform": UIDevice.current.systemName,
                    "runtimeVersion": UIDevice.current.systemVersion, "os": ProcessInfo.processInfo.operatingSystemVersionString,
                    "actualCategory": UIApplication.shared.preferredContentSizeCategory.rawValue,
                    "preferredLanguages": Locale.preferredLanguages, "supportsAttachments": false,
                    "privateSelectorsUsed": false, "sourceModified": false, "externalBackend": false,
                    "scope": "Actual pinned UIKit composer and Store, driven through public UITextInput APIs and source UIControl/menu actions. Authored in-memory rejecting/plain-ACK backend. No gesture/physical keyboard, XCUIScreen/rendered pixel, Chief backend, Android or full parity acceptance."]
                if let data = try? JSONSerialization.data(withJSONObject: payload, options: [.prettyPrinted, .sortedKeys]) {
                    let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.json")
                    attachment.name = "chief-rich-composer-" + specification.id + ".json"; attachment.lifetime = .keepAlways; add(attachment)
                }
            }
            try await wait("source composer mounted and history ready") { store.hasLoadedNewest && store.isViewing &&
                descendants(controller.view).contains { $0.accessibilityIdentifier == "conversation.composer.text" } }
            let text = try XCTUnwrap(descendants(controller.view).first { $0.accessibilityIdentifier == "conversation.composer.text" } as? UITextView)
            records.append(["step": -1, "action": ["kind": "initial"], "state": state(text, controller: controller, store: store, backend: backend)])
            for (index, action) in specification.actions.enumerated() {
                let before = CACurrentMediaTime()
                let observed = try await perform(action, text: text, controller: controller, store: store, backend: backend)
                let encoded = try JSONSerialization.jsonObject(with: JSONEncoder().encode(action))
                records.append(["step": index, "action": encoded, "observed": observed, "before": before, "after": CACurrentMediaTime(),
                    "state": state(text, controller: controller, store: store, backend: backend)])
            }
        }
    }
}
