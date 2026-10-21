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
