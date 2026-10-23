import XCTest
import CryptoKit

final class FeedFullTextUITests: XCTestCase {
    @MainActor func testCapturePhoneStandard() throws { try capture(device: "phone", category: "large") }
    @MainActor func testCapturePhoneEnlarged() throws { try capture(device: "phone", category: "accessibilityMedium") }
    @MainActor func testCaptureTabletStandard() throws { try capture(device: "tablet", category: "large") }

    @MainActor private func wait(_ app: XCUIApplication, scene: String, probe: String = "sheet",
                                 predicate: ([String: Any]) -> Bool = { _ in true }) throws -> [String: Any] {
        let element = app.staticTexts["reference.feed." + probe]
        let deadline = Date().addingTimeInterval(20)
        while Date() < deadline {
            if element.exists, let data = element.label.data(using: .utf8),
               let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               value["ready"] as? Bool == true, value["scene"] as? String == scene, predicate(value) { return value }
            RunLoop.current.run(until: Date().addingTimeInterval(0.15))
        }
        XCTFail("Original Full text observation did not settle: " + scene + "/" + probe)
        throw NSError(domain: "FeedFullTextReference", code: 1)
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
        let strings = try XCTUnwrap(fixture["uiStrings"] as? [String: [String: String]])
        let heading = try XCTUnwrap((fixture["probes"] as? [String: String])?["top"])
        for scene in scenes where scene["device"] == device && scene["category"] == category {
            let id = try XCTUnwrap(scene["id"]), language = try XCTUnwrap(scene["language"])
            let locale = ["en":"en_US", "ar":"ar_SA", "de":"de_DE", "es":"es_ES", "fr":"fr_FR", "ja":"ja_JP", "ko":"ko_KR", "zh-Hans":"zh_CN", "zh-Hant":"zh_TW"][language]!
            let app = XCUIApplication()
            app.launchArguments = ["-AppleLanguages", "(\"\(language)\")", "-AppleLocale", locale]
            app.launchEnvironment["CMUX_FEED_READER_SCENE"] = id
            XCUIDevice.shared.orientation = scene["orientation"] == "landscape" ? .landscapeRight : .portrait
            var pages: [[String: Any]] = []
            func snapshot(_ stage: String, target: XCUIElement, probe: String = "sheet") throws {
                let exists = target.waitForExistence(timeout: 10)
                // ProgressView's static label is visibly drawn but not a tap
                // target in UIKit AX. Preserve its actual hittability; prove
                // existence/frame plus compositor visibility instead.
                let frame = target.frame
                let inViewport = frame.width > 0 && frame.height > 0 && frame.minY >= app.frame.minY && frame.maxY <= app.frame.maxY
                if !exists || !inViewport || (stage != "loading" && !target.isHittable) {
                    let diagnostic = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
                    diagnostic.name = "feed-reader-" + id + "-" + stage + "-failure"; diagnostic.lifetime = .keepAlways; add(diagnostic)
                    let tree = XCTAttachment(string: app.debugDescription)
                    tree.name = "feed-reader-" + id + "-" + stage + "-accessibility"; tree.lifetime = .keepAlways; add(tree)
                }
                XCTAssertTrue(exists); XCTAssertTrue(inViewport)
                if stage != "loading" { XCTAssertTrue(target.isHittable) }
                var record = try wait(app, scene: id, probe: probe)
                let close = app.buttons["MobileAgentFeedFullTextClose"]
                if probe == "sheet" {
                    XCTAssertTrue(close.exists); XCTAssertEqual(close.label, strings["close"]![language]!)
                    XCTAssertFalse(app.buttons["Source"].exists); XCTAssertFalse(app.buttons["Formatted"].exists)
                }
                let image = XCUIScreen.main.screenshot()
                let attachment = XCTAttachment(screenshot: image)
                attachment.name = "feed-reader-" + id + "-" + stage; attachment.lifetime = .keepAlways; add(attachment)
                record["stage"] = stage; record["target"] = element(target); record["close"] = element(close)
                record["unexpectedSourceControl"] = app.buttons["Source"].exists || app.buttons["Formatted"].exists
                record["title"] = element(app.navigationBars.staticTexts[strings["title"]![language]!].firstMatch)
                record["captureMethod"] = "XCUIScreen.main.screenshot"
                record["imageSha256"] = SHA256.hash(data: image.pngRepresentation).map { String(format: "%02x", $0) }.joined()
                pages.append(record)
            }
            app.launchEnvironment["CMUX_FEED_READER_MODE"] = "loading"; app.launch()
            _ = try wait(app, scene: id) { $0["loadAttempts"] as? Int == 1 }
            try snapshot("loading", target: app.staticTexts[strings["loading"]![language]!])
            app.buttons["MobileAgentFeedFullTextClose"].tap()
            let loadingDismissal = try wait(app, scene: id, probe: "root") {
                $0["dismissed"] as? Bool == true && $0["loadCancellations"] as? Int == 1
            }
            XCTAssertFalse(app.buttons["MobileAgentFeedFullTextClose"].exists); app.terminate()

            app.launchEnvironment["CMUX_FEED_READER_MODE"] = "retry"; app.launch()
            _ = try wait(app, scene: id) { $0["loadAttempts"] as? Int == 1 }
            try snapshot("error", target: app.staticTexts[strings["unavailable"]![language]!])
            let retry = app.buttons[strings["retry"]![language]!]
            XCTAssertTrue(retry.isHittable); retry.tap()
            _ = try wait(app, scene: id) { $0["loadAttempts"] as? Int == 2 }
            try snapshot("loaded", target: app.staticTexts.matching(NSPredicate(format: "label == %@", heading)).firstMatch)
            XCTAssertFalse(app.staticTexts[strings["unavailable"]![language]!].exists)
            XCTAssertFalse(retry.exists)
            app.buttons["MobileAgentFeedFullTextClose"].tap()
            _ = try wait(app, scene: id, probe: "root") { $0["dismissed"] as? Bool == true }
            try snapshot("closed", target: app.buttons["reference.feed.open"], probe: "root")
            XCTAssertFalse(app.buttons["MobileAgentFeedFullTextClose"].exists)
            XCTAssertEqual(pages.last?["loadAttempts"] as? Int, 2)
            let payload: [String: Any] = ["schema": 1, "referenceKind": "originalFeedFullTextScene", "scene": id,
                "pages": pages, "loadingDismissal": loadingDismissal]
            let result = XCTAttachment(data: try JSONSerialization.data(withJSONObject: payload, options: [.prettyPrinted, .sortedKeys]), uniformTypeIdentifier: "public.json")
            result.name = "feed-reader-" + id + "-observations"; result.lifetime = .keepAlways; add(result)
            app.terminate()
        }
    }
}
