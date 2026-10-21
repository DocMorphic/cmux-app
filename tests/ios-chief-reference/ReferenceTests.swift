import XCTest
import UIKit
import CryptoKit
import CmuxConversationCore
import CmuxConversationUI

private struct ReferenceScenes: Decodable { var schema: Int; var scenes: [ReferenceScene] }
private struct ReferenceScene: Decodable {
    var id: String; var style: String; var category: String; var rows: [ReferenceRow]; var failedSend: String?
}

private struct ReferenceInteractions: Decodable { var schema: Int; var scenes: [ReferenceInteraction] }
private struct ReferenceInteraction: Decodable {
    var id: String; var sourceScene: String; var style: String; var category: String
    var orientation: String; var mode: String; var draft: String?
}

extension CmuxChiefReferenceTests {
    @MainActor func testCaptureStandardChiefInteractionReferences() async throws {
        try await captureInteractions(sceneCategory: "large", systemCategory: .large)
    }

    @MainActor func testCaptureAccessibilityChiefInteractionReferences() async throws {
        try await captureInteractions(sceneCategory: "accessibilityMedium", systemCategory: .accessibilityMedium)
    }

    @MainActor private func views(in view: UIView) -> [UIView] {
        [view] + view.subviews.flatMap { views(in: $0) }
    }

    @MainActor private func frame(_ view: UIView, in window: UIWindow) -> [CGFloat] {
        let r = view.convert(view.bounds, to: window)
        return [r.minX, r.minY, r.width, r.height]
    }

    @MainActor private func controls(in root: UIView, window: UIWindow) -> [[String: Any]] {
        views(in: root).compactMap { view in
            guard let control = view as? UIControl, !view.isHidden, view.alpha > 0.01,
                  !view.convert(view.bounds, to: window).intersection(window.bounds).isEmpty else { return nil }
            var record: [String: Any] = ["class": String(describing: type(of: control)),
                "identifier": control.accessibilityIdentifier ?? "", "label": control.accessibilityLabel ?? "",
                "frame": frame(control, in: window), "enabled": control.isEnabled]
            if let toggle = control as? UISwitch { record["on"] = toggle.isOn }
            return record
        }
    }

    @MainActor private func images(in root: UIView, window: UIWindow) -> [[String: Any]] {
        views(in: root).compactMap { view in
            guard let imageView = view as? UIImageView, let image = imageView.image,
                  !view.isHidden, view.alpha > 0.01,
                  !view.convert(view.bounds, to: window).intersection(window.bounds).isEmpty else { return nil }
            return ["class": String(describing: type(of: imageView)), "frame": frame(imageView, in: window),
                "imageSize": [image.size.width, image.size.height], "imageScale": image.scale,
                "symbol": image.isSymbolImage]
        }
    }

