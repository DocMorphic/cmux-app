import XCTest
import CryptoKit

/// Source controls plus OS compositor images. Synthetic poll data is not backend acceptance.
final class CmuxChiefPollUITests: XCTestCase {
    @MainActor func testCaptureStandardChiefPollReferences() throws { try capture(category: "large") }
    @MainActor func testCaptureAccessibilityChiefPollReferences() throws { try capture(category: "accessibilityMedium") }
    @MainActor func testCaptureExtraSmallPollReferences() throws { try capture(category: "extraSmall") }
    @MainActor func testCaptureSmallPollReferences() throws { try capture(category: "small") }
    @MainActor func testCaptureMediumPollReferences() throws { try capture(category: "medium") }
    @MainActor func testCaptureExtraLargePollReferences() throws { try capture(category: "extraLarge") }
    @MainActor func testCaptureExtraExtraLargePollReferences() throws { try capture(category: "extraExtraLarge") }
    @MainActor func testCaptureExtraExtraExtraLargePollReferences() throws { try capture(category: "extraExtraExtraLarge") }
    @MainActor func testCaptureAccessibilityLargePollReferences() throws { try capture(category: "accessibilityLarge") }
    @MainActor func testCaptureAccessibilityExtraLargePollReferences() throws { try capture(category: "accessibilityExtraLarge") }
    @MainActor func testCaptureAccessibilityExtraExtraLargePollReferences() throws { try capture(category: "accessibilityExtraExtraLarge") }
    @MainActor func testCaptureAccessibilityExtraExtraExtraLargePollReferences() throws { try capture(category: "accessibilityExtraExtraExtraLarge") }

