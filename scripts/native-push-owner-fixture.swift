import Darwin
import Foundation

actor NativeOwnerFixtureGate {
    private var continuation: CheckedContinuation<Void, Never>?
    var began = false
    func prepare() async {
        began = true
        await withCheckedContinuation { continuation = $0 }
    }
    func release() { continuation?.resume(); continuation = nil }
}
final class NativeOwnerFixtureDescriptor: @unchecked Sendable {
    private let lock = NSLock()
    private var bytes: Data
    init(_ bytes: Data) { self.bytes = bytes }
    func read() -> Data { lock.withLock { bytes } }
    func replace(_ bytes: Data) { lock.withLock { self.bytes = bytes } }
}

@main struct NativePushOwnerFixture {
    enum FixtureFailure: Error { case assertion(Int), probe(String) }
    @MainActor static func main() async {
        do { try await run() }
        catch { FileHandle.standardError.write(Data("Native owner fixture failed: \(error)\n".utf8)); exit(1) }
    }
    @MainActor static func run() async throws {
        let args = CommandLine.arguments
        guard args.count == 5 else { fatalError("Fixture arguments") }
        let node = args[1], helper = args[2], probe = args[3], root = args[4]
        func require(_ value: @autoclosure () throws -> Bool, line: Int = #line) throws {
            if try !value() { throw FixtureFailure.assertion(line) }
        }
        func wait(_ predicate: () async -> Bool, line: Int = #line) async throws {
            for _ in 0..<200 {
                if await predicate() { return }
                try await Task.sleep(for: .milliseconds(25))
            }
            throw FixtureFailure.assertion(line)
        }
        func runProbe(_ mode: String) async throws {
            try await Task.detached {
                let process = Process()
                process.executableURL = URL(fileURLWithPath: node); process.arguments = [probe, mode, root]
                process.standardOutput = FileHandle.standardOutput; process.standardError = FileHandle.standardError
                try process.run(); process.waitUntilExit()
                guard process.terminationStatus == 0 else { throw FixtureFailure.probe(mode) }
            }.value
        }
        func json(_ object: [String: Any]) throws -> Data { try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]) }
        func host(hide: Bool = false) throws -> Data {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            return try json(["type": "host", "snapshot": ["state": "ready", "epoch": "fixture-epoch", "observedAt": now, "validUntil": now + 30_000,
                "authority": ["accountID": "fixture-account", "teamID": NSNull(), "macDeviceID": "fixture-mac", "macInstanceTag": "fixture-tag", "macBuildID": "fixture-build"],
                "settings": ["forwardingEnabled": true, "mode": "always", "admission": "allowed", "hideContent": hide]]])
        }
        let descriptor = try json(["version": 1, "algorithm": "rawX25519", "installationID": "fixture-native", "keyID": "fixture-native-key", "publicKey": Data(repeating: 9, count: 32).base64EncodedString()])
        let liveDescriptor = NativeOwnerFixtureDescriptor(descriptor)
        let absent = try NativePushSourceOwner(environment: [:], loadDescriptor: { descriptor }, loadCaptureKey: { _ in Data(repeating: 34, count: 32) })
        try require(absent == nil)
        let nativePath = root + "/native.json"
        var starts = 0, reaped = 0
        guard let owner = try NativePushSourceOwner(environment: ["CMUX_ANDROID_PUSH_CONFIG": root + "/config.json",
            "CMUX_ANDROID_PUSH_NODE": node, "CMUX_ANDROID_PUSH_HELPER": helper], loadDescriptor: { liveDescriptor.read() },
            loadCaptureKey: { _ in Data(repeating: 34, count: 32) }, onState: { phase in
                print("Fixture phase: " + phase)
                if phase == "started" { starts += 1 }; if phase == "reaped" { reaped += 1 }
            }) else { throw NativePushSourceOwner.Failure.invalidConfiguration }
        try require(!FileManager.default.fileExists(atPath: nativePath) && !owner.isRunning)
        try owner.start()
        try require(!FileManager.default.fileExists(atPath: nativePath))
        let expiry = (Int64(Date().timeIntervalSince1970) + 120) * 1000
        func capture(hide: Bool = false) throws -> Data { try json(["type": "capture", "request": ["sourceEpoch": "fixture-epoch", "expiresAt": expiry, "phoneEligible": true,
            "event": ["kind": "notify", "correlationID": UUID().uuidString.lowercased(), "badgeCount": 1, "hideContent": hide,
                "title": "fixture", "subtitle": "", "body": "native owner λ", "replyShape": "none", "retargetsToLiveSurfaceOwner": false,
                "workspaceId": NSNull(), "surfaceId": NSNull(), "notificationId": NSNull()]]]) }
        let scope = NativePushSourceScope(accountID: "fixture-account", teamID: nil, macDeviceID: "fixture-mac",
            macInstanceTag: "fixture-tag", macBuildID: "fixture-build")
        try require(owner.offer([host(), capture()], scope: scope))
        try await wait { owner.isRunning && owner.retainedCount == 0 }
        try await runProbe("offer"); try require(starts == 1)
        try require(owner.offer([host(hide: true), capture(hide: true)], scope: scope))
        try await wait { starts == 2 && owner.isRunning && reaped == 1 && owner.retainedCount == 0 }
        try await runProbe("offer")
        owner.invalidate()
        try await wait { reaped == 2 }
        try require(!owner.isRunning && starts == 2)
        try require(owner.offer([host(hide: true), capture(hide: true)], scope: scope))
        try await wait { starts == 3 && owner.isRunning && owner.retainedCount == 0 }
        try await runProbe("offer")
        let rotated = try json(["version": 1, "algorithm": "rawX25519", "installationID": "fixture-native-rotated",
            "keyID": "fixture-native-key-rotated", "publicKey": Data(repeating: 10, count: 32).base64EncodedString()])
        liveDescriptor.replace(rotated)
        try require(owner.offer([host(hide: true)], scope: scope))
        try await wait { (try? Data(contentsOf: URL(fileURLWithPath: nativePath))) == rotated }
        try require(starts == 3 && owner.isRunning)
        try await runProbe("rotated-offer")
        await owner.stop(); try require(reaped == 3 && !owner.isRunning)
        try await runProbe("verify")

        // Awaited stop also joins cancelled, already-running preparation. It
        // must never launch a helper after that work finally returns.
        let gate = NativeOwnerFixtureGate()
        var preparedStarts = 0, stopRequested = false, stopped = false
        let held = try NativePushSourceProcessSink(executable: URL(fileURLWithPath: node), arguments: [helper, "--config", root + "/config.json"],
            directory: URL(fileURLWithPath: root), prepareChild: { await gate.prepare() }, onState: {
                if $0 == "started" { preparedStarts += 1 }; if $0 == "stopping" { stopRequested = true }
            })
        try require(held.offer([host()])); try held.start()
        try await wait { await gate.began }
        held.invalidate()
        let stopping = Task { @MainActor in await held.stop(); stopped = true }
        try await wait { stopRequested }
        try require(!stopped)
        await gate.release(); await stopping.value
        try require(stopped && preparedStarts == 0 && !held.isRunning)

        // Native descriptor refresh must not postpone independent capture
        // durability. Stop joins refresh and cannot launch a late helper.
        let hostGate = NativeOwnerFixtureGate()
        let ledger = try NativePushCaptureRepository(directory: URL(fileURLWithPath: root + "/held-host-producer"),
            loadKey: { _ in Data(repeating: 35, count: 32) })
        var hostStarts = 0, hostStopRequested = false, hostStopped = false
        let heldHost = try NativePushSourceCaptureController(repositoryFactory: { ledger },
            executable: URL(fileURLWithPath: node), arguments: [helper, "--config", root + "/config.json"],
            directory: URL(fileURLWithPath: root), prepareChild: {}, prepareHost: { await hostGate.prepare() }, onState: {
                if $0 == "started" { hostStarts += 1 }; if $0 == "capture-stopping" { hostStopRequested = true }
            })
        try heldHost.start(); try require(heldHost.offer([host(), capture()], scope: scope))
        try await wait { await hostGate.began }
        let committed = try await ledger.load(now: Int64(Date().timeIntervalSince1970 * 1000))
        try require(committed.count == 1)
        let stopHost = Task { @MainActor in await heldHost.stop(); hostStopped = true }
        try await wait { hostStopRequested }; try require(!hostStopped)
        await hostGate.release(); await stopHost.value
        try require(hostStopped && hostStarts == 0 && !heldHost.isRunning)

        // Repositories cannot publish private fields or follow a target symlink.
        let configURL = URL(fileURLWithPath: root + "/config.json")
        let before = try Data(contentsOf: URL(fileURLWithPath: nativePath))
        let extra = try json(["version": 1, "algorithm": "rawX25519", "installationID": "fixture", "keyID": "fixture", "publicKey": Data(repeating: 9, count: 32).base64EncodedString(), "privateKey": "public-fixture-forbidden-field"])
        let invalid = NativePushOwnerDescriptorRepository(configurationURL: configURL, loadDescriptor: { extra })
        do { try await invalid.publish(); throw NativePushSourceOwner.Failure.invalidConfiguration }
        catch NativePushOwnerDescriptorRepository.Failure.unavailable {}
        try require(try Data(contentsOf: URL(fileURLWithPath: nativePath)) == before)
        try FileManager.default.removeItem(atPath: nativePath)
        try FileManager.default.createSymbolicLink(atPath: nativePath, withDestinationPath: root + "/config.json")
        let safe = NativePushOwnerDescriptorRepository(configurationURL: configURL, loadDescriptor: { descriptor })
        do { try await safe.publish(); throw NativePushSourceOwner.Failure.invalidConfiguration }
        catch NativePushOwnerDescriptorRepository.Failure.unavailable {}
        print("Native owner: passive/lease-gated preparation, public descriptor, actual production helper/capture/offers, privacy reap/replacement, invalidation/recovery, awaited stop, cancelled preparation and publication refusal passed")
    }
}