    @MainActor private func orient(_ scene: UIWindowScene, controller: UIViewController, landscape: Bool) async throws {
        controller.setNeedsUpdateOfSupportedInterfaceOrientations()
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: landscape ? .landscapeRight : .portrait))
        try await wait("actual reference orientation") {
            scene.interfaceOrientation.isLandscape == landscape &&
                (scene.coordinateSpace.bounds.width > scene.coordinateSpace.bounds.height) == landscape
        }
    }

    @MainActor private func captureInteractions(sceneCategory: String, systemCategory: UIContentSizeCategory) async throws {
        let input = try resource("Scenes.json"), interactionInput = try resource("Interactions.json")
        let scenes = try JSONDecoder().decode(ReferenceScenes.self, from: input)
        let interactions = try JSONDecoder().decode(ReferenceInteractions.self, from: interactionInput)
        XCTAssertEqual(interactions.schema, 1)
        let provenance = try JSONSerialization.jsonObject(with: resource("Provenance.json"))
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        try await wait("actual application content-size category") {
            UIApplication.shared.preferredContentSizeCategory == systemCategory && scene.activationState == .foregroundActive
        }
        UIView.setAnimationsEnabled(false)
        defer { UIView.setAnimationsEnabled(true) }
        for specification in interactions.scenes where specification.category == sceneCategory {
            let base = try XCTUnwrap(scenes.scenes.first { $0.id == specification.sourceScene })
            let date = Date()
            let backend = ReferenceBackend(base, date: date)
            let store = ConversationStore(backend: backend)
            let controller = ConversationViewController(store: store, options: ConversationPresentationOptions(
                placeholder: "Message Chief", serviceTitle: "Chief", serviceSubtitle: "Runs your agents on your Mac",
                avatarImage: UIImage(data: try resource("ChiefAvatar.png")), trailingSymbol: "stop.fill",
                trailingAccessibilityLabel: "Stop Chief", supportsReactions: false, supportsEditing: false,
                supportsUnsend: false, supportsAttachments: false))
            let window = UIWindow(windowScene: scene)
            window.frame = scene.coordinateSpace.bounds
            window.overrideUserInterfaceStyle = specification.style == "light" ? .light : .dark
            window.rootViewController = controller; window.makeKeyAndVisible()
            defer { store.stop(); window.isHidden = true; window.rootViewController = nil }
            try await orient(scene, controller: controller, landscape: specification.orientation == "landscape")
            try await wait(specification.id + ": history and appearance") {
                window.layoutIfNeeded()
                return store.hasLoadedNewest && store.messages.count == base.rows.count && store.isViewing
            }
            var behavior: [String: Any] = ["viewingBefore": store.isViewing]
            var content: UIView = controller.view
            var presented: UIViewController?
            var editor: UITextView?
            if specification.mode == "details" {
                let text = try XCTUnwrap(views(in: controller.view).first { $0.accessibilityIdentifier == "conversation.composer.text" } as? UITextView)
                let draft = try XCTUnwrap(specification.draft)
                text.text = draft; text.delegate?.textViewDidChange?(text)
                XCTAssertTrue(text.becomeFirstResponder()); XCTAssertEqual(text.text, draft)
                // The source VC's composer callback updates typing, not store.draft.
                behavior["storeDraftMatchesEditorBefore"] = store.draft == draft
                let name = try XCTUnwrap(views(in: controller.view).first { $0.accessibilityIdentifier == "conversation.header.name" } as? UIControl)
                name.sendActions(for: .touchUpInside)
                try await wait(specification.id + ": presented source details") {
                    guard let modal = controller.presentedViewController else { return false }
                    return modal.viewIfLoaded?.window === window && !modal.isBeingPresented && modal.transitionCoordinator == nil
                }
                let modal = try XCTUnwrap(controller.presentedViewController)
                content = modal.view; presented = modal; editor = text
                behavior["viewingWhileDetails"] = store.isViewing
                behavior["editorFocusedWhileDetails"] = text.isFirstResponder
                XCTAssertFalse(text.isFirstResponder)
                let detailViews = views(in: content)
                let alerts = try XCTUnwrap(detailViews.first { $0.accessibilityIdentifier == "conversation.details.hideAlerts" } as? UISwitch)
                let receipts = try XCTUnwrap(detailViews.first { $0.accessibilityIdentifier == "conversation.details.readReceipts" } as? UISwitch)
                XCTAssertFalse(alerts.isOn); XCTAssertTrue(receipts.isOn)
                alerts.setOn(true, animated: false); alerts.sendActions(for: .valueChanged)
                behavior["alertsOptimistic"] = store.info?.listState.muted == true
                XCTAssertTrue(store.info?.listState.muted == true)
                try await wait("actual unsupported alert rollback") { store.info?.listState.muted == false && !alerts.isOn }
                receipts.setOn(false, animated: false); receipts.sendActions(for: .valueChanged)
                behavior["receiptsOptimistic"] = store.info?.listState.sendReadReceipts == false
                XCTAssertTrue(store.info?.listState.sendReadReceipts == false)
                try await wait("actual unsupported receipts rollback") { store.info?.listState.sendReadReceipts == true && receipts.isOn }
                behavior["alertsAfterRollback"] = alerts.isOn; behavior["receiptsAfterRollback"] = receipts.isOn
            } else {
                XCTAssertEqual(controller.traitCollection.verticalSizeClass, .compact)
            }
            try await Task.sleep(for: .milliseconds(500))
            window.layoutIfNeeded()
            let visibleLabels = labels(in: window, window: window)
            let visibleControls = controls(in: content, window: window)
            let visibleImages = images(in: content, window: window)
            let capturedSafeArea = [window.safeAreaInsets.top, window.safeAreaInsets.bottom, window.safeAreaInsets.left, window.safeAreaInsets.right]
            let capturedVerticalSizeClass = controller.traitCollection.verticalSizeClass == .compact ? "compact" : "regular"
            if specification.mode == "details" {
                let title = try XCTUnwrap(visibleLabels.first { ($0["accessibilityIdentifier"] as? String) == "conversation.details.title" })
                XCTAssertGreaterThanOrEqual(title["fontSize"] as? CGFloat ?? 0, sceneCategory == "large" ? 28 : 38)
                for id in ["call", "video", "mail"] {
                    let button = try XCTUnwrap(visibleControls.first { ($0["identifier"] as? String) == "conversation.details.\(id)" })
                    XCTAssertEqual(button["enabled"] as? Bool, false)
                }
            }
            let format = UIGraphicsImageRendererFormat(); format.scale = scene.screen.scale
            let image = UIGraphicsImageRenderer(bounds: window.bounds, format: format).image { _ in
                XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
            }
            let png = try XCTUnwrap(image.pngData())
            let screenshot = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
            screenshot.name = specification.id + ".png"; screenshot.lifetime = .keepAlways; add(screenshot)
            if let presented {
                let navigation = try XCTUnwrap(presented as? UINavigationController)
                let back = try XCTUnwrap(navigation.topViewController?.navigationItem.leftBarButtonItem)
                XCTAssertEqual(back.accessibilityIdentifier, "conversation.details.back")
                // UIBarButtonItem need not expose a UIControl in UIKit's private view tree.
                // Dispatch the item's actual public action, never a fabricated selector/dismissal.
                let activated: Bool
                if let action = back.action {
                    behavior["detailsCloseActivation"] = "barButtonTargetAction"
                    activated = UIApplication.shared.sendAction(action, to: back.target, from: back, for: nil)
                } else {
                    behavior["detailsCloseActivation"] = "barButtonAccessibilityActivate"
                    activated = back.accessibilityActivate()
                }
                XCTAssertTrue(activated, "The actual source Back item must handle its action")
                try await wait("actual source details back action") { controller.presentedViewController == nil }
                try await wait("actual source viewing after details return") { store.isViewing }
                XCTAssertEqual(editor?.text, specification.draft)
                XCTAssertFalse(editor?.isFirstResponder ?? true)
                behavior["viewingAfterReturn"] = store.isViewing
                behavior["editorFocusedAfterReturn"] = editor?.isFirstResponder ?? true
                behavior["draftPreserved"] = editor?.text == specification.draft
            }
            let metadata: [String: Any] = ["scene": specification.id, "mode": specification.mode,
                "provenance": provenance, "sourceScene": specification.sourceScene,
                "fixtureSha256": SHA256.hash(data: input).map { String(format: "%02x", $0) }.joined(),
                "interactionFixtureSha256": SHA256.hash(data: interactionInput).map { String(format: "%02x", $0) }.joined(),
                "imageSha256": SHA256.hash(data: png).map { String(format: "%02x", $0) }.joined(),
                "style": specification.style, "category": specification.category, "date": ISO8601DateFormatter().string(from: date),
                "applicationContentSizeCategory": UIApplication.shared.preferredContentSizeCategory.rawValue,
                "applicationCategoryMatchesScene": UIApplication.shared.preferredContentSizeCategory == systemCategory,
                "os": ProcessInfo.processInfo.operatingSystemVersionString, "orientation": specification.orientation,
                "viewport": [image.size.width, image.size.height], "scale": format.scale,
                "safeArea": capturedSafeArea, "verticalSizeClass": capturedVerticalSizeClass,
                "labels": visibleLabels, "controls": visibleControls, "imageViews": visibleImages,
                "storeMessages": store.messages.count, "behavior": behavior,
                "scope": "Actual unmodified pinned UIKit controls, details rollback/focus/viewing observations and compact header; offline read-only backend. No real account/transport, physical device or animation acceptance."]
            for (suffix, data, type) in [("json", try JSONSerialization.data(withJSONObject: metadata, options: [.prettyPrinted, .sortedKeys]), "public.json")] {
                let attachment = XCTAttachment(data: data, uniformTypeIdentifier: type)
                attachment.name = specification.id + "." + suffix; attachment.lifetime = .keepAlways; add(attachment)
            }
            // Do not leave a persistent simulator orientation for the next invocation.
            try await orient(scene, controller: controller, landscape: false)
        }
    }
}
private struct ReferenceRow: Decodable {
    var id: String; var text: String; var outgoing: Bool; var seconds: Int?; var replyTo: String?
    var pollQuestion: String?; var pollChoices: [String]?
}
private enum ReferenceFailure: Error { case readOnly, renderTimeout(String), missingResource(String), missingWindowScene }

