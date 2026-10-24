import XCTest
import CryptoKit

final class SignInMethodsUITests: XCTestCase {
    @MainActor func testCapturePhoneStandard() throws { try capture(device: "phone", category: "large") }
    @MainActor func testCapturePhoneEnlarged() throws { try capture(device: "phone", category: "accessibilityMedium") }
    @MainActor func testCaptureTabletStandard() throws { try capture(device: "tablet", category: "large") }
    @MainActor private func capture(device: String, category: String) throws {
        let fixtureURL = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "References.json", withExtension: nil))
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: fixtureURL)) as? [String: Any])
        let scenes = try XCTUnwrap(fixture["scenes"] as? [[String: String]])
        func element(_ value: XCUIElement) -> [String: Any] {
            let r=value.frame
            return ["identifier":value.identifier,"label":value.label,"enabled":value.isEnabled,"hittable":value.isHittable,
                "frame":[r.minX,r.minY,r.width,r.height]]
        }
        for scene in scenes where scene["device"] == device && scene["category"] == category {
            let id=try XCTUnwrap(scene["id"]); let app=XCUIApplication()
            app.launchEnvironment["CMUX_SIGNIN_METHODS_REFERENCE"]=id
            app.launchArguments=["-AppleLanguages","(\(scene["language"]!))","-AppleLocale",scene["language"]!]
            XCUIDevice.shared.orientation = scene["orientation"] == "landscape" ? .landscapeLeft : .portrait
            app.launch()
            let observation=app.descendants(matching:.any).matching(identifier:"reference.signin.observation").firstMatch
            XCTAssertTrue(observation.waitForExistence(timeout:20))
            var record: [String:Any]?
            let deadline=Date().addingTimeInterval(20)
            while Date() < deadline {
                if let data=observation.label.data(using:.utf8), let value=(try? JSONSerialization.jsonObject(with:data)) as? [String:Any], value["ready"] as? Bool == true { record=value;break }
                RunLoop.current.run(until:Date().addingTimeInterval(0.1))
            }
            var value=try XCTUnwrap(record)
            let email=app.textFields["Email"];let request=app.buttons["signin.emailCode"]
            XCTAssertTrue(email.exists);XCTAssertTrue(request.exists)
            if scene["mode"] == "keyboard" { email.tap();email.typeText("fixture@example.test");XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout:5)) }
            let providers=["apple","google","github"].map { app.buttons["signin."+$0] }
            for button in providers { XCTAssertTrue(button.exists);XCTAssertEqual(button.isEnabled,!["google","restoring"].contains(scene["mode"]!)) }
            // Record the actual source behavior, including the editable busy email field.
            value["providers"]=providers.map(element);value["email"]=element(email);value["request"]=element(request)
            value["keyboardVisible"]=app.keyboards.firstMatch.exists
            value["captureMethod"]="XCUIScreen.main.screenshot"
            let screenshot=XCUIScreen.main.screenshot();let name="signin-"+id
            value["imageSha256"]=SHA256.hash(data:screenshot.pngRepresentation).map{String(format:"%02x",$0)}.joined()
            let image=XCTAttachment(screenshot:screenshot);image.name=name;image.lifetime = .keepAlways;add(image)
            let json=XCTAttachment(data:try JSONSerialization.data(withJSONObject:value,options:[.sortedKeys]),uniformTypeIdentifier:"public.json")
            json.name=name+"-metadata";json.lifetime = .keepAlways;add(json)
            app.terminate()
        }
    }
}
