import XCTest
import UIKit
import QuartzCore
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
    var rootID: String?; var quoteMessageID: String?
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

    @MainActor private func effectiveVisibleFrame(_ view: UIView, window: UIWindow) -> CGRect? {
        guard view.window === window else { return nil }
        var opacity: CGFloat = 1
        var visible = view.convert(view.bounds, to: window).intersection(window.bounds)
        var ancestor: UIView? = view
        while let current = ancestor {
            opacity *= current.alpha
            guard !current.isHidden, opacity > 0.01 else { return nil }
            if current.clipsToBounds { visible = visible.intersection(current.convert(current.bounds, to: window)) }
            ancestor = current.superview
        }
        return visible.isNull || visible.isEmpty ? nil : visible
    }

    @MainActor private func accessibleElements(in root: UIView, window: UIWindow) -> [UIAccessibilityElement] {
        views(in: root).filter { effectiveVisibleFrame($0, window: window) != nil }.flatMap { view in
            (view.accessibilityElements ?? []).compactMap { $0 as? UIAccessibilityElement }
        }
    }

    @MainActor private func verifyAncestorVisibility(window: UIWindow) throws {
        // Render-observation guard: a visible child inside a hidden/transparent or
        // clipped-away ancestor must not count as a visible source control/image.
        let parent = UIView(frame: CGRect(x: 10, y: 10, width: 80, height: 80))
        let button = UIButton(frame: CGRect(x: 0, y: 0, width: 40, height: 40))
        let picture = UIImageView(image: try XCTUnwrap(UIImage(systemName: "xmark")))
        picture.frame = button.frame; parent.addSubview(button); parent.addSubview(picture); window.addSubview(parent)
        defer { parent.removeFromSuperview() }
        XCTAssertEqual(controls(in: parent, window: window).count, 1)
        XCTAssertEqual(images(in: parent, window: window).count, 1)
        parent.alpha = 0
        XCTAssertTrue(controls(in: parent, window: window).isEmpty); XCTAssertTrue(images(in: parent, window: window).isEmpty)
        parent.alpha = 1; parent.isHidden = true
        XCTAssertTrue(controls(in: parent, window: window).isEmpty); XCTAssertTrue(images(in: parent, window: window).isEmpty)
        parent.isHidden = false; parent.clipsToBounds = true
        button.frame.origin.x = 100; picture.frame.origin.x = 100
        XCTAssertTrue(controls(in: parent, window: window).isEmpty); XCTAssertTrue(images(in: parent, window: window).isEmpty)
    }

    @MainActor private func controls(in root: UIView, window: UIWindow) -> [[String: Any]] {
        views(in: root).compactMap { view in
            guard let control = view as? UIControl,
                  let visible = effectiveVisibleFrame(view, window: window) else { return nil }
            var record: [String: Any] = ["class": String(describing: type(of: control)),
                "identifier": control.accessibilityIdentifier ?? "", "label": control.accessibilityLabel ?? "",
                "frame": frame(control, in: window), "enabled": control.isEnabled,
                "visibleFrame": [visible.minX, visible.minY, visible.width, visible.height]]
            if let toggle = control as? UISwitch { record["on"] = toggle.isOn }
            return record
        }
    }

    @MainActor private func images(in root: UIView, window: UIWindow) -> [[String: Any]] {
        views(in: root).compactMap { view in
            guard let imageView = view as? UIImageView, let image = imageView.image,
                  let visible = effectiveVisibleFrame(view, window: window) else { return nil }
            return ["class": String(describing: type(of: imageView)), "frame": frame(imageView, in: window),
                "imageSize": [image.size.width, image.size.height], "imageScale": image.scale,
                "symbol": image.isSymbolImage, "visibleFrame": [visible.minX, visible.minY, visible.width, visible.height]]
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

    @MainActor private func captionMetrics(scene: UIWindowScene, currentName: String) -> [String: Any] {
        let categories: [(String, UIContentSizeCategory)] = [
            ("extraSmall", .extraSmall), ("small", .small), ("medium", .medium), ("large", .large),
            ("extraLarge", .extraLarge), ("extraExtraLarge", .extraExtraLarge), ("extraExtraExtraLarge", .extraExtraExtraLarge),
            ("accessibilityMedium", .accessibilityMedium), ("accessibilityLarge", .accessibilityLarge),
            ("accessibilityExtraLarge", .accessibilityExtraLarge), ("accessibilityExtraExtraLarge", .accessibilityExtraExtraLarge),
            ("accessibilityExtraExtraExtraLarge", .accessibilityExtraExtraExtraLarge)
        ]
        let records = categories.map { name, category -> [String: Any] in
            let traits = UITraitCollection(traitsFrom: [scene.traitCollection, UITraitCollection(preferredContentSizeCategory: category)])
            let caption = UIFont.preferredFont(forTextStyle: .caption2, compatibleWith: traits)
            let regular = UIFont.systemFont(ofSize: caption.pointSize, weight: .regular)
            let descriptor = UIFontDescriptor.preferredFontDescriptor(withTextStyle: .body, compatibleWith: traits)
            let bubble = UIFont(descriptor: descriptor.withSymbolicTraits(.traitTightLeading) ?? descriptor, size: 0)
            return ["name": name, "category": category.rawValue,
                "bodyPointSize": bubble.pointSize,
                "scaledBodyValue": UIFontMetrics(forTextStyle: .body).scaledValue(for: 17, compatibleWith: traits),
                "captionPointSize": caption.pointSize, "captionLineHeight": caption.lineHeight,
                "captionLeading": caption.leading, "neutralLineHeight": regular.lineHeight,
                "regularAscender": regular.ascender, "regularDescender": regular.descender]
        }
        // Compare explicit-compatible metrics with the actual application category.
        // The source reads this public preferred font without a trait override.
        let actual = UIFont.preferredFont(forTextStyle: .caption2)
        let bodyDescriptor = UIFontDescriptor.preferredFontDescriptor(withTextStyle: .body)
        let actualBody = UIFont(descriptor: bodyDescriptor.withSymbolicTraits(.traitTightLeading) ?? bodyDescriptor, size: 0)
        return ["schema": 1, "records": records, "currentName": currentName,
            "actualCategory": UIApplication.shared.preferredContentSizeCategory.rawValue,
            "actualCaptionPointSize": actual.pointSize, "actualCaptionLineHeight": actual.lineHeight,
            "actualBodyPointSize": actualBody.pointSize,
            "actualCaptionLeading": actual.leading, "boldText": UIAccessibility.isBoldTextEnabled,
            "scope": "Public UIKit compatible-category font metrics for the source caption2 expressions, cross-checked against the actual current application category. Not source-package modification or Android font artwork."]
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
        let capturedCaptionMetrics = captionMetrics(scene: scene, currentName: sceneCategory)
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
            try verifyAncestorVisibility(window: window)
            var behavior: [String: Any] = ["viewingBefore": store.isViewing, "ancestorVisibilityGuardPassed": true]
            var content: UIView = controller.view
            var presented: UIViewController?
            var editor: UITextView?
            var thread: UIView?
            var threadObservations: [String: Any] = [:]
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
            } else if specification.mode == "thread" {
                let rootID = try XCTUnwrap(specification.rootID)
                let quoteID = try XCTUnwrap(specification.quoteMessageID)
                let rootRow = try XCTUnwrap(base.rows.first { $0.id == rootID })
                let quoteRow = try XCTUnwrap(base.rows.first { $0.id == quoteID })
                XCTAssertEqual(quoteRow.replyTo, rootID)
                let text = try XCTUnwrap(views(in: controller.view).first { $0.accessibilityIdentifier == "conversation.composer.text" } as? UITextView)
                let draft = try XCTUnwrap(specification.draft)
                text.text = draft; text.delegate?.textViewDidChange?(text)
                XCTAssertTrue(text.becomeFirstResponder()); XCTAssertEqual(text.text, draft)
                // Locate the quote on the real mounted cell's contentView, using
                // its neighboring bubble identifier. Do not invoke private APIs.
                try await wait(specification.id + ": mounted quoted source row") {
                    window.layoutIfNeeded()
                    return accessibleElements(in: controller.view, window: window).contains {
                        $0.accessibilityIdentifier == "conversation.bubble." + quoteID
                    }
                }
                let containers = views(in: controller.view).filter { view in
                    effectiveVisibleFrame(view, window: window) != nil && (view.accessibilityElements ?? []).contains {
                        ($0 as? UIAccessibilityElement)?.accessibilityIdentifier == "conversation.bubble." + quoteID
                    }
                }
                let container = try XCTUnwrap(containers.count == 1 ? containers.first : nil, "Require a unique real quote-owning source cell")
                let quotes = (container.accessibilityElements ?? []).compactMap { $0 as? UIAccessibilityElement }.filter {
                    $0.accessibilityTraits.contains(.button) && $0.accessibilityLabel == rootRow.text
                }
                let quote = try XCTUnwrap(quotes.count == 1 ? quotes.first : nil, "Require the unique source quote accessibility element")
                behavior["quoteContainerCount"] = containers.count; behavior["quoteElementCount"] = quotes.count
                behavior["threadOpenActivation"] = "sourceQuoteAccessibilityActivate"
                XCTAssertTrue(quote.accessibilityActivate())
                try await wait(specification.id + ": actual source reply overlay") {
                    window.layoutIfNeeded()
                    return views(in: controller.view).contains { $0.accessibilityIdentifier == "conversation.replyThread" }
                }
                let overlay = try XCTUnwrap(views(in: controller.view).first { $0.accessibilityIdentifier == "conversation.replyThread" })
                let collections = views(in: controller.view).compactMap { $0 as? UICollectionView }
                let collection = try XCTUnwrap(collections.count == 1 ? collections.first : nil)
                try await wait(specification.id + ": reply row motion and close chrome") {
                    window.layoutIfNeeded()
                    let scroller = overlay.subviews.compactMap { $0 as? UIScrollView }.first
                    return scroller?.subviews.allSatisfy { $0.transform.isIdentity } == true &&
                        text.isFirstResponder && controls(in: controller.view, window: window).contains {
                            ($0["identifier"] as? String) == "conversation.header.trailing" && ($0["label"] as? String) == "Close"
                        }
                }
                let expectedIDs = base.rows.filter { $0.id == rootID || $0.replyTo == rootID }.map { "conversation.bubble." + $0.id }.sorted()
                let actualIDs = accessibleElements(in: overlay, window: window).compactMap { $0.accessibilityIdentifier }
                    .filter { $0.hasPrefix("conversation.bubble.") }.sorted()
                XCTAssertEqual(actualIDs, expectedIDs)
                XCTAssertTrue(collection.accessibilityElementsHidden)
                XCTAssertEqual(text.text, draft); XCTAssertTrue(text.isFirstResponder)
                behavior["viewingWhileThread"] = store.isViewing
                behavior["editorFocusedWhileThread"] = text.isFirstResponder
                behavior["draftPreservedWhileThread"] = text.text == draft
                behavior["backingAccessibilityHidden"] = collection.accessibilityElementsHidden
                threadObservations = ["rootID": rootID, "quoteMessageID": quoteID, "bubbleIdentifiers": actualIDs]
                thread = overlay; editor = text
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
            if let thread {
                let scroller = try XCTUnwrap(thread.subviews.compactMap { $0 as? UIScrollView }.first)
                let boxes = controls(in: controller.view, window: window)
                XCTAssertFalse(boxes.contains { ($0["label"] as? String) == "Back" })
                behavior["backHiddenWhileThread"] = true
                threadObservations["labels"] = labels(in: thread, window: window)
                threadObservations["contentFrame"] = frame(scroller, in: window)
                threadObservations["contentSize"] = [scroller.contentSize.width, scroller.contentSize.height]
                threadObservations["contentOffset"] = [scroller.contentOffset.x, scroller.contentOffset.y]
                threadObservations["rowFrames"] = scroller.subviews.filter { !$0.isHidden && $0.alpha > 0.01 }.map {
                    ["class": String(describing: type(of: $0)), "frame": frame($0, in: window), "identityTransform": $0.transform.isIdentity] as [String: Any]
                }
                threadObservations["editorFrame"] = frame(try XCTUnwrap(editor), in: window)
            }
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
                let bar = navigation.navigationBar
                let barViews = views(in: bar)
                let diagnostic: [String: Any] = ["diagnosticScene": specification.id,
                    "navigationViews": barViews.map { view in ["class": String(describing: type(of: view)),
                        "identifier": view.accessibilityIdentifier ?? "", "label": view.accessibilityLabel ?? "",
                        "frame": frame(view, in: window), "isControl": view is UIControl,
                        "controlEvents": (view as? UIControl)?.allControlEvents.rawValue ?? 0] }]
                let diagnosticAttachment = XCTAttachment(data: try JSONSerialization.data(withJSONObject: diagnostic, options: [.prettyPrinted, .sortedKeys]), uniformTypeIdentifier: "public.json")
                diagnosticAttachment.name = specification.id + "-navigation-diagnostic.json"; diagnosticAttachment.lifetime = .keepAlways; add(diagnosticAttachment)
                // The source installs only its Back item in this bar. UIKit's rendered
                // primary control has no public label/id, so prove the item/bar shape and
                // require exactly one registered primary control instead of guessing by class.
                let item = try XCTUnwrap(bar.topItem)
                XCTAssertTrue(item === navigation.topViewController?.navigationItem)
                XCTAssertTrue(item.leftBarButtonItem === back)
                XCTAssertEqual(item.leftBarButtonItems?.count, 1)
                XCTAssertTrue(item.rightBarButtonItems?.isEmpty ?? true)
                let primary = barViews.compactMap { $0 as? UIControl }.filter {
                    $0.isEnabled && $0.allControlEvents.contains(.primaryActionTriggered)
                }
                let control = try XCTUnwrap(primary.count == 1 ? primary.first : nil,
                    "Exactly one registered primary control must belong to the source Back-only navigation bar")
                behavior["navigationPrimaryControlCount"] = primary.count
                behavior["navigationLeftItemCount"] = item.leftBarButtonItems?.count ?? 0
                behavior["navigationRightItemCount"] = item.rightBarButtonItems?.count ?? 0
                behavior["navigationItemIdentifier"] = back.accessibilityIdentifier ?? ""
                behavior["detailsCloseActivation"] = "uniqueNavigationPrimaryAction"
                control.sendActions(for: .primaryActionTriggered)
                try await wait("actual source details back action") { controller.presentedViewController == nil }
                try await wait("actual source viewing after details return") { store.isViewing }
                XCTAssertEqual(editor?.text, specification.draft)
                XCTAssertFalse(editor?.isFirstResponder ?? true)
                behavior["viewingAfterReturn"] = store.isViewing
                behavior["editorFocusedAfterReturn"] = editor?.isFirstResponder ?? true
                behavior["draftPreserved"] = editor?.text == specification.draft
            }
            if thread != nil {
                let closeControls = views(in: controller.view).compactMap { $0 as? UIControl }.filter {
                    $0.accessibilityIdentifier == "conversation.header.trailing" && $0.accessibilityLabel == "Close" &&
                        $0.isEnabled && effectiveVisibleFrame($0, window: window) != nil && $0.allControlEvents.contains(.touchUpInside)
                }
                let close = try XCTUnwrap(closeControls.count == 1 ? closeControls.first : nil)
                behavior["threadCloseControlCount"] = closeControls.count
                behavior["threadCloseActivation"] = "sourceTrailingTouchUpInside"
                close.sendActions(for: .touchUpInside)
                try await wait("actual source thread close") {
                    window.layoutIfNeeded()
                    return !views(in: controller.view).contains { $0.accessibilityIdentifier == "conversation.replyThread" }
                }
                try await wait("actual source normal header after thread close") {
                    controls(in: controller.view, window: window).contains { ($0["label"] as? String) == "Back" } &&
                        close.accessibilityLabel == "Stop Chief"
                }
                let collections = views(in: controller.view).compactMap { $0 as? UICollectionView }
                XCTAssertEqual(editor?.text, specification.draft); XCTAssertTrue(editor?.isFirstResponder ?? false)
                XCTAssertTrue(store.isViewing); XCTAssertTrue(collections.allSatisfy { !$0.accessibilityElementsHidden })
                behavior["threadRemovedAfterClose"] = true
                behavior["backingAccessibilityRestored"] = collections.count == 1 && collections.allSatisfy { !$0.accessibilityElementsHidden }
                behavior["viewingAfterReturn"] = store.isViewing
                behavior["editorFocusedAfterReturn"] = editor?.isFirstResponder ?? false
                behavior["draftPreserved"] = editor?.text == specification.draft
                behavior["normalHeaderRestored"] = true
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
                "storeMessages": store.messages.count, "behavior": behavior, "thread": threadObservations,
                "captionMetrics": capturedCaptionMetrics,
                "scope": "Actual unmodified pinned UIKit controls, details rollback/focus/viewing, compact header and source quote activation/thread close observations; offline read-only backend. No real account/transport, physical device or animation acceptance."]
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

private final class ReferenceBackend: ConversationBackend, @unchecked Sendable {
    private let writeLock = NSLock()
    private var attemptedWrites = 0
    var writeAttempts: Int { writeLock.withLock { attemptedWrites } }
    private func readOnlyFailure() -> ReferenceFailure {
        writeLock.withLock { attemptedWrites += 1 }
        return .readOnly
    }
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
    func send(_ draft: ConversationOutgoingDraft) async throws -> ConversationMessage { throw readOnlyFailure() }
    func react(messageID: String, reaction: ConversationReaction?) async throws -> ConversationMessage { throw readOnlyFailure() }
    func edit(messageID: String, text: String) async throws -> ConversationMessage { throw readOnlyFailure() }
    func unsend(messageID: String) async throws -> ConversationMessage { throw readOnlyFailure() }
    func setTyping(_ isTyping: Bool) async {}
    func markRead(upToSeq: Int) async {}
    func uploadImage(_ data: Data, mimeType: String) async throws -> ConversationAttachment { throw readOnlyFailure() }
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


extension CmuxChiefReferenceTests {
    /// Separate from the static exports: keep UIKit animations enabled and sample
    /// real presentation layers/hit testing, not the model alpha's final target.
    @MainActor func testCaptureChiefReplyHeaderTimeline() async throws {
        let input = try resource("Scenes.json")
        let fixture = try JSONDecoder().decode(ReferenceScenes.self, from: input)
        let base = try XCTUnwrap(fixture.scenes.first { $0.id == "quote-dark" })
        let root = try XCTUnwrap(base.rows.first { $0.id == "parent" })
        let provenance = try JSONSerialization.jsonObject(with: resource("Provenance.json"))
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let priorAnimations = UIView.areAnimationsEnabled
        UIView.setAnimationsEnabled(true)
        defer { UIView.setAnimationsEnabled(priorAnimations) }
        XCTAssertFalse(UIAccessibility.isReduceMotionEnabled, "Dynamic probe requires the real non-reduced source path")
        var cases: [[String: Any]] = []
        // Exclude a real warm-up sequence: the first reply can compile render
        // resources and block the main actor, leaving gaps in an animation probe.
        // Every measured sequence still receives a fresh unmodified controller.
        for (name, enterDuration, reopen) in [("warm-up", 0.85, false), ("settled", 0.85, false), ("early-close", 0.10, false),
                                             ("glyph-swap-close", 0.34, false), ("reopen-return", 0.85, true),
                                             ("reopen-glyph-swap", 0.34, true)] {
            let store = ConversationStore(backend: ReferenceBackend(base, date: Date()))
            let controller = ConversationViewController(store: store, options: ConversationPresentationOptions(
                placeholder: "Message Chief", serviceTitle: "Chief", serviceSubtitle: "Runs your agents on your Mac",
                avatarImage: UIImage(data: try resource("ChiefAvatar.png")), trailingSymbol: "stop.fill",
                trailingAccessibilityLabel: "Stop Chief", supportsReactions: false, supportsEditing: false,
                supportsUnsend: false, supportsAttachments: false))
            let window = UIWindow(windowScene: scene)
            window.frame = scene.coordinateSpace.bounds; window.overrideUserInterfaceStyle = .dark
            window.rootViewController = controller; window.makeKeyAndVisible()
            defer { store.stop(); window.isHidden = true; window.rootViewController = nil }
            try await orient(scene, controller: controller, landscape: false)
            try await wait("animated header source ready") {
                window.layoutIfNeeded()
                return store.hasLoadedNewest && store.messages.count == base.rows.count && store.isViewing
            }
            let editor = try XCTUnwrap(views(in: controller.view).first { $0.accessibilityIdentifier == "conversation.composer.text" } as? UITextView)
            editor.text = "Retain animated header draft"; editor.delegate?.textViewDidChange?(editor)
            XCTAssertTrue(editor.becomeFirstResponder())
            try await Task.sleep(for: .milliseconds(700))
            window.layoutIfNeeded()
            let back = try XCTUnwrap(views(in: controller.view).compactMap { $0 as? UIButton }.first { $0.accessibilityLabel == "Back" })
            let trailing = try XCTUnwrap(views(in: controller.view).first { $0.accessibilityIdentifier == "conversation.header.trailing" } as? UIButton)
            let collection = try XCTUnwrap(views(in: controller.view).compactMap { $0 as? UICollectionView }.first)
            let containers = views(in: collection).filter { view in
                (view.accessibilityElements ?? []).contains {
                    ($0 as? UIAccessibilityElement)?.accessibilityIdentifier == "conversation.bubble.reply"
                }
            }
            let container = try XCTUnwrap(containers.count == 1 ? containers.first : nil)
            let quotes = (container.accessibilityElements ?? []).compactMap { $0 as? UIAccessibilityElement }.filter {
                $0.accessibilityTraits.contains(.button) && $0.accessibilityLabel == root.text
            }
            let quote = try XCTUnwrap(quotes.count == 1 ? quotes.first : nil)
            func overlay() -> UIView? { views(in: controller.view).first { $0.accessibilityIdentifier == "conversation.replyThread" } }
            func control(_ button: UIButton) -> [String: Any] {
                var model: Float = 1; var presentation: Float = 1
                var hidden = false; var axHidden = false; var presentedLayers = 0
                var cursor: UIView? = button
                while let view = cursor {
                    model *= Float(view.alpha)
                    if let layer = view.layer.presentation() { presentation *= layer.opacity; presentedLayers += 1 }
                    else { presentation *= Float(view.alpha) }
                    hidden = hidden || view.isHidden
                    axHidden = axHidden || view.accessibilityElementsHidden
                    cursor = view.superview
                }
                let rect = button.convert(button.bounds, to: window)
                let hit = window.hitTest(CGPoint(x: rect.midX, y: rect.midY), with: nil)
                let image = button.image(for: .normal)
                return ["label": button.accessibilityLabel ?? "", "modelOpacity": model,
                    "presentationOpacity": presentation, "presentationLayers": presentedLayers,
                    "hiddenAncestor": hidden, "axHiddenAncestor": axHidden,
                    "isAccessibilityElement": button.isAccessibilityElement, "enabled": button.isEnabled,
                    "interactionEnabled": button.isUserInteractionEnabled,
                    "hitControl": hit === button || hit?.isDescendant(of: button) == true,
                    "hitClass": hit.map { String(describing: type(of: $0)) } ?? "nil",
                    "frame": [rect.minX, rect.minY, rect.width, rect.height],
                    "imageSize": image.map { [$0.size.width, $0.size.height] } ?? [],
                    "symbol": image?.isSymbolImage ?? false]
            }
            func state(_ elapsed: Double) -> [String: Any] {
                ["elapsed": elapsed, "back": control(back), "trailing": control(trailing),
                 "overlayPresent": overlay() != nil, "backingAXHidden": collection.accessibilityElementsHidden,
                 "editorFocused": editor.isFirstResponder, "draftPreserved": editor.text == "Retain animated header draft",
                 "animationsEnabled": UIView.areAnimationsEnabled]
            }
            func sample(_ duration: Double, since start: CFTimeInterval) async throws -> [[String: Any]] {
                var result = [state(CACurrentMediaTime() - start)]
                while CACurrentMediaTime() - start < duration {
                    try await Task.sleep(for: .milliseconds(10))
                    result.append(state(CACurrentMediaTime() - start))
                }
                return result
            }
            XCTAssertNil(overlay())
            let initial = state(0)
            XCTAssertEqual(trailing.accessibilityLabel, "Stop Chief")
            let began = CACurrentMediaTime()
            XCTAssertTrue(quote.accessibilityActivate())
            let activationSeconds = CACurrentMediaTime() - began
            let entering = try await sample(enterDuration, since: began)
            let thread = try XCTUnwrap(overlay())
            let closingAt = CACurrentMediaTime()
            if name == "settled" {
                XCTAssertEqual(trailing.accessibilityLabel, "Close")
                trailing.sendActions(for: .touchUpInside)
            } else { XCTAssertTrue(thread.accessibilityPerformEscape()) }
            var phases: [[String: Any]] = [["name": "enter", "requestedDuration": enterDuration,
                "samples": entering, "activationSeconds": activationSeconds]]
            if reopen {
                let exitDuration = name == "reopen-glyph-swap" ? 0.01 : 0.10
                phases.append(["name": "exit-before-reopen", "requestedDuration": exitDuration,
                    "samples": try await sample(exitDuration, since: closingAt)])
                let reopened = CACurrentMediaTime()
                XCTAssertTrue(quote.accessibilityActivate())
                phases.append(["name": "reopen", "requestedDuration": 0.85,
                    "samples": try await sample(0.85, since: reopened)])
                let latest = try XCTUnwrap(overlay())
                let finalClose = CACurrentMediaTime(); XCTAssertTrue(latest.accessibilityPerformEscape())
                phases.append(["name": "final-exit", "requestedDuration": 0.85,
                    "samples": try await sample(0.85, since: finalClose)])
            } else {
                phases.append(["name": "exit", "requestedDuration": 0.85,
                    "samples": try await sample(0.85, since: closingAt)])
            }
            try await wait("header probe returns its source overlay") { overlay() == nil }
            XCTAssertEqual(editor.text, "Retain animated header draft"); XCTAssertTrue(editor.isFirstResponder)
            XCTAssertFalse(collection.accessibilityElementsHidden)
            if name != "warm-up" {
                cases.append(["name": name, "initial": initial, "phases": phases, "final": state(0)])
            }
            // Do not assert that UIKit cancels an interrupted delayed alpha fade:
            // observing its actual behavior is the purpose of these cases.
            try await Task.sleep(for: .milliseconds(150))
        }
        let payload: [String: Any] = ["schema": 1, "kind": "chiefReplyHeaderTimeline", "provenance": provenance,
            "fixtureSha256": SHA256.hash(data: input).map { String(format: "%02x", $0) }.joined(),
            "osVersion": UIDevice.current.systemVersion, "os": ProcessInfo.processInfo.operatingSystemVersionString,
            "viewport": [scene.coordinateSpace.bounds.width, scene.coordinateSpace.bounds.height], "scale": scene.screen.scale,
            "category": UIApplication.shared.preferredContentSizeCategory.rawValue,
            "reduceMotion": UIAccessibility.isReduceMotionEnabled, "cases": cases,
            "scope": "Animation-enabled unmodified pinned UIKit header. Presentation/model opacity, actual quote activation, close escape/trailing actions and window hit tests. Not physical VoiceOver, Android rendering or real account/backend evidence."]
        let attachment = XCTAttachment(data: try JSONSerialization.data(withJSONObject: payload, options: [.prettyPrinted, .sortedKeys]), uniformTypeIdentifier: "public.json")
        attachment.name = "chief-reply-header-timeline.json"; attachment.lifetime = .keepAlways; add(attachment)
    }
}

private struct ReferenceActionScenes: Decodable { var schema: Int; var scenes: [ReferenceActionScene] }
private struct ReferenceActionScene: Decodable {
    var id: String; var sourceScene: String; var style: String; var category: String
    var orientation: String; var messageID: String; var action: String
}

extension CmuxChiefReferenceTests {
    @MainActor func testCaptureStandardChiefActions() async throws {
        try await captureActions(category: "large", systemCategory: .large)
    }
    @MainActor func testCaptureAccessibilityChiefActions() async throws {
        try await captureActions(category: "accessibilityMedium", systemCategory: .accessibilityMedium)
    }

    @MainActor private func captureActions(category: String, systemCategory: UIContentSizeCategory) async throws {
        let fixture = try resource("Actions.json"), baseFixture = try resource("Scenes.json")
        let actions = try JSONDecoder().decode(ReferenceActionScenes.self, from: fixture)
        let bases = try JSONDecoder().decode(ReferenceScenes.self, from: baseFixture)
        XCTAssertEqual(actions.schema, 1)
        let provenance = try JSONSerialization.jsonObject(with: resource("Provenance.json"))
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        try await wait("actual action reference category") { UIApplication.shared.preferredContentSizeCategory == systemCategory && scene.activationState == .foregroundActive }
        UIView.setAnimationsEnabled(false)
        defer { UIView.setAnimationsEnabled(true) }
        for specification in actions.scenes where specification.category == category {
            let base = try XCTUnwrap(bases.scenes.first { $0.id == specification.sourceScene })
            let target = try XCTUnwrap(base.rows.first { $0.id == specification.messageID })
            let backend = ReferenceBackend(base, date: Date())
            let store = ConversationStore(backend: backend)
            let controller = ConversationViewController(store: store, options: ConversationPresentationOptions(
                placeholder: "Message Chief", serviceTitle: "Chief", serviceSubtitle: "Runs your agents on your Mac",
                avatarImage: UIImage(data: try resource("ChiefAvatar.png")), trailingSymbol: "stop.fill",
                trailingAccessibilityLabel: "Stop Chief", supportsReactions: false, supportsEditing: false,
                supportsUnsend: false, supportsAttachments: false))
            let window = UIWindow(windowScene: scene); window.frame = scene.coordinateSpace.bounds
            window.overrideUserInterfaceStyle = specification.style == "light" ? .light : .dark; window.rootViewController = controller; window.makeKeyAndVisible()
            defer { store.stop(); window.isHidden = true; window.rootViewController = nil }
            try await orient(scene, controller: controller, landscape: specification.orientation == "landscape")
            try await wait("action reference history") { window.layoutIfNeeded(); return store.hasLoadedNewest && store.messages.count == base.rows.count && store.isViewing }
            try verifyAncestorVisibility(window: window)
            let editor = try XCTUnwrap(views(in: controller.view).first { $0.accessibilityIdentifier == "conversation.composer.text" } as? UITextView)
            let draft = "Retain the draft through message actions"
            editor.text = draft; editor.delegate?.textViewDidChange?(editor); XCTAssertTrue(editor.becomeFirstResponder())
            @MainActor func bubble(_ id: String) -> UIAccessibilityElement? {
                accessibleElements(in: controller.view, window: window).first { $0.accessibilityIdentifier == "conversation.bubble." + id }
            }
            // Scroll the real collection to compose a live cell if compact geometry
            // has virtualized it. No fabricated cell or private source callback.
            if bubble(specification.messageID) == nil {
                let collection = try XCTUnwrap(views(in: controller.view).compactMap { $0 as? UICollectionView }.first)
                for index in 0..<collection.numberOfItems(inSection: 0) {
                    collection.scrollToItem(at: IndexPath(item: index, section: 0), at: .centeredVertically, animated: false)
                    window.layoutIfNeeded(); try await Task.sleep(for: .milliseconds(40))
                    if bubble(specification.messageID) != nil { break }
                }
            }
            let targetBubble = try XCTUnwrap(bubble(specification.messageID))
            let activationFrame = targetBubble.accessibilityFrame
            XCTAssertTrue(targetBubble.accessibilityActivate())
            try await wait("actual source action overlay") { views(in: controller.view).contains { $0.accessibilityIdentifier == "conversation.actions" } }
            let overlay = try XCTUnwrap(views(in: controller.view).first { $0.accessibilityIdentifier == "conversation.actions" })
            try await wait("source action menu keyboard dismissal") { !editor.isFirstResponder }
            try await Task.sleep(for: .milliseconds(350)); window.layoutIfNeeded()
            XCTAssertTrue(overlay.accessibilityViewIsModal)
            let rows = controls(in: overlay, window: window).filter { ($0["identifier"] as? String)?.hasPrefix("conversation.menu.") == true }
                .sorted { ($0["frame"] as! [CGFloat])[1] < ($1["frame"] as! [CGFloat])[1] }
            let required = ["arrowshape.turn.up.left", "doc.on.doc", "selection.pin.in.out", "ellipsis.circle"].map { "conversation.menu." + $0 }
            let identifiers = rows.compactMap { $0["identifier"] as? String }
            XCTAssertTrue(required.allSatisfy(identifiers.contains)); XCTAssertFalse(identifiers.contains("conversation.menu.pencil"))
            XCTAssertFalse(identifiers.contains("conversation.menu.arrow.uturn.backward.circle"))
            let platter = try XCTUnwrap(views(in: overlay).compactMap { $0 as? UIVisualEffectView }.first { candidate in
                views(in: candidate).contains { ($0.accessibilityIdentifier ?? "").hasPrefix("conversation.menu.") }
            })
            let clips = overlay.subviews.filter { type(of: $0) == UIView.self && $0.clipsToBounds && $0.bounds.width > 0 }
            let preview = try XCTUnwrap(clips.count == 1 ? clips.first : nil, "Require unique actual clipped snapshot container")
            var observation: [String: Any] = ["activation": "sourceBubbleAccessibilityActivate", "ancestorVisibilityGuardPassed": true,
                "keyboardDismissed": !editor.isFirstResponder, "modal": overlay.accessibilityViewIsModal,
                "rows": rows, "platterFrame": frame(platter, in: window), "previewFrame": frame(preview, in: window),
                "overlayFrame": frame(overlay, in: window), "screenInset": controller.systemMinimumLayoutMargins.leading,
                "safeArea": [overlay.safeAreaInsets.top, overlay.safeAreaInsets.left, overlay.safeAreaInsets.bottom, overlay.safeAreaInsets.right],
                "previewBounds": [preview.bounds.width, preview.bounds.height], "previewScale": preview.transform.a,
                "previewClips": preview.clipsToBounds, "previewCornerRadius": preview.layer.cornerRadius,
                "sourceBubbleFrame": [activationFrame.minX, activationFrame.minY, activationFrame.width, activationFrame.height],
                "menuLabels": labels(in: platter, window: window)]
            @MainActor func menuControl(_ symbol: String) throws -> UIControl {
                let matches = views(in: overlay).compactMap { $0 as? UIControl }.filter { $0.accessibilityIdentifier == "conversation.menu." + symbol }
                return try XCTUnwrap(matches.count == 1 ? matches.first : nil)
            }
            var behavior: [String: Any] = [:]
            if specification.action == "more" {
                try menuControl("ellipsis.circle").sendActions(for: .touchUpInside)
                try await wait("More enters source selection") { views(in: controller.view).contains { $0.accessibilityLabel == "Delete" && effectiveVisibleFrame($0, window: window) != nil } }
                let selected = accessibleElements(in: controller.view, window: window).filter { $0.accessibilityTraits.contains(.selected) }
                behavior["selectedInitially"] = selected.compactMap(\.accessibilityIdentifier)
                XCTAssertEqual(selected.filter { $0.accessibilityIdentifier == "conversation.bubble." + specification.messageID }.count, 1)
                let forward = controls(in: controller.view, window: window).filter { $0["label"] as? String == "Forward" }
                XCTAssertTrue(forward.isEmpty); XCTAssertNil(effectiveVisibleFrame(editor, window: window)); XCTAssertFalse(editor.isFirstResponder)
                behavior["forwardVisible"] = false; behavior["composerHidden"] = true
            }
            try await Task.sleep(for: .milliseconds(160)); window.layoutIfNeeded()
            let format = UIGraphicsImageRendererFormat(); format.scale = scene.screen.scale
            let image = UIGraphicsImageRenderer(bounds: window.bounds, format: format).image { _ in
                XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
            }
            let png = try XCTUnwrap(image.pngData())
            let capturedLabels = labels(in: controller.view, window: window)
            let capturedControls = controls(in: controller.view, window: window)
            if specification.action == "copy" {
                let previousClipboard = UIPasteboard.general.string
                defer { UIPasteboard.general.string = previousClipboard }
                UIPasteboard.general.string = "uncopied-chief-fixture-" + UUID().uuidString
                try menuControl("doc.on.doc").sendActions(for: .touchUpInside)
                try await wait("source Copy callback") { UIPasteboard.general.string == target.text && overlay.superview == nil }
                behavior["copyMatchesSourceText"] = true; behavior["copySentinelReplaced"] = true
            } else if specification.action == "reply" {
                try menuControl("arrowshape.turn.up.left").sendActions(for: .touchUpInside)
                try await wait("source menu Reply handoff") { overlay.superview == nil && views(in: controller.view).contains { $0.accessibilityIdentifier == "conversation.replyThread" } }
                behavior["replyHandoff"] = "sourceMenuTouchUpInside"
                behavior["editorFocusedAfterReply"] = editor.isFirstResponder
                XCTAssertTrue(editor.isFirstResponder)
                try await wait("source Close header after menu handoff") { views(in: controller.view).contains { $0.accessibilityIdentifier == "conversation.header.trailing" && $0.accessibilityLabel == "Close" } }
                let close = try XCTUnwrap(views(in: controller.view).compactMap { $0 as? UIControl }.first { $0.accessibilityIdentifier == "conversation.header.trailing" && $0.accessibilityLabel == "Close" })
                close.sendActions(for: .touchUpInside)
                try await wait("source thread returns after menu Reply") { !views(in: controller.view).contains { $0.accessibilityIdentifier == "conversation.replyThread" } }
            } else {
                let second = try XCTUnwrap(bubble("reply")); XCTAssertTrue(second.accessibilityActivate())
                let selected = accessibleElements(in: controller.view, window: window).filter { $0.accessibilityTraits.contains(.selected) }
                XCTAssertEqual(selected.count, 2); behavior["selectedAfterToggle"] = selected.compactMap(\.accessibilityIdentifier).sorted()
                let delete = try XCTUnwrap(views(in: controller.view).compactMap { $0 as? UIControl }.first { $0.accessibilityLabel == "Delete" && $0.isEnabled && effectiveVisibleFrame($0, window: window) != nil })
                delete.sendActions(for: .touchUpInside)
                try await wait("source delete confirmation") { controller.presentedViewController is UIAlertController }
                let alert = try XCTUnwrap(controller.presentedViewController as? UIAlertController)
                XCTAssertTrue(alert.actions.contains { $0.title == "Delete 2 Messages" && $0.style == .destructive })
                behavior["deleteConfirmation"] = alert.actions.compactMap(\.title)
                alert.dismiss(animated: false)
                try await wait("confirmation dismissed for selection cancel") { controller.presentedViewController == nil }
                let cancel = try XCTUnwrap(views(in: controller.view).compactMap { $0 as? UIControl }.first { $0.accessibilityIdentifier == "conversation.header.trailing" && $0.accessibilityLabel == "Cancel" })
                cancel.sendActions(for: .touchUpInside)
                try await wait("source selection cancel restores composer") { effectiveVisibleFrame(editor, window: window) != nil }
                behavior["selectionCancelRestoresComposer"] = true
                // Separate public Core action proof; this does not claim that the
                // destructive UIAlert action itself was activated.
                let ids = Set(store.messages.filter { ["parent", "reply"].contains($0.id) }.map(\.rowID))
                store.deleteLocally(rowIDs: ids)
                XCTAssertEqual(store.messages.map(\.id), ["next"])
                behavior["directCoreLocalDeleteIDs"] = store.messages.map(\.id)
                behavior["destructiveAlertActivated"] = false
            }
            XCTAssertEqual(editor.text, draft); XCTAssertEqual(backend.writeAttempts, 0)
            behavior["draftPreserved"] = editor.text == draft; behavior["backendWriteAttempts"] = backend.writeAttempts
            behavior["viewingAfterAction"] = store.isViewing
            observation["behavior"] = behavior
            let metadata: [String: Any] = ["actionScene": specification.id, "sourceScene": specification.sourceScene,
                "style": specification.style, "actualStyle": window.traitCollection.userInterfaceStyle == .dark ? "dark" : "light",
                "category": specification.category, "orientation": specification.orientation, "actualLandscape": scene.interfaceOrientation.isLandscape, "animationsEnabled": UIView.areAnimationsEnabled,
                "action": specification.action, "messageID": specification.messageID, "outgoing": target.outgoing,
                "capturePhase": specification.action == "more" ? "selection" : "menu",
                "fixtureSha256": SHA256.hash(data: fixture).map { String(format: "%02x", $0) }.joined(),
                "imageSha256": SHA256.hash(data: png).map { String(format: "%02x", $0) }.joined(),
                "provenance": provenance, "applicationCategoryMatchesScene": UIApplication.shared.preferredContentSizeCategory == systemCategory,
                "viewport": [image.size.width, image.size.height], "scale": format.scale,
                "labels": capturedLabels, "controls": capturedControls, "observation": observation,
                "scope": "Actual pinned UIKit bubble AX activation and registered MenuRow controls; settled menu/selection and public Core local-delete observations. No physical press/touch, Android parity, destructive alert activation or animation timing acceptance."]
            for (suffix, data, type) in [("png", png, "public.png"), ("json", try JSONSerialization.data(withJSONObject: metadata, options: [.prettyPrinted, .sortedKeys]), "public.json")] {
                let attachment = XCTAttachment(data: data, uniformTypeIdentifier: type); attachment.name = specification.id + "." + suffix; attachment.lifetime = .keepAlways; add(attachment)
            }
            try await orient(scene, controller: controller, landscape: false)
        }
    }
}