private final class ReferenceBackend: ConversationBackend {
    let messages: [ConversationMessage]
    let info = ConversationInfo(id: "chief-main", title: "Chief", kind: .direct, participants: [
        ConversationParticipant(id: "chief", name: "Chief", initials: "C", colorHex: "#0284FE", isMe: false),
        ConversationParticipant(id: "me", name: "You", initials: "Y", colorHex: "#0284FE", isMe: true)
    ])
    init(_ scene: ReferenceScene, date: Date) {
        messages = scene.rows.enumerated().map { index, row in
            ConversationMessage(id: row.id, seq: index + 1, clientMessageID: nil,
                senderID: row.outgoing ? "me" : "chief", sentAt: date.addingTimeInterval(Double(row.seconds ?? (-300 + index * 30))),
                text: row.text, replyToID: row.replyTo, delivery: row.outgoing ? .sent : nil,
                poll: row.pollQuestion.map { question in ConversationPoll(question: question,
                    options: (row.pollChoices ?? []).map { ConversationPollOption(id: $0, text: $0) }) })
        }
    }
    func events() -> AsyncStream<ConversationBackendEvent> {
        AsyncStream { continuation in
            continuation.yield(.connected(info: info, meID: "me", lagged: false))
            continuation.yield(.readState(ConversationReadState(lastReadSeq: messages.count, unreadCount: 0, headSeq: messages.count)))
            continuation.finish()
        }
    }
    func history(beforeSeq: Int?, limit: Int) async throws -> ConversationHistoryPage {
        let eligible = messages.filter { beforeSeq == nil || ($0.seq ?? 0) < beforeSeq! }
        return ConversationHistoryPage(messages: Array(eligible.suffix(limit)), hasMore: eligible.count > limit)
    }
    func send(_ draft: ConversationOutgoingDraft) async throws -> ConversationMessage { throw ReferenceFailure.readOnly }
    func react(messageID: String, reaction: ConversationReaction?) async throws -> ConversationMessage { throw ReferenceFailure.readOnly }
    func edit(messageID: String, text: String) async throws -> ConversationMessage { throw ReferenceFailure.readOnly }
    func unsend(messageID: String) async throws -> ConversationMessage { throw ReferenceFailure.readOnly }
    func setTyping(_ isTyping: Bool) async {}
    func markRead(upToSeq: Int) async {}
    func uploadImage(_ data: Data, mimeType: String) async throws -> ConversationAttachment { throw ReferenceFailure.readOnly }
    func close() {}
}

