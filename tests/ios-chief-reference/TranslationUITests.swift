import XCTest
import CryptoKit

/// Real UI-test process and compositor images. Controlled translations establish UI behavior, not inference.
final class CmuxChiefTranslationUITests: XCTestCase {
    @MainActor func testCaptureStandardChiefTranslationReferences() throws { try capture(category: "large") }
    @MainActor func testCaptureAccessibilityChiefTranslationReferences() throws { try capture(category: "accessibilityMedium") }

    private func input() throws -> Data { try Data(contentsOf: XCTUnwrap(Bundle(for: Self.self).url(forResource: "TranslationReferences.json", withExtension: nil))) }
    @MainActor private func observation(_ app: XCUIApplication) -> [String: Any]? {
        let channel = app.descendants(matching: .any).matching(identifier: "reference.translation.observation").firstMatch
        guard channel.exists, let data = channel.label.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }
    @MainActor private func wait(_ description: String, app: XCUIApplication,
                                condition: ([String: Any]) -> Bool) throws -> [String: Any] {
        let deadline = Date().addingTimeInterval(20)
        while Date() < deadline {
            if let value = observation(app), condition(value) { return value }
            RunLoop.current.run(until: Date().addingTimeInterval(0.12))
        }
        let diagnostic = XCTAttachment(string: app.debugDescription)
        diagnostic.name = "translation-timeout-" + description; diagnostic.lifetime = .keepAlways; add(diagnostic)
        XCTFail("Timed out waiting for actual source " + description)
        throw NSError(domain: "ChiefTranslationReference", code: 1)
    }
    @MainActor private func capture(category: String) throws {
        continueAfterFailure = false
        let data = try input()
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let scenes = try XCTUnwrap(fixture["scenes"] as? [[String: String]])
        for specification in scenes where specification["category"] == category {
            let name = try XCTUnwrap(specification["id"]), mode = try XCTUnwrap(specification["mode"])
            let target = try XCTUnwrap(specification["target"])
            let app = XCUIApplication()
            app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
            app.launchEnvironment["CMUX_TRANSLATION_REFERENCE"] = name
            XCUIDevice.shared.orientation = .portrait
            app.launch()
            defer { app.terminate() }
            _ = try wait("ready-" + name, app: app) { $0["ready"] as? Bool == true && $0["categoryMatches"] as? Bool == true }
            if specification["orientation"] == "landscape" { XCUIDevice.shared.orientation = .landscapeRight }
            _ = try wait("orientation-" + name, app: app) { value in
                guard let size = value["viewport"] as? [Double], size.count == 2 else { return false }
                return (size[0] > size[1]) == (specification["orientation"] == "landscape")
            }
            let bubble = app.descendants(matching: .any).matching(identifier: "conversation.bubble." + target).firstMatch
            XCTAssertTrue(bubble.waitForExistence(timeout: 10)); XCTAssertTrue(bubble.isHittable)
            bubble.press(forDuration: 0.9)
            let translate = app.buttons["conversation.menu.translate"]
            XCTAssertTrue(translate.waitForExistence(timeout: 10)); translate.tap()
            _ = try wait("choice-" + name, app: app) { ($0["alertActions"] as? [String])?.contains("Translate This Message") == true }
            if mode != "choice" {
                app.buttons[mode == "conversation" ? "Translate Conversation" : "Translate This Message"].tap()
                if mode == "from" {
                    _ = try wait("from-" + name, app: app) { $0["alertTitle"] as? String == "Translate From" }
                } else {
                    _ = try wait("translated-" + name, app: app) { value in
                        let p = (value["presentations"] as? [[String: Any]])?.first { $0["id"] as? String == target }
                        let labels = value["labels"] as? [[String: Any]] ?? []
                        return p?["caption"] as? String == "showOriginal" && labels.contains {
                            $0["identifier"] as? String == "conversation.message.translation" && ($0["text"] as? String)?.contains("Show Original") == true
                        }
                    }
                    if mode == "original" {
                        let value = try XCTUnwrap(observation(app))
                        let label = try XCTUnwrap((value["labels"] as? [[String: Any]])?.first {
                            $0["identifier"] as? String == "conversation.message.translation"
                        })
                        let frame = try XCTUnwrap(label["frame"] as? [Double])
                        app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: frame[0] + frame[2] / 2, dy: frame[1] + frame[3] / 2)).tap()
                        _ = try wait("original-" + name, app: app) { value in
                            (value["presentations"] as? [[String: Any]])?.first { $0["id"] as? String == target }?["caption"] as? String == "viewTranslation"
                        }
                    } else if mode == "menu" {
                        bubble.press(forDuration: 0.9)
                        XCTAssertTrue(translate.waitForExistence(timeout: 10)); XCTAssertEqual(translate.label, "Show Original")
                    }
                }
            }
            // Wait for system animations and require stable public UIKit label/control geometry.
            var previous = Data(); var stable = 0
            _ = try wait("settled-" + name, app: app) { value in
                let geometry: [String: Any] = ["labels": value["labels"] ?? [], "controls": value["controls"] ?? []]
                let current = try! JSONSerialization.data(withJSONObject: geometry, options: [.sortedKeys])
                stable = current == previous ? stable + 1 : 0; previous = current
                return stable >= 3
            }
            var value = try XCTUnwrap(observation(app))
            XCTAssertEqual(value["backendWriteAttempts"] as? Int, 0)
            XCTAssertEqual(value["translator"] as? String, "controlled")
            XCTAssertEqual(value["style"] as? String, specification["style"])
            XCTAssertEqual(value["direction"] as? String, specification["direction"])
            let png = XCUIScreen.main.screenshot().pngRepresentation
            value["translationScene"] = name; value["fixture"] = specification
            value["fixtureSha256"] = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
            value["imageSha256"] = SHA256.hash(data: png).map { String(format: "%02x", $0) }.joined()
            value["captureMethod"] = "XCUIScreen.main.screenshot"
            value["activation"] = "physicalPressAndSourceControls"
            value["scope"] = "Unmodified pinned UIKit UI with controlled translations, real source menu/UIAlert actions and OS compositor screenshot. Observer adds a transparent AX transport node. Not model inference, physical device or VoiceOver acceptance."
            let screenshot = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
            screenshot.name = name + ".png"; screenshot.lifetime = .keepAlways; add(screenshot)
            let metadata = XCTAttachment(data: try JSONSerialization.data(withJSONObject: value, options: [.prettyPrinted, .sortedKeys]), uniformTypeIdentifier: "public.json")
            metadata.name = name + ".json"; metadata.lifetime = .keepAlways; add(metadata)
            app.terminate()
        }
        XCUIDevice.shared.orientation = .portrait
    }
}