    @MainActor func testPollDetailsSurvivesLiveRotation() throws {
        continueAfterFailure = false
        let input = try Data(contentsOf: XCTUnwrap(Bundle(for: Self.self).url(forResource: "PollReferences.json", withExtension: nil)))
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: input) as? [String: Any])
        let scenes = try XCTUnwrap(fixture["scenes"] as? [[String: String]])
        let specification = try XCTUnwrap(scenes.first { $0["id"] == "poll-pending-dark" })
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launchEnvironment["CMUX_POLL_REFERENCE"] = "poll-pending-dark"
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        defer { app.terminate(); XCUIDevice.shared.orientation = .portrait }
        _ = try wait("lifecycle-ready", app: app) { $0["ready"] as? Bool == true && $0["categoryMatches"] as? Bool == true }
        let bubble = app.descendants(matching: .any).matching(identifier: "conversation.bubble.poll").firstMatch
        XCTAssertTrue(bubble.waitForExistence(timeout: 10)); XCTAssertTrue(bubble.isHittable)
        bubble.press(forDuration: 0.9)
        let details = app.buttons["conversation.menu.chart.bar.doc.horizontal"]
        XCTAssertTrue(details.waitForExistence(timeout: 10)); XCTAssertTrue(details.isHittable)
        XCTAssertEqual(details.label, "Poll Details"); details.tap()
        XCTAssertTrue(app.tables["conversation.poll.details"].waitForExistence(timeout: 10))
        var steps: [[String: Any]] = []
        var presentation: Int?; var table: Int?; var observationOwner: String?
        for (phase, orientation) in [("portrait-before", UIDeviceOrientation.portrait),
                                      ("landscape-open", .landscapeRight), ("portrait-returned", .portrait)] {
            XCUIDevice.shared.orientation = orientation
            var previous = Data(); var stable = 0
            let observed = try wait("lifecycle-" + phase, app: app) { value in
                guard value["presented"] as? Bool == true,
                      let viewport = value["viewport"] as? [Double], viewport.count == 2,
                      (viewport[0] > viewport[1]) == (orientation == .landscapeRight),
                      let current = value["table"] as? [String: Any], current["sections"] is [[String: Any]] else { return false }
                let signature: [String: Any] = ["table": current, "labels": value["labels"] ?? []]
                let bytes = try! JSONSerialization.data(withJSONObject: signature, options: [.sortedKeys])
                stable = bytes == previous ? stable + 1 : 0; previous = bytes
                return stable >= 3
            }
            let owner = try XCTUnwrap(observed["observationOwner"] as? String)
            if let observationOwner { XCTAssertEqual(owner, observationOwner) } else { observationOwner = owner }
            let currentPresentation = try XCTUnwrap(observed["presentationGeneration"] as? Int)
            let currentTable = try XCTUnwrap(observed["tableGeneration"] as? Int)
            XCTAssertGreaterThan(currentPresentation, 0); XCTAssertGreaterThan(currentTable, 0)
            if let presentation { XCTAssertEqual(currentPresentation, presentation) } else { presentation = currentPresentation }
            if let table { XCTAssertEqual(currentTable, table) } else { table = currentTable }
            XCTAssertEqual(observed["backendWriteAttempts"] as? Int, 0)
            let image = XCUIScreen.main.screenshot().pngRepresentation
            var value = observed
            value["fixture"] = specification
            value["fixtureSha256"] = SHA256.hash(data: input).map { String(format: "%02x", $0) }.joined()
            value["imageSha256"] = SHA256.hash(data: image).map { String(format: "%02x", $0) }.joined()
            value["captureMethod"] = "XCUIScreen.main.screenshot"
            value["activation"] = "physicalPressAndSourcePollDetails"
            steps.append(["phase": phase, "state": value])
            let screenshot = XCTAttachment(data: image, uniformTypeIdentifier: "public.png")
            screenshot.name = "poll-lifecycle-" + phase + ".png"; screenshot.lifetime = .keepAlways; add(screenshot)
        }
        let done = app.buttons["Done"].firstMatch
        XCTAssertTrue(done.exists); XCTAssertTrue(done.isHittable); done.tap()
        _ = try wait("lifecycle-dismissed", app: app) { $0["presented"] as? Bool == false }
        let payload: [String: Any] = ["schema": 1, "pollLifecycle": "pending-roundtrip", "steps": steps, "doneDismissed": true,
            "scope": "Actual source sheet/menu, same observed controller/table through live rotation and compositor captures with controlled pending data. Not process/Activity restoration, actual Chief RPC, votes, physical device or VoiceOver."]
        let metadata = XCTAttachment(data: try JSONSerialization.data(withJSONObject: payload, options: [.prettyPrinted, .sortedKeys]), uniformTypeIdentifier: "public.json")
        metadata.name = "poll-lifecycle-pending-roundtrip.json"; metadata.lifetime = .keepAlways; add(metadata)
    }

    @MainActor private func observation(_ app: XCUIApplication) -> [String: Any]? {
        let node = app.descendants(matching: .any).matching(identifier: "reference.poll.observation").firstMatch
        guard node.exists, let bytes = node.label.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: bytes)) as? [String: Any]
    }
    @MainActor private func wait(_ name: String, app: XCUIApplication,
                                condition: ([String: Any]) -> Bool) throws -> [String: Any] {
        let deadline = Date().addingTimeInterval(20)
        while Date() < deadline {
            if let value = observation(app), condition(value) { return value }
            RunLoop.current.run(until: Date().addingTimeInterval(0.12))
        }
        let tree = XCTAttachment(string: app.debugDescription)
        tree.name = "poll-timeout-tree-" + name; tree.lifetime = .keepAlways; add(tree)
        let image = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        image.name = "poll-timeout-screen-" + name; image.lifetime = .keepAlways; add(image)
        if let value = observation(app), let bytes = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]) {
            let state = XCTAttachment(data: bytes, uniformTypeIdentifier: "public.json")
            state.name = "poll-timeout-state-" + name; state.lifetime = .keepAlways; add(state)
        }
        XCTFail("Timed out waiting for source Poll Details " + name)
        throw NSError(domain: "ChiefPollReference", code: 1)
    }
    @MainActor private func capture(category: String) throws {
        continueAfterFailure = false
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "PollReferences.json", withExtension: nil))
        let input = try Data(contentsOf: url)
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: input) as? [String: Any])
        let scenes = try XCTUnwrap(fixture["scenes"] as? [[String: String]])
        XCTAssertTrue(scenes.contains { $0["category"] == category }, "Missing category fixture: " + category)
        for specification in scenes where specification["category"] == category {
            let name = try XCTUnwrap(specification["id"])
            let app = XCUIApplication()
            app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
            app.launchEnvironment["CMUX_POLL_REFERENCE"] = name
            XCUIDevice.shared.orientation = .portrait
            app.launch()
            defer { app.terminate() }
            _ = try wait("ready-" + name, app: app) { $0["ready"] as? Bool == true && $0["categoryMatches"] as? Bool == true }
            if specification["orientation"] == "landscape" { XCUIDevice.shared.orientation = .landscapeRight }
            _ = try wait("orientation-" + name, app: app) { value in
                guard let size = value["viewport"] as? [Double], size.count == 2 else { return false }
                return (size[0] > size[1]) == (specification["orientation"] == "landscape")
            }
            let bubble = app.descendants(matching: .any).matching(identifier: "conversation.bubble.poll").firstMatch
            XCTAssertTrue(bubble.waitForExistence(timeout: 10)); XCTAssertTrue(bubble.isHittable)
            bubble.press(forDuration: 0.9)
            let details = app.buttons["conversation.menu.chart.bar.doc.horizontal"]
            XCTAssertTrue(details.waitForExistence(timeout: 10)); XCTAssertTrue(details.isHittable)
            XCTAssertEqual(details.label, "Poll Details"); details.tap()
            let table = app.tables["conversation.poll.details"]
            XCTAssertTrue(table.waitForExistence(timeout: 10))
            _ = try wait("table-" + name, app: app) { ($0["table"] as? [String: Any])?["sections"] is [[String: Any]] }
            if specification["scroll"] == "bottom" {
                for _ in 0..<3 { table.swipeUp() }
            }
            var previous = Data(); var stable = 0
            _ = try wait("settled-" + name, app: app) { value in
                let state: [String: Any] = ["table": value["table"] ?? [:], "labels": value["labels"] ?? []]
                let current = try! JSONSerialization.data(withJSONObject: state, options: [.sortedKeys])
                stable = current == previous ? stable + 1 : 0; previous = current
                return stable >= 3
            }
            var value = try XCTUnwrap(observation(app))
            let image = XCUIScreen.main.screenshot().pngRepresentation
            value["pollScene"] = name; value["fixture"] = specification
            value["fixtureSha256"] = SHA256.hash(data: input).map { String(format: "%02x", $0) }.joined()
            value["imageSha256"] = SHA256.hash(data: image).map { String(format: "%02x", $0) }.joined()
            value["captureMethod"] = "XCUIScreen.main.screenshot"
            value["activation"] = "physicalPressAndSourcePollDetails"
            value["scope"] = "Unmodified pinned conversation package with controlled pending/generic poll data and public UIKit observation. Compositor reference only; not Chief RPC, votes, live updates, physical device or VoiceOver acceptance."
            let done = app.buttons["Done"].firstMatch
            XCTAssertTrue(done.exists); XCTAssertTrue(done.isHittable); done.tap()
            _ = try wait("dismissed-" + name, app: app) { $0["presented"] as? Bool == false }
            value["doneDismissed"] = true
            let screenshot = XCTAttachment(data: image, uniformTypeIdentifier: "public.png")
            screenshot.name = name + ".png"; screenshot.lifetime = .keepAlways; add(screenshot)
            let metadata = XCTAttachment(data: try JSONSerialization.data(withJSONObject: value, options: [.prettyPrinted, .sortedKeys]), uniformTypeIdentifier: "public.json")
            metadata.name = name + ".json"; metadata.lifetime = .keepAlways; add(metadata)
            XCTAssertEqual(value["backendWriteAttempts"] as? Int, 0)
            XCTAssertEqual(value["style"] as? String, specification["style"])
            XCTAssertEqual(value["direction"] as? String, specification["direction"])
            app.terminate()
        }
        XCUIDevice.shared.orientation = .portrait
    }
}
