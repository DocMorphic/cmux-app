import Foundation

@main struct NativePushDeliveryQueueFixture {
    @MainActor static func main() throws {
        let time: Int64 = 1_800_000_000_000
        let session = "00000000-0000-4000-8000-000000000010"
        let key = Data(repeating: 41, count: 32)
        func data(_ value: [String: Any]) throws -> Data {
            try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
        }
        func object(_ data: Data) throws -> [String: Any] {
            try JSONSerialization.jsonObject(with: data) as! [String: Any]
        }
        func host(_ epoch: String = "fixture-epoch", at: Int64 = time, hide: Bool = false,
                  enabled: Bool = true, admission: String = "allowed") throws -> Data {
            try data(["type": "host", "snapshot": ["state": "ready", "epoch": epoch,
                "observedAt": at, "validUntil": at + 30_000,
                "authority": ["accountID": "fixture-account", "teamID": NSNull(), "macDeviceID": "fixture-mac",
                    "macInstanceTag": "stable", "macBuildID": "fixture-build"],
                "settings": ["forwardingEnabled": enabled, "hideContent": hide,
                    "mode": "onlyWhenAway", "admission": admission]]])
        }
        func capture(_ id: String = "00000000-0000-4000-8000-000000000011", expiry: Int64 = time + 120_000,
                     epoch: String = "fixture-epoch") throws -> Data {
            try data(["type": "capture", "request": ["sourceEpoch": epoch, "expiresAt": expiry,
                "phoneEligible": true, "event": ["kind": "notify", "correlationID": id, "badgeCount": 1,
                    "hideContent": false, "title": "Ready λ 中 👩🏽‍💻", "subtitle": "", "body": "literal\n\"line\"",
                    "replyShape": "none", "retargetsToLiveSurfaceOwner": true,
                    "workspaceId": "fixture-workspace", "surfaceId": NSNull(), "notificationId": "fixture-notice"]]])
        }
        func receipt(_ sequence: Int, _ kind: String, _ ack: Bool = true) throws -> Data {
            try data(["sequence": sequence, "kind": kind, "acknowledged": ack])
        }
        func require(_ condition: @autoclosure () throws -> Bool) throws {
            guard try condition() else { throw NativePushSourceDeliveryQueue.Failure.invalidInput }
        }
        func expectFailure(_ action: () throws -> Void) throws {
            do { try action() } catch { return }
            throw NativePushSourceDeliveryQueue.Failure.invalidInput
        }
        func ready(_ queue: NativePushSourceDeliveryQueue) throws {
            try queue.begin(sessionID: session, key: key, now: time)
            _ = try queue.next(now: time)
            try require(queue.receive(receipt(0, "ready")) == .advance)
            _ = try queue.next(now: time)
            try require(queue.receive(receipt(1, "state-updated")) == .advance)
        }
        var passed: [String] = []
        let queue = try NativePushSourceDeliveryQueue()
        let original = try capture()
        try require(queue.offer([host(), original], now: time) == .accepted)
        try ready(queue)
        let wire = try queue.next(now: time)!
        try require(queue.next(now: time + 120_001) == wire)
        try require(queue.retainedCount == 1)
        try require(queue.receive(receipt(2, "captured")) == .advance)
        try require(queue.retainedCount == 0)
        try require(queue.receive(receipt(2, "captured")) == .duplicate)
        passed.append("uncertain receipt preserves exact frame beyond expiry; durable capture releases once")

        let retry = try NativePushSourceDeliveryQueue()
        try require(retry.offer([host(), original], now: time) == .accepted)
        try ready(retry)
        let attempt = try retry.next(now: time)!
        try require(retry.receive(receipt(2, "storage-unavailable", false)) == .retryLater)
        try require(retry.retainedCount == 1)
        try require(retry.offer([host(at: time + 10_000)], now: time + 10_000) == .accepted)
        let refresh = try retry.next(now: time + 10_000)!
        try require(object(refresh)["sequence"] as? Int == 2)
        try require(retry.receive(receipt(2, "state-updated")) == .advance)
        let retried = try retry.next(now: time + 10_000)!
        try require(object(retried)["sequence"] as? Int == 3)
        try require(object(retried)["body"] as? String == object(attempt)["body"] as? String)
        try require(retry.receive(receipt(3, "duplicate")) == .advance)
        passed.append("explicit nonack permits fresh host at uncommitted sequence and unchanged capture")

        let atomic = try NativePushSourceDeliveryQueue(capacity: 1)
        try require(atomic.offer([host(), original], now: time) == .accepted)
        try require(atomic.offer([host("other-epoch"), capture(epoch: "other-epoch")], now: time) == .rejected)
        try require(atomic.retainedCount == 1)
        try atomic.begin(sessionID: session, key: key, now: time)
        _ = try atomic.receive(receipt(0, "ready"))
        let retainedHost = try object(try atomic.next(now: time)!)
        try require(retainedHost["body"] as? String == host().base64EncodedString())
        _ = try atomic.receive(receipt(1, "state-updated"))
        let retainedCapture = try object(try atomic.next(now: time)!)
        try require(retainedCapture["body"] as? String == original.base64EncodedString())
        passed.append("capacity rejection retains neither new control nor capture prefix")

        let bytes = try NativePushSourceDeliveryQueue(byteCapacity: original.count - 1)
        try require(bytes.offer([host(), original], now: time) == .rejected)
        try require(bytes.retainedCount == 0)
        try expectFailure { try bytes.begin(sessionID: session, key: key, now: time) }
        passed.append("byte bound rejects whole batch without setting host")

        let unknown = try NativePushSourceDeliveryQueue()
        try require(unknown.offer([host(), original], now: time) == .accepted)
        try ready(unknown)
        _ = try unknown.next(now: time)
        try require(unknown.offer([data(["type": "invalidate"])], now: time) == .retireTransport)
        try require(!unknown.hasSession && unknown.retainedCount == 1)
        try expectFailure { try unknown.begin(sessionID: session, key: key, now: time) }
        try require(unknown.offer([host(at: time + 10_000)], now: time + 10_000) == .accepted)
        try unknown.begin(sessionID: session, key: key, now: time + 10_000)
        try require(unknown.receive(receipt(0, "ready")) == .advance)
        let recovered = try unknown.next(now: time + 10_000)!
        let recoveredBody = Data(base64Encoded: try object(recovered)["body"] as! String)!
        try require(recoveredBody == host(at: time + 10_000))
        try require(unknown.receive(receipt(1, "state-updated")) == .advance)
        try require(object(try unknown.next(now: time + 10_000)!)["body"] as? String == original.base64EncodedString())
        passed.append("unknown/disconnect requires refreshed authority before unchanged capture replay")

        for change in [try host("team-b"), try host(hide: true), try host(enabled: false)] {
            let fenced = try NativePushSourceDeliveryQueue()
            try require(fenced.offer([host(), original], now: time) == .accepted)
            try ready(fenced)
            _ = try fenced.next(now: time)
            try require(fenced.offer([change], now: time) == .retireTransport)
            try require(!fenced.hasSession && fenced.retainedCount == 1)
            try fenced.begin(sessionID: session, key: key, now: time)
            _ = try fenced.receive(receipt(0, "ready"))
            let fresh = try object(try fenced.next(now: time)!)
            try require(fresh["body"] as? String == change.base64EncodedString())
        }
        passed.append("identity change, privacy tightening and master disable fence transport and stale controls")

        let presence = try NativePushSourceDeliveryQueue()
        try require(presence.offer([host(), original], now: time) == .accepted)
        try ready(presence)
        let inFlight = try presence.next(now: time)!
        try require(presence.offer([host(admission: "suppressed_mac_active")], now: time) == .accepted)
        try require(presence.hasSession && presence.next(now: time) == inFlight)
        _ = try presence.receive(receipt(2, "captured"))
        let newest = try presence.next(now: time)!
        try require(object(newest)["body"] as? String == host(admission: "suppressed_mac_active").base64EncodedString())
        passed.append("presence changes preserve in-flight native admission and coalesce latest control")

        let expiry = try NativePushSourceDeliveryQueue()
        try require(expiry.offer([host(), capture(expiry: time)], now: time) == .accepted)
        try require(expiry.retainedCount == 0)
        try ready(expiry)
        try require(expiry.next(now: time) == nil)
        expiry.invalidate()
        try require(expiry.offer([host(at: time + 1)], now: time) == .rejected)
        try require(expiry.offer([host(at: time - 30_000)], now: time) == .rejected)
        passed.append("expired events are not renewed; future/expired host observations cannot authorize")

        let hostile = try NativePushSourceDeliveryQueue()
        try require(hostile.offer([host(), original], now: time) == .accepted)
        try hostile.begin(sessionID: session, key: key, now: time)
        let bootstrap = try hostile.next(now: time)!
        for bad in [try receipt(1, "ready"), try receipt(0, "captured"), try receipt(0, "ready", false),
                    try data(["sequence": false, "kind": "ready", "acknowledged": true]),
                    try data(["sequence": 0, "kind": "ready", "acknowledged": 1]),
                    Data(repeating: 32, count: 513)] {
            try expectFailure { _ = try hostile.receive(bad) }
            try require(hostile.next(now: time) == bootstrap && hostile.retainedCount == 1)
        }
        passed.append("invalid receipts cannot advance sequence or release retained captures")

        for terminal in ["expired", "retired"] {
            let stopped = try NativePushSourceDeliveryQueue()
            try require(stopped.offer([host(), original], now: time) == .accepted)
            try ready(stopped)
            _ = try stopped.next(now: time)
            let signedOut = try data(["type": "host", "snapshot": ["state": "signed-out",
                "observedAt": time, "validUntil": time + 30_000]])
            try require(stopped.offer([signedOut], now: time) == .retireTransport)
            try stopped.begin(sessionID: session, key: key, now: time)
            _ = try stopped.receive(receipt(0, "ready"))
            try require(object(try stopped.next(now: time)!)["body"] as? String == signedOut.base64EncodedString())
            _ = try stopped.receive(receipt(1, "state-updated"))
            _ = try stopped.next(now: time)
            try require(stopped.receive(receipt(2, terminal)) == .advance && stopped.retainedCount == 0)
        }
        passed.append("verified sign-out replaces stale host; expired/retired receipts release original captures")

        // Real codec transcript consumed separately by the JS adapter/journal.
        let interop = try NativePushSourceDeliveryQueue()
        try require(interop.offer([host(), original], now: time) == .accepted)
        try interop.begin(sessionID: session, key: key, now: time)
        var frames: [[String: Any]] = []
        frames.append(try object(try interop.next(now: time)!)); _ = try interop.receive(receipt(0, "ready"))
        frames.append(try object(try interop.next(now: time)!)); _ = try interop.receive(receipt(1, "state-updated"))
        frames.append(try object(try interop.next(now: time)!))
        _ = try interop.receive(receipt(2, "source-unavailable", false))
        try require(interop.offer([host(at: time + 10_000)], now: time + 10_000) == .accepted)
        frames.append(try object(try interop.next(now: time + 10_000)!))
        _ = try interop.receive(receipt(2, "state-updated"))
        frames.append(try object(try interop.next(now: time + 10_000)!))
        if CommandLine.arguments.dropFirst().contains("--stream") {
            let live = try NativePushSourceDeliveryQueue()
            try require(live.offer([host(), original], now: time) == .accepted)
            try live.begin(sessionID: session, key: key, now: time)
            var now = time
            var receipts = 0
            while let message = try live.next(now: now) {
                var line = message
                line.append(10)
                try FileHandle.standardOutput.write(contentsOf: line)
                guard let reply = readLine(), let bytes = reply.data(using: .utf8) else {
                    throw NativePushSourceDeliveryQueue.Failure.unavailable
                }
                let action = try live.receive(bytes)
                receipts += 1
                if action == .retryLater {
                    now = time + 31_000
                    try require(live.offer([host(at: now)], now: now) == .accepted)
                }
                if live.retainedCount == 0 {
                    try require(receipts == 5)
                    return
                }
            }
            throw NativePushSourceDeliveryQueue.Failure.unavailable
        }
        FileHandle.standardOutput.write(try data(["checks": passed, "frames": frames]))
    }
}
