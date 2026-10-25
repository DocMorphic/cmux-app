import XCTest
import CryptoKit

@MainActor final class HomeUITests: XCTestCase {
    private func launch(_ id: String, language: String = "en") -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["CMUX_HOME_REFERENCE"] = id
        app.launchArguments = ["-AppleLanguages", "(\(language))", "-AppleLocale", language == "ar" ? "ar_SA" : "en_US"]
        app.launch()
        return app
    }
    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }
    private func capture(_ app: XCUIApplication, _ id: String) {
        let description = app.debugDescription
        let tree = XCTAttachment(string: description)
        tree.name = id + ".tree"; tree.lifetime = .keepAlways; add(tree)
        let png = XCUIScreen.main.screenshot().pngRepresentation
        let pixels = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
        pixels.name = id + ".screen"; pixels.lifetime = .keepAlways; add(pixels)
        let raw = element(app, "reference.home.observation").value as? String ?? "missing"
        let report: [String: Any] = ["scene": id, "rpc": raw,
            "imageSha256": SHA256.hash(data: png).map { String(format: "%02x", $0) }.joined(),
            "treeSha256": SHA256.hash(data: Data(description.utf8)).map { String(format: "%02x", $0) }.joined()]
        let observation = XCTAttachment(data: try! JSONSerialization.data(withJSONObject: report, options: [.sortedKeys]), uniformTypeIdentifier: "public.json")
        observation.name = id + ".observation"; observation.lifetime = .keepAlways; add(observation)
    }
    func testHomeBranches() {
        continueAfterFailure = false
        for id in ["loading", "empty", "failure", "pinned-only", "recent", "recent-light", "recent-arabic", "recent-big"] {
            let app = launch(id, language: id == "recent-arabic" ? "ar" : "en")
            defer { app.terminate() }
            switch id {
            case "loading": XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Loading Chief")).firstMatch.waitForExistence(timeout: 10))
            case "empty": XCTAssertTrue(app.staticTexts["No Chief conversations"].waitForExistence(timeout: 10))
            case "failure": XCTAssertTrue(app.staticTexts["Reference Chief could not start"].waitForExistence(timeout: 10))
            default:
                let pinned = element(app, "MobileChiefPinnedConversation")
                XCTAssertTrue(pinned.waitForExistence(timeout: 10))
                XCTAssertTrue(pinned.label.contains("2"))
                if id == "pinned-only" {
                    XCTAssertTrue(element(app, "MobileChiefPinnedOnlyInvitation").waitForExistence(timeout: 5))
                } else {
                    let working = element(app, "MobileChiefConversationRow-working")
                    XCTAssertTrue(working.waitForExistence(timeout: 5))
                    XCTAssertTrue(working.label.contains("Working"))
                    XCTAssertFalse(working.label.contains("3 unread"))
                    XCTAssertTrue(element(app, "MobileChiefConversationRow-unicode").exists)
                }
            }
            capture(app, id)
        }
    }
    func testOriginalSearchFindsExpandedCaseAndRetainsNativeHome() {
        continueAfterFailure = false
        let app = launch("recent"); defer { app.terminate() }
        XCTAssertTrue(element(app, "MobileChiefPinnedConversation").waitForExistence(timeout: 10))
        // searchable may be below the initially expanded navigation title.
        if !app.searchFields.firstMatch.isHittable { app.swipeDown() }
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5)); search.tap(); search.typeText("strasse")
        XCTAssertTrue(element(app, "MobileChiefConversationRow-unicode").waitForExistence(timeout: 5))
        XCTAssertFalse(element(app, "MobileChiefPinnedConversation").exists)
        XCTAssertFalse(element(app, "MobileChiefConversationRow-working").exists)
        capture(app, "search-expanded-case")
    }
    func testQuestionOpenEditCancelReopenAndSubmitThroughWholeBackend() {
        continueAfterFailure = false
        let app = launch("question"); defer { app.terminate() }
        let pinned = element(app, "MobileChiefPinnedConversation")
        XCTAssertTrue(pinned.waitForExistence(timeout: 10)); pinned.tap()
        let entry = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Answer questions")).firstMatch
        XCTAssertTrue(entry.waitForExistence(timeout: 10)); entry.tap()
        let other = element(app, "MobileChiefQuestionOther-region")
        XCTAssertTrue(other.waitForExistence(timeout: 5))
        capture(app, "question-medium")
        other.tap(); other.typeText("Custom region")
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        capture(app, "question-keyboard")
        app.buttons["Cancel"].tap()
        XCTAssertTrue(entry.waitForExistence(timeout: 5)); entry.tap()
        XCTAssertTrue(other.waitForExistence(timeout: 5))
        XCTAssertTrue(["", "Other answer (optional)"].contains(other.value as? String ?? "missing"))
        capture(app, "question-reopened")
        app.buttons["Send"].tap()
        XCTAssertTrue(other.waitForNonExistence(timeout: 10))
        XCTAssertEqual(app.state, .runningForeground)
        XCTAssertFalse(element(app, "MobileChiefQuestionError").exists)
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "✓ United States")).firstMatch.waitForExistence(timeout: 5))
        capture(app, "question-submitted")
    }
}