final class CmuxChiefReferenceTests: XCTestCase {
    private func resource(_ name: String) throws -> Data {
        guard let url = Bundle(for: Self.self).url(forResource: name, withExtension: nil) else {
            throw ReferenceFailure.missingResource(name)
        }
        return try Data(contentsOf: url)
    }

    @MainActor private func labels(in view: UIView, window: UIWindow, clip inheritedClip: CGRect? = nil,
                                   opacity inheritedOpacity: CGFloat = 1) -> [[String: Any]] {
        let opacity = inheritedOpacity * view.alpha
        guard !view.isHidden, opacity > 0.01 else { return [] }
        let available = inheritedClip ?? window.bounds
        let clip = view.clipsToBounds ? available.intersection(view.convert(view.bounds, to: window)) : available
        guard !clip.isNull, clip.width > 0, clip.height > 0 else { return [] }
        var result: [[String: Any]] = []
        if let label = view as? UILabel, let text = label.text, !text.isEmpty {
            let frame = label.convert(label.bounds, to: window)
            let visible = frame.intersection(clip)
            if !visible.isNull, visible.width > 0, visible.height > 0 {
                result.append(["text": text, "class": String(describing: type(of: label)),
                    "frame": [frame.minX, frame.minY, frame.width, frame.height],
                    "visibleFrame": [visible.minX, visible.minY, visible.width, visible.height],
                    "fontSize": label.font.pointSize, "fontLineHeight": label.font.lineHeight,
                    "accessibilityIdentifier": label.accessibilityIdentifier ?? ""])
            }
        }
        for child in view.subviews { result += labels(in: child, window: window, clip: clip, opacity: opacity) }
        return result
    }

    @MainActor private func wait(_ name: String, until ready: () -> Bool) async throws {
        let deadline = ContinuousClock.now + .seconds(15)
        while !ready() {
            if ContinuousClock.now >= deadline { throw ReferenceFailure.renderTimeout(name) }
            try await Task.sleep(for: .milliseconds(20))
        }
    }

    @MainActor func testCaptureStandardChiefConversationScenes() async throws {
        try await capture(sceneCategory: "large", systemCategory: .large)
    }

    @MainActor func testCaptureAccessibilityChiefConversationScene() async throws {
        try await capture(sceneCategory: "accessibilityMedium", systemCategory: .accessibilityMedium)
    }

