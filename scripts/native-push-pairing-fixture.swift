import AppKit
import CryptoKit
import Foundation
import Vision

actor PairingFixtureGate {
    let data: Data
    var held = false
    var entered = false
    var cancelled: [String] = []
    private var continuation: CheckedContinuation<Void, Never>?
    init(_ data: Data) { self.data = data }
    func hold() { held = true; entered = false }
    func release() { held = false; continuation?.resume(); continuation = nil }
    func request(_ action: String, _ value: String) async throws -> Data {
        if action == "cancel" { cancelled.append(value); return Data("{\"cancelled\":true}".utf8) }
        entered = true
        if held { await withCheckedContinuation { continuation = $0 } }
        return data
    }
}

@main struct NativePushPairingFixture {
    enum Failure: Error { case assertion(Int) }
    @MainActor static func main() async {
        do { try await run() }
        catch { FileHandle.standardError.write(Data("Native pairing fixture failed: \(error)\n".utf8)); exit(1) }
    }
    @MainActor static func run() async throws {
        func require(_ condition: @autoclosure () throws -> Bool, line: Int = #line) throws {
            if try !condition() { throw Failure.assertion(line) }
        }
        func wait(_ condition: () async -> Bool, line: Int = #line) async throws {
            for _ in 0..<100 { if await condition() { return }; try await Task.sleep(for: .milliseconds(10)) }
            throw Failure.assertion(line)
        }
        if CommandLine.arguments.count == 3 {
            let mode = CommandLine.arguments[1], client = NativePushPairingClient(path: CommandLine.arguments[2])
            let started = ProcessInfo.processInfo.systemUptime
            let work = Task { try await client.request(action: "offer", value: "io.github.docmorphic.cmuxapp") }
            if mode == "client-cancel" { try await Task.sleep(for: .milliseconds(100)); work.cancel() }
            do { _ = try await work.value; throw Failure.assertion(#line) }
            catch is NativePushPairingClient.Failure { try require(mode == "client-refuse") }
            catch is CancellationError { try require(mode == "client-cancel") }
            try require(ProcessInfo.processInfo.systemUptime - started < 2)
            print("Native socket \(mode) passed; UI actor remained available")
            return
        }
        var time: Int64 = 1_000_000
        let build = "io.github.docmorphic.cmuxapp"
        let body = try JSONSerialization.data(withJSONObject: [
            "version": 1, "offerID": "00000000-0000-4000-8000-000000000001", "expiresAt": time + 120_000,
            "phoneBuildID": build, "endpoint": "https://mac.fixture.example/v1/push/enroll",
            "secret": Data(repeating: 3, count: 32).base64EncodedString().replacingOccurrences(of: "=", with: ""),
            "project": ["project": "fixture-project", "sender": "123456", "application": "fixture-app"],
            "mac": ["accountID": "fixture-account", "teamID": NSNull(), "macDeviceID": "fixture-mac", "macInstanceTag": "fixture-tag", "macBuildID": "fixture-build"],
            "native": ["version": 1, "algorithm": "rawX25519", "installationID": "native", "keyID": "native-key", "publicKey": Data(repeating: 1, count: 32).base64EncodedString()],
            "helper": ["version": 1, "algorithm": "rawX25519", "installationID": "helper", "keyID": "helper-key", "publicKey": Data(repeating: 2, count: 32).base64EncodedString()]
        ], options: [.sortedKeys, .withoutEscapingSlashes])
        let offer = try NativePushPairingOffer(body: body, phoneBuildID: build, now: time)
        let hex = SHA256.hash(data: Data(repeating: 2, count: 32)).map { String(format: "%02X", $0) }.joined()
        try require(offer.fingerprint.replacingOccurrences(of: " ", with: "") == hex)
        try require(offer.description == "NativePushPairingOffer(redacted)")
        for (field, value) in [("expiresAt", time), ("expiresAt", time + 120_001), ("endpoint", "http://mac.fixture.example/v1/push/enroll"),
                               ("endpoint", "https://user@mac.fixture.example/v1/push/enroll"), ("phoneBuildID", "other"),
                               ("secret", "bad"), ("privateKey", "forbidden")] as [(String, Any)] {
            var changed = try JSONSerialization.jsonObject(with: body) as! [String: Any]; changed[field] = value
            let data = try JSONSerialization.data(withJSONObject: changed)
            do { _ = try NativePushPairingOffer(body: data, phoneBuildID: build, now: time); throw Failure.assertion(#line) }
            catch is NativePushPairingClient.Failure {}
        }
        let gate = PairingFixtureGate(body)
        var clears = 0
        let model = NativePushPairingModel(request: { try await gate.request($0, $1) }, now: { time }, onClear: { clears += 1 })
        model.generate(); try await wait { model.offer != nil }
        try require(!model.busy && !model.failed && clears == 1)
        time += 120_000; model.expire(); try require(model.offer == nil && clears == 2)
        try await wait { await gate.cancelled.count == 1 }
        time -= 120_000
        await gate.hold(); model.generate(); try await wait { await gate.entered }
        model.clear(); await gate.release()
        try await wait { await gate.cancelled.count == 2 }
        try require(model.offer == nil && !model.busy && !model.failed)
        let failure = NativePushPairingModel(request: { _, _ in Data("{}".utf8) }, now: { time })
        failure.generate(); try await wait { failure.failed }; try require(!failure.busy && failure.offer == nil)
        print("Pairing offer/fingerprint validation, expiry, private-copy cleanup callback, late-result cancellation and retry error state passed")

        // Decode actual QR pixels at the displayed point size and Retina size.
        _ = NSApplication.shared
        guard let qr = NativePushPairingView.qr(body), let source = qr.cgImage(forProposedRect: nil, context: nil, hints: nil) else { throw Failure.assertion(#line) }
        for pixels in [248, 496] {
            guard let context = CGContext(data: nil, width: pixels, height: pixels, bitsPerComponent: 8, bytesPerRow: pixels * 4,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { throw Failure.assertion(#line) }
            context.interpolationQuality = .none; context.draw(source, in: CGRect(x: 0, y: 0, width: pixels, height: pixels))
            guard let image = context.makeImage() else { throw Failure.assertion(#line) }
            let request = VNDetectBarcodesRequest(); request.symbologies = [.qr]
            try VNImageRequestHandler(cgImage: image).perform([request])
            try require(request.results?.count == 1)
            try require(request.results?.first?.payloadStringValue == String(data: body, encoding: .utf8))
        }
        print("Actual QR pixels decode to the exact private offer at 248 and 496 pixels")
        let board = NSPasteboard(name: .init("cmux-pairing-fixture-" + UUID().uuidString))
        defer { board.releaseGlobally() }
        let clipboard = NativePushPairingClipboard(board: board)
        clipboard.copy(body); try require(board.string(forType: .string) == String(data: body, encoding: .utf8))
        clipboard.clear(); try require(board.string(forType: .string) == nil)
        clipboard.copy(body); board.clearContents(); board.setString("new fixture copy", forType: .string)
        clipboard.clear(); try require(board.string(forType: .string) == "new fixture copy")
        print("Actual isolated pasteboard clears the owned secret and preserves a newer copy; user clipboard untouched")
        let firstMenu = NSMenu(); let firstSubmenu = NSMenu(); let top = NSMenuItem()
        top.submenu = firstSubmenu; firstMenu.addItem(top); NSApp.mainMenu = firstMenu
        let presentation = NativePushPairingPresentation(request: { _, _ in body })
        presentation.installMenu(); let count = firstSubmenu.items.count; presentation.installMenu()
        try require(count == 2 && firstSubmenu.items.count == count)
        let replacement = NSMenu(), second = NSMenu(), replacementTop = NSMenuItem()
        replacementTop.submenu = second; replacement.addItem(replacementTop); NSApp.mainMenu = replacement
        NotificationCenter.default.post(name: NSMenu.didBeginTrackingNotification, object: second)
        try require(second.items.count == 2)
        NotificationCenter.default.post(name: NSMenu.didBeginTrackingNotification, object: second)
        try require(second.items.count == 2)
        // A rebuild may remove items without replacing the NSMenu itself.
        second.removeAllItems()
        NotificationCenter.default.post(name: NSMenu.didBeginTrackingNotification, object: second)
        try require(second.items.count == 2)
        print("Native pairing menu is idempotent and automatically repairs replaced or emptied host menus on tracking")
    }
}
