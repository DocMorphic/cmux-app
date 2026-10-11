import Foundation

@main struct NativeSourceFixture {
    static func main() throws {
        let encoder = try NativePushSourceFrameEncoder(
            sessionID: "00000000-0000-4000-8000-000000000010",
            key: Data(repeating: 41, count: 32))
        let time: Int64 = 1_800_000_000_000
        let host: [String: Any] = ["type": "host", "snapshot": [
            "state": "ready", "epoch": "fixture-epoch", "observedAt": time, "validUntil": time + 30_000,
            "authority": ["accountID": "fixture-account", "teamID": NSNull(), "macDeviceID": "fixture-mac",
                          "macInstanceTag": "stable", "macBuildID": "fixture-build"],
            "settings": ["forwardingEnabled": true, "mode": "always", "admission": "allowed", "hideContent": false]
        ]]
        let alert: [String: Any] = ["type": "capture", "request": [
            "sourceEpoch": "fixture-epoch", "phoneEligible": true, "expiresAt": time + 120_000,
            "event": ["kind": "notify", "correlationID": "00000000-0000-4000-8000-000000000011",
                      "badgeCount": 1, "hideContent": false, "title": "Ready λ 中 👩🏽‍💻", "subtitle": "",
                      "body": "Line one\n\"next\" \\ /", "replyShape": "text", "retargetsToLiveSurfaceOwner": true,
                      "workspaceId": "fixture-workspace", "surfaceId": NSNull(), "notificationId": "fixture-notice"]
        ]]
        let dismissed: [String: Any] = ["type": "capture", "request": [
            "sourceEpoch": "fixture-epoch", "phoneEligible": false, "expiresAt": time + 120_000,
            "event": ["kind": "dismiss", "correlationID": "00000000-0000-4000-8000-000000000012",
                      "badgeCount": 0, "hideContent": false, "notificationIds": ["fixture-notice", "other-中"]]
        ]]
        let frames = try [host, alert, dismissed].enumerated().map { index, body in
            try JSONSerialization.jsonObject(with: encoder.encode(
                body: JSONSerialization.data(withJSONObject: body, options: [.sortedKeys]), sequence: UInt64(index + 1)))
        }
        if CommandLine.arguments.dropFirst().contains("--stream") {
            // Public fixture key only. A real owner provisions a fresh secret over
            // private inherited pipes, never arguments, environment or logs.
            let bootstrap: [String: Any] = ["type": "bootstrap", "version": 1,
                "sessionID": "00000000-0000-4000-8000-000000000010",
                "key": Data(repeating: 41, count: 32).base64EncodedString()]
            for (index, value) in ([bootstrap] + frames).enumerated() {
                var line = try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
                line.append(10); FileHandle.standardOutput.write(line)
                guard let reply = readLine(), let bytes = reply.data(using: .utf8),
                      let receipt = try JSONSerialization.jsonObject(with: bytes) as? [String: Any],
                      receipt["sequence"] as? Int == index,
                      receipt["acknowledged"] as? Bool == true,
                      receipt["kind"] as? String == ["ready", "state-updated", "captured", "captured"][index]
                else { throw NativePushSourceFrameEncoder.Failure.invalidInput }
            }
        } else {
            FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: frames, options: [.prettyPrinted, .sortedKeys]))
        }
    }
}
