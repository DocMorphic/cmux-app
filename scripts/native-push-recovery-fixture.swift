import Darwin
import Foundation

actor NativeRecoveryGate {
    var began = false
    private var continuation: CheckedContinuation<Void, Never>?
    func hold() async { began = true; await withCheckedContinuation { continuation = $0 } }
    func release() { continuation?.resume(); continuation = nil }
}

@main struct NativePushRecoveryFixture {
    enum Failure: Error { case assertion(Int) }
    static let captureKey = Data(repeating: 34, count: 32)
    static let correlation = "00000000-0000-4000-8000-000000000222"
    @MainActor static func main() async {
        do { try await run() }
        catch { FileHandle.standardError.write(Data("Native recovery fixture failed: \(error)\n".utf8)); exit(1) }
    }
    @MainActor static func run() async throws {
        let args = CommandLine.arguments
        guard args.count == 5 else { throw Failure.assertion(#line) }
        let mode = args[1], node = args[2], helper = args[3], root = args[4]
        func require(_ value: Bool, line: Int = #line) throws { if !value { throw Failure.assertion(line) } }
        func wait(_ condition: () async -> Bool, line: Int = #line) async throws {
            for _ in 0..<800 {
                if await condition() { return }
                try await Task.sleep(for: .milliseconds(25))
            }
            throw Failure.assertion(line)
        }
        func json(_ object: [String: Any]) throws -> Data { try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]) }
        func scope(_ team: String = "fixture-team-a") -> NativePushSourceScope {
            .init(accountID: "fixture-account", teamID: team, macDeviceID: "fixture-mac", macInstanceTag: "fixture-tag", macBuildID: "fixture-build")
        }
        func host(_ epoch: String) throws -> Data {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            return try json(["type": "host", "snapshot": ["state": "ready", "epoch": epoch, "observedAt": now, "validUntil": now + 30_000,
                "authority": ["accountID": "fixture-account", "teamID": NSNull(), "macDeviceID": "fixture-mac", "macInstanceTag": "fixture-tag", "macBuildID": "fixture-build"],
                "settings": ["forwardingEnabled": true, "mode": "always", "admission": "allowed", "hideContent": false]]])
        }
        func capture(_ epoch: String, correlationID: String = correlation) throws -> Data {
            let expiry = (Int64(Date().timeIntervalSince1970) + 120) * 1000
            return try json(["type": "capture", "request": ["sourceEpoch": epoch, "expiresAt": expiry, "phoneEligible": true,
                "event": ["kind": "notify", "correlationID": correlationID, "badgeCount": 1, "hideContent": false,
                    "title": "fixture", "subtitle": "", "body": "original recovery λ", "replyShape": "none",
                    "retargetsToLiveSurfaceOwner": false, "workspaceId": NSNull(), "surfaceId": NSNull(), "notificationId": NSNull()]]])
        }
        let descriptor = try json(["version": 1, "algorithm": "rawX25519", "installationID": "fixture-native",
            "keyID": "fixture-native-key", "publicKey": Data(repeating: 9, count: 32).base64EncodedString()])
        var receipts = 0, commits = 0
        guard let owner = try NativePushSourceOwner(environment: ["CMUX_ANDROID_PUSH_CONFIG": root + "/config.json",
            "CMUX_ANDROID_PUSH_NODE": node, "CMUX_ANDROID_PUSH_HELPER": helper], loadDescriptor: { descriptor }, loadCaptureKey: { _ in captureKey },
            onState: { phase in if phase == "capture-receipt-durable" { receipts += 1 }; if phase == "capture-committed" { commits += 1 } }) else {
            throw Failure.assertion(#line)
        }
        let repo = try NativePushCaptureRepository(directory: URL(fileURLWithPath: root + "/state/producer"), loadKey: { _ in captureKey })
        try owner.start()
        if mode == "commit-and-die" || mode == "scope-aba" {
            try require(owner.offer([host("native-incarnation-first"), capture("native-incarnation-first")], scope: scope()))
            try await wait { FileManager.default.fileExists(atPath: root + "/committed-receipt-withheld") && owner.isRunning && commits == 1 }
            try require(receipts == 0 && owner.retainedCount == 1)
            let records = try await repo.load(now: Int64(Date().timeIntervalSince1970 * 1000))
            try require(records.count == 1 && records[0].context.sourceEpoch == "native-incarnation-first")
            try records[0].body.write(to: URL(fileURLWithPath: root + "/original-public-fixture.json"))
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: root + "/original-public-fixture.json")
            if mode == "commit-and-die" {
                FileHandle.standardOutput.write(Data("actual helper committed; native encrypted original retained before SIGKILL\n".utf8))
                _ = Darwin.kill(getpid(), SIGKILL); throw Failure.assertion(#line)
            }
            owner.retireScope()
            try require(!owner.isRunning)
            try require(owner.offer([host("native-incarnation-team-b")], scope: scope("fixture-team-b")))
            try await wait { owner.isRunning && owner.retainedCount == 0 }
            try require(owner.offer([host("native-incarnation-team-a-again")], scope: scope()))
            try await wait { owner.isRunning && owner.retainedCount == 0 }
            await owner.stop()
            try require(try await repo.load(now: Int64(Date().timeIntervalSince1970 * 1000)).isEmpty)
            try require(receipts == 0)
            print("Confirmed selected-team A→B→A retirement clears native originals without replaying the old source epoch")
            return
        }
        let original = try Data(contentsOf: URL(fileURLWithPath: root + "/original-public-fixture.json"))
        let saved = try await repo.load(now: Int64(Date().timeIntervalSince1970 * 1000))
        try require(saved.count == 1 && saved[0].body == original)
        try require(!owner.isRunning)
        if mode == "held-stop" || mode == "held-team-hop" {
            let gate = NativeRecoveryGate(), descriptorRepo = NativePushOwnerDescriptorRepository(
                configurationURL: URL(fileURLWithPath: root + "/config.json"), loadDescriptor: { descriptor })
            var stopRequested = false, stopped = false, heldReceipts = 0
            let controller = try NativePushSourceCaptureController(repositoryFactory: { await gate.hold(); return repo },
                executable: URL(fileURLWithPath: node), arguments: [helper, "--config", root + "/config.json"],
                directory: URL(fileURLWithPath: root), prepareChild: { try await descriptorRepo.publish() }, onState: {
                    if $0 == "capture-stopping" { stopRequested = true }; if $0 == "capture-receipt-durable" { heldReceipts += 1 }
                })
            try require(controller.offer([host("native-held-first")], scope: scope())); try controller.start()
            try await wait { await gate.began }
            if mode == "held-stop" {
                let stopping = Task { @MainActor in await controller.stop(); stopped = true }
                try await wait { stopRequested }
                try require(!stopped && !controller.isRunning)
                await gate.release(); await stopping.value
                try require(stopped && !controller.isRunning && heldReceipts == 0)
                try require(try await repo.load(now: Int64(Date().timeIntervalSince1970 * 1000)) == saved)
                print("Stop joins held storage startup, preserves original capture and prevents late helper launch")
            } else {
                controller.retireScope()
                try require(controller.offer([host("native-held-team-b")], scope: scope("fixture-team-b")))
                await gate.release()
                try await wait { controller.isRunning && controller.retainedCount == 0 }
                await controller.stop()
                let remaining = try await repo.load(now: Int64(Date().timeIntervalSince1970 * 1000))
                try require(heldReceipts == 0 && remaining.isEmpty)
                print("Selected-team transition during held restore retires stale loaded captures before helper launch")
            }
            await owner.stop(); return
        }
        if mode == "full-store" {
            let first = saved[0]
            for _ in 0..<127 {
                let id = UUID()
                var message = try JSONSerialization.jsonObject(with: first.body) as! [String: Any]
                var request = message["request"] as! [String: Any], event = request["event"] as! [String: Any]
                event["correlationID"] = id.uuidString.lowercased(); request["event"] = event; message["request"] = request
                _ = try await repo.append(.init(id: id, context: first.context, admittedAt: first.admittedAt,
                    expiresAt: first.expiresAt, body: json(message)), now: Int64(Date().timeIntervalSince1970 * 1000))
            }
            let nextCorrelation = UUID().uuidString.lowercased()
            try require(owner.offer([host("native-full-new"), capture("native-full-new", correlationID: nextCorrelation)], scope: scope()))
            try await wait { receipts == 128 && owner.retainedCount == 1 }
            await owner.stop()
            let pending = try await repo.load(now: Int64(Date().timeIntervalSince1970 * 1000))
            try require(pending.count == 1 && pending[0].context.sourceEpoch == "native-incarnation-first")
            let request = (try JSONSerialization.jsonObject(with: pending[0].body) as! [String: Any])["request"] as! [String: Any]
            try require((request["event"] as! [String: Any])["correlationID"] as? String == nextCorrelation)
            print("128 restored originals drain before pending admission; full helper backpressure leaves the new original durably retained")
            return
        }
        if mode == "restore-after-unavailable" {
            try require(owner.offer([json(["type": "invalidate"])]))
            let remaining = try await repo.load(now: Int64(Date().timeIntervalSince1970 * 1000))
            try require(!owner.isRunning && remaining == saved)
        }
        let targetScope = mode == "restore-other-team" ? scope("fixture-team-b") : scope()
        try require(owner.offer([host("native-incarnation-second")], scope: targetScope))
        try await wait { owner.isRunning && owner.retainedCount == 0 }
        await owner.stop()
        try require(try await repo.load(now: Int64(Date().timeIntervalSince1970 * 1000)).isEmpty)
        try require(commits == 0)
        if mode == "restore-other-team" {
            try require(receipts == 0)
            print("Fresh selected-team mismatch retires stored captures despite matching account-scoped wire tuple")
        } else {
            try require(["restore", "restore-after-unavailable"].contains(mode) && receipts == 1)
            print("New native owner matched fresh scope, replayed exact original source/body/expiry and removed storage after real duplicate receipt")
        }
    }
}
