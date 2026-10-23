import XCTest
import CryptoKit

final class NativeMarkdownUITests: XCTestCase {
    @MainActor func testCapturePhoneStandard() throws { try capture(device: "phone", category: "large") }
    @MainActor func testCapturePhoneEnlarged() throws { try capture(device: "phone", category: "accessibilityMedium") }
    @MainActor func testCaptureTabletStandard() throws { try capture(device: "tablet", category: "large") }

    @MainActor private func wait(_ app: XCUIApplication, scene: String) throws -> [String: Any] {
        let probe = app.staticTexts["reference.markdown.observation"]
        let until = Date().addingTimeInterval(20)
        while Date() < until {
            if probe.exists, let data = probe.label.data(using: .utf8),
               let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               value["ready"] as? Bool == true, value["scene"] as? String == scene { return value }
            RunLoop.current.run(until: Date().addingTimeInterval(0.15))
        }
        XCTFail("Original native Markdown host did not settle")
        throw NSError(domain: "NativeMarkdownReference", code: 1)
    }
    @MainActor private func visible(_ element: XCUIElement, in app: XCUIApplication) -> Bool {
        guard element.exists else { return false }
        let r = element.frame, screen = app.frame
        // The original leaf has 16-pt padding. A landscape window can have
        // zero top safe area; an invented 30-pt exclusion would reject its
        // fully visible first heading and scroll that heading offscreen.
        return element.isHittable && r.width > 0 && r.height > 0 && r.minY >= screen.minY && r.maxY <= screen.maxY
    }
    @MainActor private func expose(_ element: XCUIElement, in app: XCUIApplication) throws {
        for _ in 0..<16 {
            if visible(element, in: app) { return }
            // The root source ScrollView only; source code/table horizontal
            // ScrollViews never receive these vertical navigation gestures.
            let from = app.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.76))
            let to = app.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.40))
            from.press(forDuration: 0.05, thenDragTo: to)
        }
        XCTFail("Source probe did not become visible: " + element.debugDescription)
        throw NSError(domain: "NativeMarkdownReference", code: 2)
    }
    @MainActor private func element(_ value: XCUIElement) -> [String: Any] {
        guard value.exists else { return ["exists": false] }
        let r = value.frame
        return ["exists": true, "label": value.label, "hittable": value.isHittable,
            "frame": [r.minX, r.minY, r.width, r.height], "type": value.elementType.rawValue]
    }
    @MainActor private func capture(device: String, category: String) throws {
        continueAfterFailure = false
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "References.json", withExtension: nil))
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let scenes = try XCTUnwrap(fixture["scenes"] as? [[String: String]])
        let probes = try XCTUnwrap(fixture["probes"] as? [String: String])
        let stages = try XCTUnwrap(fixture["stages"] as? [String])
        for scene in scenes where scene["device"] == device && scene["category"] == category {
            let id = try XCTUnwrap(scene["id"]), language = try XCTUnwrap(scene["language"])
            let locale = ["en": "en_US", "ar": "ar_SA", "de": "de_DE", "es": "es_ES", "fr": "fr_FR", "ja": "ja_JP", "ko": "ko_KR", "zh-Hans": "zh_CN", "zh-Hant": "zh_TW"][language]!
            let app = XCUIApplication()
            app.launchArguments = ["-AppleLanguages", "(\"\(language)\")", "-AppleLocale", locale]
            app.launchEnvironment["CMUX_NATIVE_MARKDOWN_REFERENCE"] = id
            XCUIDevice.shared.orientation = scene["orientation"] == "landscape" ? .landscapeRight : .portrait
            app.launch(); defer { app.terminate() }
            _ = try wait(app, scene: id)
            var pages: [[String: Any]] = []
            for stage in stages {
                let text = try XCTUnwrap(probes[stage])
                let target = app.staticTexts.matching(NSPredicate(format: "label == %@", text)).firstMatch
                try expose(target, in: app)
                var record = try wait(app, scene: id)
                let snapshot = XCUIScreen.main.screenshot()
                let image = XCTAttachment(screenshot: snapshot)
                image.name = "markdown-" + id + "-" + stage; image.lifetime = .keepAlways; add(image)
                record["stage"] = stage; record["target"] = element(target)
                record["caption"] = element(app.staticTexts[probes["caption"]!])
                record["captureMethod"] = "XCUIScreen.main.screenshot"
                record["imageSha256"] = SHA256.hash(data: snapshot.pngRepresentation).map { String(format: "%02x", $0) }.joined()
                pages.append(record)
            }
            let payload: [String: Any] = ["schema": 1, "referenceKind": "nativeMarkdownScene", "scene": id, "pages": pages]
            let result = XCTAttachment(data: try JSONSerialization.data(withJSONObject: payload, options: [.prettyPrinted, .sortedKeys]), uniformTypeIdentifier: "public.json")
            result.name = "markdown-" + id + "-observations"; result.lifetime = .keepAlways; add(result)
        }
    }
}
