import Foundation
import Darwin

@main struct NativePushProcessFixture {
    @MainActor static func main() async throws {
        let args = CommandLine.arguments
        guard args.count == 5 else { fatalError("fixture arguments") }
        let directory = URL(fileURLWithPath: args[3])
        let marker = args[4]
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let epoch = UUID().uuidString.lowercased()
        func bytes(_ object: [String: Any]) throws -> Data {
            try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        }
        func host(hide: Bool = false) throws -> Data {
            let clock = Int64(Date().timeIntervalSince1970 * 1000)
            return try bytes(["type": "host", "snapshot": ["state": "ready", "epoch": epoch,
                "observedAt": clock, "validUntil": clock + 30_000,
                "authority": ["accountID": "fixture-account", "teamID": NSNull(), "macDeviceID": "fixture-mac",
                    "macInstanceTag": "stable", "macBuildID": "fixture-build"],
                "settings": ["forwardingEnabled": true, "hideContent": hide, "mode": "always", "admission": "allowed"]]])
        }
        let expiry = (now / 1000 + 120) * 1000
        let event = try bytes(["type": "capture", "request": ["sourceEpoch": epoch, "expiresAt": expiry,
            "phoneEligible": true, "event": ["kind": "notify", "correlationID": UUID().uuidString.lowercased(),
                "badgeCount": 1, "hideContent": false, "title": "fixture", "subtitle": "", "body": "actual process λ",
                "replyShape": "none", "retargetsToLiveSurfaceOwner": false,
                "workspaceId": NSNull(), "surfaceId": NSNull(), "notificationId": NSNull()]]])
        func require(_ value: @autoclosure () throws -> Bool) throws {
            guard try value() else { throw NativePushSourceProcessSink.Failure.unavailable }
        }
        func wait(_ predicate: () -> Bool) async throws {
            for _ in 0..<200 {
                if predicate() { return }
                try await Task.sleep(for: .milliseconds(25))
            }
            throw NativePushSourceProcessSink.Failure.unavailable
        }
        var starts = 0, reaped = 0
        let sink = try NativePushSourceProcessSink(executable: URL(fileURLWithPath: args[1]),
            arguments: [args[2], directory.path, "normal", marker], directory: URL(fileURLWithPath: args[2]).deletingLastPathComponent(),
            receiptDelay: .seconds(1), retryDelay: .milliseconds(50), onState: { phase in
                if phase == "started" { starts += 1 }; if phase == "reaped" { reaped += 1 }
            })
        try require(!sink.isRunning)
        try require(sink.offer([host(), event]))
        try require(!sink.isRunning && sink.retainedCount == 1)
        try sink.start()
        try await wait { sink.retainedCount == 0 }
        try require(sink.isRunning && starts == 1)
        let captured = try String(contentsOfFile: marker, encoding: .utf8)
        try require(captured.contains("actual process λ") && captured.contains(String(expiry)))
        try require(sink.offer([host(hide: true)]))
        try await wait { starts == 2 && sink.isRunning }
        try require(reaped == 1)
        sink.invalidate()
        try require(!sink.isRunning)
        try await wait { reaped == 2 }
        try require(starts == 2)
        try require(sink.offer([host(hide: true)]))
        try await wait { starts == 3 && sink.isRunning }
        await sink.stop()
        try require(!sink.isRunning && reaped == 3)
        try require(try String(contentsOfFile: marker, encoding: .utf8).split(separator: "\n").count == 1)

        for mode in ["malformed", "stall"] {
            var failed = false, closed = false
            let bad = try NativePushSourceProcessSink(executable: URL(fileURLWithPath: args[1]),
                arguments: [args[2], directory.path, mode, marker], directory: URL(fileURLWithPath: args[2]).deletingLastPathComponent(),
                receiptDelay: .milliseconds(300), retryDelay: .milliseconds(50), onState: { phase in
                    if phase == (mode == "malformed" ? "receipt-rejected" : "bootstrap-timeout") { failed = true }
                    if phase == "reaped" { closed = true }
                })
            try require(bad.offer([host(), event]))
            try bad.start()
            try await wait { failed && closed }
            try require(!bad.isRunning && bad.retainedCount == 1)
            await bad.stop()
        }
        try FileManager.default.removeItem(atPath: marker + ".pid")
        var disposable: NativePushSourceProcessSink? = try NativePushSourceProcessSink(
            executable: URL(fileURLWithPath: args[1]), arguments: [args[2], directory.path, "normal", marker],
            directory: URL(fileURLWithPath: args[2]).deletingLastPathComponent())
        try require(disposable!.offer([host()]))
        try disposable!.start()
        try await wait { FileManager.default.fileExists(atPath: marker + ".pid") }
        let pid = Int32(try String(contentsOfFile: marker + ".pid", encoding: .utf8))!
        weak var released = disposable
        disposable = nil
        try await wait { released == nil && Darwin.kill(pid, 0) != 0 && errno == ESRCH }
        print("Native process supervisor: passive startup, real durable capture, privacy retirement/reap/restart, fresh-authority recovery, stop, oversized receipt, stalled bootstrap and owner-release cleanup passed")
    }
}
