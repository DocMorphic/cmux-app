import Darwin
import Foundation

@main struct NativePushCaptureFixture {
    enum Failure: Error { case assertion(Int) }
    static let time: Int64 = 1_800_000_000_000
    static let fixedID = UUID(uuidString: "00000000-0000-4000-8000-000000000111")!
    static let key = Data(repeating: 7, count: 32)
    static func require(_ value: Bool, line: Int = #line) throws {
        if !value { throw Failure.assertion(line) }
    }
    static func record(id: UUID = fixedID, bodyText: String = "private native capture λ", admitted: Int64 = time, expiry: Int64 = time + 120_000) throws -> NativePushCaptureRepository.Record {
        let context = NativePushCaptureRepository.Context(accountID: "fixture-account", teamID: "fixture-team", macDeviceID: "fixture-mac",
            macInstanceTag: "fixture-instance", macBuildID: "fixture-build", sourceEpoch: "original-source-epoch")
        let body = try JSONSerialization.data(withJSONObject: ["type": "capture", "request": ["sourceEpoch": context.sourceEpoch,
            "expiresAt": expiry, "phoneEligible": true, "event": ["kind": "notify", "correlationID": id.uuidString.lowercased(),
                "badgeCount": 1, "hideContent": false, "title": "fixture", "subtitle": "", "body": bodyText,
                "replyShape": "none", "retargetsToLiveSurfaceOwner": false, "workspaceId": NSNull(), "surfaceId": NSNull(),
                "notificationId": NSNull()]]], options: [.sortedKeys])
        return .init(id: id, context: context, admittedAt: admitted, expiresAt: expiry, body: body)
    }
    static func repository(_ path: String, capacity: Int = 128, bytes: Int = 8 * 1_048_576,
                           loadKey: @escaping @Sendable (Bool) throws -> Data = { _ in key }) throws -> NativePushCaptureRepository {
        try NativePushCaptureRepository(directory: URL(fileURLWithPath: path), capacity: capacity, byteCapacity: bytes, loadKey: loadKey)
    }
    static func rejected(_ operation: () async throws -> Void, line: Int = #line) async throws {
        do { try await operation() } catch NativePushCaptureRepository.Failure.unavailable { return }
        throw Failure.assertion(line)
    }
    static func main() async {
        do { try await run() }
        catch { FileHandle.standardError.write(Data("Native capture fixture failed: \(error)\n".utf8)); exit(1) }
    }
    static func run() async throws {
        let args = CommandLine.arguments
        guard args.count == 3 else { throw Failure.assertion(#line) }
        let mode = args[1], root = args[2], path = root + "/producer"
        let repo = try repository(path)
        if mode == "commit-and-die" {
            try require(try await repo.append(record(), now: time) == .stored)
            FileHandle.standardOutput.write(Data("durable capture committed before SIGKILL\n".utf8))
            _ = Darwin.kill(getpid(), SIGKILL)
            throw Failure.assertion(#line)
        }
        guard mode == "verify" else { throw Failure.assertion(#line) }
        let restored = try await repo.load(now: time + 1000)
        try require(restored == [record()])
        let persisted = URL(fileURLWithPath: path + "/captures.sealed")
        let before = try Data(contentsOf: persisted)
        try require(before.range(of: Data("private native capture".utf8)) == nil && before.range(of: Data("fixture-account".utf8)) == nil)
        try require((try FileManager.default.attributesOfItem(atPath: persisted.path)[.posixPermissions] as? NSNumber)?.intValue == 0o600)
        try require((try FileManager.default.attributesOfItem(atPath: path)[.posixPermissions] as? NSNumber)?.intValue == 0o700)
        try require(try await repo.append(record(), now: time + 1000) == .duplicate)
        try require(try Data(contentsOf: persisted) == before)
        try await rejected { _ = try await repo.append(record(bodyText: "conflicting original body"), now: time + 1000) }
        try require(try Data(contentsOf: persisted) == before)
        let wrong = try repository(path, loadKey: { _ in Data(repeating: 8, count: 32) })
        try await rejected { _ = try await wrong.load(now: time + 1000) }
        try require(try Data(contentsOf: persisted) == before)
        let missing = try repository(path, loadKey: { creation in
            if creation { return key }
            throw NativePushCaptureRepository.Failure.unavailable
        })
        try await rejected { _ = try await missing.load(now: time + 1000) }
        try require(try await repo.load(now: time + 120_000) == [])
        try await repo.remove([fixedID], now: time + 1000)
        try require(try await repository(path).load(now: time + 1000) == [])
        print("Recovery after committed SIGKILL, encrypted private storage, exact duplicate/conflict, original expiry and receipt removal passed")

        let passivePath = root + "/passive"
        let passive = try repository(passivePath, loadKey: { _ in throw NativePushCaptureRepository.Failure.unavailable })
        try require(!FileManager.default.fileExists(atPath: passivePath))
        try require(try await passive.load(now: time) == [])
        try require(!FileManager.default.fileExists(atPath: passivePath))
        print("Passive construction/read with no key creation or stored host authority passed")

        let boundedPath = root + "/bounded", bounded = try repository(boundedPath, capacity: 1)
        let first = try record(), second = try record(id: UUID())
        _ = try await bounded.append(first, now: time)
        let boundedFile = URL(fileURLWithPath: boundedPath + "/captures.sealed"), boundedBefore = try Data(contentsOf: boundedFile)
        try await rejected { _ = try await bounded.append(second, now: time) }
        let retained = try await bounded.load(now: time)
        try require(try Data(contentsOf: boundedFile) == boundedBefore && retained == [first])
        try await rejected { _ = try await bounded.append(record(id: second.id, expiry: time + 240_000), now: time + 120_000) }
        let newer = try record(id: second.id, admitted: time + 120_000, expiry: time + 240_000)
        _ = try await bounded.append(newer, now: time + 120_000)
        try require(try await bounded.load(now: time + 120_000) == [newer])
        let tiny = try repository(root + "/tiny", bytes: first.body.count - 1)
        try await rejected { _ = try await tiny.append(first, now: time) }
        try require(try await tiny.load(now: time) == [])
        print("Count/byte bounds, no eviction, expired-slot reuse and no renewed expiry passed")

        let corruptPath = root + "/corrupt", corrupt = try repository(corruptPath)
        _ = try await corrupt.append(first, now: time)
        let corruptFile = URL(fileURLWithPath: corruptPath + "/captures.sealed")
        var ciphertext = try Data(contentsOf: corruptFile); ciphertext[ciphertext.count - 1] ^= 1
        let writer = try FileHandle(forWritingTo: corruptFile); try writer.write(contentsOf: ciphertext); try writer.close()
        try await rejected { _ = try await corrupt.load(now: time) }
        try await rejected { _ = try await corrupt.append(second, now: time) }
        try await rejected { try await corrupt.remove([first.id], now: time) }
        try require(try Data(contentsOf: corruptFile) == ciphertext)
        print("Authenticated corruption and missing/wrong keys fail closed without overwriting evidence passed")

        let movedPath = root + "/moved"
        try FileManager.default.createDirectory(atPath: movedPath, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        let movedFile = URL(fileURLWithPath: movedPath + "/captures.sealed")
        try boundedBefore.write(to: movedFile)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: movedFile.path)
        let moved = try repository(movedPath)
        try await rejected { _ = try await moved.load(now: time) }
        try require(try Data(contentsOf: movedFile) == boundedBefore)
        print("Directory-bound authenticated ciphertext transplant refusal passed")

        let unsafePath = root + "/unsafe", unsafe = try repository(unsafePath)
        _ = try await unsafe.append(first, now: time)
        let unsafeFile = URL(fileURLWithPath: unsafePath + "/captures.sealed")
        let safeBytes = try Data(contentsOf: unsafeFile)
        try FileManager.default.setAttributes([.posixPermissions: 0o644], ofItemAtPath: unsafeFile.path)
        try await rejected { _ = try await unsafe.load(now: time) }
        try await rejected { _ = try await unsafe.append(second, now: time) }
        try require(try Data(contentsOf: unsafeFile) == safeBytes)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: unsafeFile.path)
        try FileManager.default.linkItem(atPath: unsafeFile.path, toPath: root + "/hardlink")
        try await rejected { _ = try await unsafe.load(now: time) }
        try FileManager.default.removeItem(atPath: root + "/hardlink")
        try FileManager.default.removeItem(atPath: unsafeFile.path)
        try FileManager.default.createSymbolicLink(atPath: unsafeFile.path, withDestinationPath: movedFile.path)
        try await rejected { _ = try await unsafe.append(second, now: time) }
        try require(try Data(contentsOf: movedFile) == boundedBefore)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: unsafePath)
        try await rejected { _ = try await unsafe.load(now: time) }
        print("Private file/directory modes, hard links and symlink refusal passed")

        let invalid = try repository(root + "/invalid")
        let mismatched = NativePushCaptureRepository.Record(id: first.id, context: first.context, admittedAt: first.admittedAt,
            expiresAt: first.expiresAt + 1000, body: first.body)
        try await rejected { _ = try await invalid.append(mismatched, now: time) }
        let control = NativePushCaptureRepository.Record(id: first.id, context: first.context, admittedAt: first.admittedAt,
            expiresAt: first.expiresAt, body: Data("{\"type\":\"host\",\"snapshot\":{}}".utf8))
        try await rejected { _ = try await invalid.append(control, now: time) }
        try require(!FileManager.default.fileExists(atPath: root + "/invalid"))
        print("Original capture/epoch/expiry validation and refusal to persist host controls passed")
    }
}