    @MainActor private func capture(sceneCategory: String, systemCategory: UIContentSizeCategory) async throws {
        let input = try resource("Scenes.json")
        let scenes = try JSONDecoder().decode(ReferenceScenes.self, from: input)
        XCTAssertEqual(scenes.schema, 1)
        let provenance = try JSONSerialization.jsonObject(with: resource("Provenance.json"))
        guard let windowScene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else {
            throw ReferenceFailure.missingWindowScene
        }
        try await wait("actual application content-size category") {
            UIApplication.shared.preferredContentSizeCategory == systemCategory
        }
        UIView.setAnimationsEnabled(false)
        defer { UIView.setAnimationsEnabled(true) }
        for scene in scenes.scenes where scene.category == sceneCategory {
            let date = Date()
            let backend = ReferenceBackend(scene, date: date)
            let store = ConversationStore(backend: backend, makeClientMessageID: { "reference-failed-send" })
            store.setViewing(true)
            let avatar = UIImage(data: try resource("ChiefAvatar.png"))
            let controller = ConversationViewController(store: store, options: ConversationPresentationOptions(
                placeholder: "Message Chief", serviceTitle: "Chief", serviceSubtitle: "Runs your agents on your Mac",
                avatarImage: avatar, trailingSymbol: "stop.fill", trailingAccessibilityLabel: "Stop Chief",
                supportsReactions: false, supportsEditing: false, supportsUnsend: false, supportsAttachments: false))
            let window = UIWindow(windowScene: windowScene)
            window.frame = windowScene.coordinateSpace.bounds
            window.overrideUserInterfaceStyle = scene.style == "light" ? .light : .dark
            window.rootViewController = controller
            window.makeKeyAndVisible()
            defer { store.stop(); window.isHidden = true; window.rootViewController = nil }
            try await wait(scene.id + ": history") { store.hasLoadedNewest && store.messages.count == scene.rows.count }
            if let draft = scene.failedSend {
                XCTAssertNotNil(store.send(text: draft))
                try await wait(scene.id + ": failed receipt") { store.messages.last?.delivery?.isFailed == true }
            }
            let expected = scene.failedSend ?? scene.rows.last!.text
            try await wait(scene.id + ": visible last row") {
                window.layoutIfNeeded()
                return labels(in: window, window: window).contains { ($0["text"] as? String)?.contains(expected) == true }
            }
            // Capture only: allow explicit Core Animation badge/footer animations
            // to settle after their observable row is present.
            try await Task.sleep(for: .milliseconds(500))
            window.layoutIfNeeded()
            if scene.category == "accessibilityMedium" {
                let body = labels(in: window, window: window).filter { ($0["class"] as? String) == "ConversationEffectLabel" }
                XCTAssertFalse(body.isEmpty)
                XCTAssertTrue(body.allSatisfy { ($0["fontSize"] as? CGFloat ?? 0) >= 28 }, "The reference must actually use accessibility body type")
            }
            let format = UIGraphicsImageRendererFormat()
            format.scale = windowScene.screen.scale
            let image = UIGraphicsImageRenderer(bounds: window.bounds, format: format).image { _ in
                XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
            }
            let png = try XCTUnwrap(image.pngData())
            let screenshot = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
            screenshot.name = scene.id + ".png"; screenshot.lifetime = .keepAlways; add(screenshot)
            let metadata: [String: Any] = ["scene": scene.id, "provenance": provenance,
                "fixtureSha256": SHA256.hash(data: input).map { String(format: "%02x", $0) }.joined(),
                "imageSha256": SHA256.hash(data: png).map { String(format: "%02x", $0) }.joined(),
                "style": scene.style, "category": scene.category, "date": ISO8601DateFormatter().string(from: date),
                "applicationContentSizeCategory": UIApplication.shared.preferredContentSizeCategory.rawValue,
                "applicationCategoryMatchesScene": UIApplication.shared.preferredContentSizeCategory == systemCategory,
                "os": ProcessInfo.processInfo.operatingSystemVersionString,
                "viewport": [window.bounds.width, window.bounds.height], "scale": format.scale,
                "safeArea": [window.safeAreaInsets.top, window.safeAreaInsets.bottom],
                "labels": labels(in: window, window: window), "storeMessages": store.messages.count,
                "scope": "Actual pinned UIKit conversation renderer and Chief presentation options with an immutable offline backend; window rendering excludes the system status bar, real account/transport, keyboard and animation acceptance"]
            let attachment = XCTAttachment(data: try JSONSerialization.data(withJSONObject: metadata, options: [.prettyPrinted, .sortedKeys]), uniformTypeIdentifier: "public.json")
            attachment.name = scene.id + ".json"; attachment.lifetime = .keepAlways; add(attachment)
        }
    }
}
