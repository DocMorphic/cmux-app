import XCTest
import CryptoKit

final class CloudOnboardingUITests: XCTestCase {
    @MainActor func testCapturePhoneStandard() throws { try capture(device: "phone", category: "large") }
    @MainActor func testCapturePhoneEnlarged() throws { try capture(device: "phone", category: "accessibilityMedium") }
    @MainActor func testCaptureTabletStandard() throws { try capture(device: "tablet", category: "large") }

    private func fixture() throws -> [String: Any] {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "References.json", withExtension: nil))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }
    @MainActor private func observation(_ app: XCUIApplication) -> [String: Any]? {
        let value = app.descendants(matching: .any).matching(identifier: "reference.cloud.observation").firstMatch
        guard value.exists, let data = value.label.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }
    @MainActor private func wait(_ app: XCUIApplication, _ condition: ([String: Any]) -> Bool) throws -> [String: Any] {
        let until = Date().addingTimeInterval(20)
        while Date() < until {
            if let value = observation(app), condition(value) { return value }
            RunLoop.current.run(until: Date().addingTimeInterval(0.12))
        }
        let diagnostics = XCTAttachment(string: app.debugDescription); diagnostics.name = "cloud-timeout-tree"; diagnostics.lifetime = .keepAlways; add(diagnostics)
        let screen = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); screen.name = "cloud-timeout-screen"; screen.lifetime = .keepAlways; add(screen)
        XCTFail("Source onboarding observation did not settle")
        throw NSError(domain: "CloudOnboardingReference", code: 1)
    }
    @MainActor private func element(_ value: XCUIElement) -> [String: Any] {
        let r = value.frame
        return ["identifier": value.identifier, "label": value.label, "value": value.value as? String ?? "",
            "type": value.elementType.rawValue, "hittable": value.isHittable, "enabled": value.isEnabled,
            "frame": [r.minX, r.minY, r.width, r.height]]
    }
    @MainActor private func capture(device: String, category: String) throws {
        continueAfterFailure = false
        let input = try fixture()
        let scenes = try XCTUnwrap(input["scenes"] as? [[String: String]])
        let labels = try XCTUnwrap(input["labels"] as? [String: [String: String]])
        for scene in scenes where scene["device"] == device && scene["category"] == category {
            let id = try XCTUnwrap(scene["id"]), language = try XCTUnwrap(scene["language"])
            let expected = try XCTUnwrap(labels[language])
            let app = XCUIApplication()
            let locale = ["en": "en_US", "ar": "ar_SA", "de": "de_DE", "es": "es_ES", "fr": "fr_FR", "ja": "ja_JP", "ko": "ko_KR", "zh-Hans": "zh_CN", "zh-Hant": "zh_TW"][language]!
            app.launchArguments = ["-AppleLanguages", "(\"\(language)\")", "-AppleLocale", locale]
            app.launchEnvironment["CMUX_CLOUD_ONBOARDING_REFERENCE"] = id
            XCUIDevice.shared.orientation = scene["orientation"] == "landscape" ? .landscapeRight : .portrait
            app.launch()
            defer { app.terminate() }
            _ = try wait(app) { data in
                guard data["ready"] as? Bool == true, data["device"] as? String == device,
                      let size = data["viewport"] as? [Double], size.count == 2 else { return false }
                return (size[0] > size[1]) == (scene["orientation"] == "landscape")
            }
            var pages: [[String: Any]] = []
            for page in 0..<3 {
                let titleKey = ["mobile.cloud.onboarding.workspace.title", "mobile.cloud.vpn.title", "mobile.cloud.onboarding.key.title"][page]
                let title = try XCTUnwrap(expected[titleKey])
                let copy = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", title)).firstMatch
                XCTAssertTrue(copy.waitForExistence(timeout: 10))
                let primary = app.buttons["CloudOnboardingPrimaryButton"]
                XCTAssertTrue(primary.waitForExistence(timeout: 10)); XCTAssertTrue(primary.isHittable)
                let expectedLabel = try XCTUnwrap(expected[page < 2 ? "mobile.cloud.onboarding.continue" : "mobile.cloud.onboarding.done"])
                XCTAssertEqual(primary.label, expectedLabel)
                let progress = app.descendants(matching: .any).matching(identifier: "CloudOnboardingProgressIndicator").firstMatch
                XCTAssertTrue(progress.exists)
                if page == 2 {
                    let finalProgress = try XCTUnwrap(progress.value as? String)
                    app.buttons["CloudOnboardingSecondaryButton"].tap()
                    let until = Date().addingTimeInterval(10)
                    while Date() < until && (progress.value as? String) == finalProgress { RunLoop.current.run(until: Date().addingTimeInterval(0.1)) }
                    XCTAssertNotEqual(progress.value as? String, finalProgress)
                    XCTAssertEqual(primary.label, expected["mobile.cloud.onboarding.continue"])
                    primary.tap()
                    let returned = Date().addingTimeInterval(10)
                    while Date() < returned && (progress.value as? String) != finalProgress { RunLoop.current.run(until: Date().addingTimeInterval(0.1)) }
                    XCTAssertEqual(progress.value as? String, finalProgress)
                }
                let snapshot = XCUIScreen.main.screenshot()
                let name = "cloud-" + id + "-page-\(page)"
                let image = XCTAttachment(screenshot: snapshot); image.name = name; image.lifetime = .keepAlways; add(image)
                // Read only source controls/copy needed by this reference. The
                // first diagnostic run spent most of its time resolving every
                // descendant's properties repeatedly and could not be exported
                // after interruption. No idle/quiescence or animation override.
                let elements = [element(copy)]
                var record = try wait(app) { $0["completed"] as? Bool == false }
                record["page"] = page; record["imageSha256"] = SHA256.hash(data: snapshot.pngRepresentation).map { String(format: "%02x", $0) }.joined()
                record["primary"] = element(primary); record["progress"] = element(progress); record["elements"] = elements
                record["captureMethod"] = "XCUIScreen.main.screenshot"
                record["backRoundTripVerified"] = page == 2
                let topic = ["workspace", "vpn", "key"][page]
                let graphic = app.images["CloudOnboardingGraphic-" + topic]
                record["graphic"] = graphic.exists ? element(graphic) : NSNull()
                let secondary = app.buttons["CloudOnboardingSecondaryButton"]
                record["secondary"] = secondary.exists ? element(secondary) : NSNull()
                pages.append(record)
                let prior = progress.value as? String
                XCTAssertFalse((prior ?? "").isEmpty)
                primary.tap()
                if page < 2 {
                    let until = Date().addingTimeInterval(10)
                    while Date() < until && (progress.value as? String) == prior { RunLoop.current.run(until: Date().addingTimeInterval(0.1)) }
                    XCTAssertNotEqual(progress.value as? String, prior)
                }
            }
            let completed = try wait(app) { $0["completed"] as? Bool == true && $0["completionCount"] as? Int == 1 }
            XCTAssertTrue(app.staticTexts["reference.cloud.completed"].exists)
            app.terminate(); app.launch()
            _ = try wait(app) { $0["ready"] as? Bool == true }
            let skip = app.buttons[expected["mobile.cloud.onboarding.skip"]!]
            XCTAssertTrue(skip.waitForExistence(timeout: 10)); XCTAssertTrue(skip.isHittable); skip.tap()
            let skipped = try wait(app) { $0["completed"] as? Bool == true && $0["completionCount"] as? Int == 1 }
            let payload: [String: Any] = ["schema": 1, "referenceKind": "cloudOnboardingScene", "scene": id,
                "pages": pages, "completed": completed, "skipped": skipped]
            let result = XCTAttachment(data: try JSONSerialization.data(withJSONObject: payload, options: [.prettyPrinted, .sortedKeys]), uniformTypeIdentifier: "public.json")
            result.name = "cloud-" + id + "-observations"; result.lifetime = .keepAlways; add(result)
        }
    }
}
