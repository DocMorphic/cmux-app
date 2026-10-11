import CoreFoundation
import CryptoKit
import Darwin
import Foundation

/// Encrypted native captures awaiting a durable helper receipt. Stored context
/// describes original admission; it must never be used as a live host lease.
/// All file/key work stays in this actor, outside the main actor.
actor NativePushCaptureRepository {
    enum Failure: Error { case unavailable }
    enum Admission: Equatable { case stored, duplicate }
    struct Context: Codable, Equatable, Sendable {
        let accountID: String
        let teamID: String?
        let macDeviceID: String
        let macInstanceTag: String
        let macBuildID: String
        let sourceEpoch: String
    }
    struct Record: Codable, Equatable, Sendable {
        let id: UUID
        let context: Context
        let admittedAt: Int64
        let expiresAt: Int64
        let body: Data
    }
    private struct Snapshot: Codable {
        let version: Int
        let records: [Record]
    }
    private let directory: URL
    private let loadKey: @Sendable (_ allowCreation: Bool) throws -> Data
    private let capacity: Int
    private let byteCapacity: Int
    private let maximumFileBytes = 12 * 1_048_576
    private var file: URL { directory.appendingPathComponent("captures.sealed") }
    private var aad: Data { Data(("cmux-app.native.capture.v1|" + directory.path).utf8) }

    init(directory: URL, capacity: Int = 128, byteCapacity: Int = 8 * 1_048_576,
         loadKey: @escaping @Sendable (_ allowCreation: Bool) throws -> Data) throws {
        guard directory.isFileURL, directory.path.hasPrefix("/"),
              (1...128).contains(capacity), (1...8 * 1_048_576).contains(byteCapacity) else { throw Failure.unavailable }
        self.directory = directory; self.capacity = capacity; self.byteCapacity = byteCapacity; self.loadKey = loadKey
    }

    /// Returns unexpired original captures only. No host/session/transport state
    /// is restored, no key is created on read, and corrupt storage is preserved.
    func load(now: Int64) throws -> [Record] {
        do {
            try validTime(now)
            guard try prepareDirectory(create: false) else { return [] }
            guard let bytes = try read() else { return [] }
            let key = try key(allowCreation: false)
            return try decode(bytes, key: key).filter { $0.expiresAt > now }
        } catch { throw Failure.unavailable }
    }

    /// Commit completes only after private atomic replacement and fsync. An
    /// exact duplicate keeps its original body/expiry; conflicting IDs fail.
    func append(_ record: Record, now: Int64) throws -> Admission {
        do {
            try validTime(now); try validate(record)
            guard record.admittedAt <= now, record.expiresAt > now else { throw Failure.unavailable }
            _ = try prepareDirectory(create: true)
            let bytes = try read(), key = try key(allowCreation: bytes == nil)
            let prior = try bytes.map { try decode($0, key: key) } ?? []
            if let same = prior.first(where: { $0.id == record.id }) {
                guard same == record else { throw Failure.unavailable }
                return .duplicate
            }
            var records = prior.filter { $0.expiresAt > now }
            records.append(record); try validate(records)
            try write(records, key: key)
            return .stored
        } catch { throw Failure.unavailable }
    }

    /// Called with the exact capture IDs whose helper receipt was durable, or
    /// whose native account/policy owner explicitly retired them.
    func remove(_ ids: Set<UUID>, now: Int64) throws {
        do {
            try validTime(now)
            guard ids.count <= capacity else { throw Failure.unavailable }
            guard try prepareDirectory(create: false), let bytes = try read() else { return }
            let key = try key(allowCreation: false)
            let prior = try decode(bytes, key: key)
            let records = prior.filter { !ids.contains($0.id) && $0.expiresAt > now }
            if records != prior { try write(records, key: key) }
        } catch { throw Failure.unavailable }
    }

    private func key(allowCreation: Bool) throws -> SymmetricKey {
        var bytes = try loadKey(allowCreation)
        defer { bytes.resetBytes(in: 0..<bytes.count) }
        guard bytes.count == 32 else { throw Failure.unavailable }
        return SymmetricKey(data: bytes)
    }
    private func validTime(_ value: Int64) throws {
        guard (0...9_007_199_254_740_991).contains(value) else { throw Failure.unavailable }
    }
    private func validate(_ record: Record) throws {
        try validTime(record.admittedAt); try validTime(record.expiresAt)
        let c = record.context
        guard [c.accountID, c.macDeviceID, c.macInstanceTag, c.macBuildID, c.sourceEpoch].allSatisfy(validText),
              c.teamID == nil || validText(c.teamID!), record.expiresAt > record.admittedAt,
              record.expiresAt - record.admittedAt <= 120_000, record.expiresAt % 1000 == 0,
              !record.body.isEmpty, record.body.count <= 1_048_576,
              String(data: record.body, encoding: .utf8) != nil,
              let object = try JSONSerialization.jsonObject(with: record.body) as? [String: Any],
              object.count == 2, object["type"] as? String == "capture",
              let request = object["request"] as? [String: Any], request.count == 4,
              let eligible = request["phoneEligible"] as? NSNumber, CFGetTypeID(eligible) == CFBooleanGetTypeID(),
              let event = request["event"] as? [String: Any],
              ["notify", "dismiss"].contains(event["kind"] as? String ?? ""),
              let correlation = event["correlationID"] as? String, UUID(uuidString: correlation) != nil,
              request["sourceEpoch"] as? String == c.sourceEpoch,
              let expiry = request["expiresAt"] as? NSNumber,
              CFGetTypeID(expiry) != CFBooleanGetTypeID(),
              expiry.doubleValue == Double(record.expiresAt) else { throw Failure.unavailable }
    }
    private func validText(_ value: String) -> Bool {
        !value.isEmpty && value.utf16.count <= 1024 && !value.unicodeScalars.contains { $0.value < 32 }
    }
    private func validate(_ records: [Record]) throws {
        guard records.count <= capacity, Set(records.map(\.id)).count == records.count,
              records.reduce(0, { $0 + $1.body.count }) <= byteCapacity else { throw Failure.unavailable }
        for record in records { try validate(record) }
    }
    private func privateDirectory(_ url: URL) throws {
        var value = stat()
        guard lstat(url.path, &value) == 0, (value.st_mode & S_IFMT) == S_IFDIR,
              value.st_uid == getuid(), value.st_mode & 0o077 == 0 else { throw Failure.unavailable }
    }
    private func prepareDirectory(create: Bool) throws -> Bool {
        try privateDirectory(directory.deletingLastPathComponent())
        var value = stat()
        if lstat(directory.path, &value) != 0 {
            guard errno == ENOENT else { throw Failure.unavailable }
            guard create else { return false }
            guard mkdir(directory.path, S_IRWXU) == 0 else { throw Failure.unavailable }
        }
        try privateDirectory(directory)
        return true
    }
    private func privateFile(_ value: stat) throws {
        guard (value.st_mode & S_IFMT) == S_IFREG, value.st_uid == getuid(), value.st_nlink == 1,
              value.st_mode & 0o077 == 0 else { throw Failure.unavailable }
    }
    private func read() throws -> Data? {
        let fd = open(file.path, O_RDONLY | O_NOFOLLOW | O_NONBLOCK)
        if fd < 0 {
            guard errno == ENOENT else { throw Failure.unavailable }
            return nil
        }
        let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
        defer { try? handle.close() }
        var value = stat()
        guard fstat(fd, &value) == 0, value.st_size >= 28, value.st_size <= maximumFileBytes else { throw Failure.unavailable }
        try privateFile(value)
        var bytes = Data()
        while let chunk = try handle.read(upToCount: min(4096, maximumFileBytes + 1 - bytes.count)), !chunk.isEmpty {
            bytes.append(chunk)
            guard bytes.count <= maximumFileBytes else { throw Failure.unavailable }
        }
        return bytes
    }
    private func decode(_ bytes: Data, key: SymmetricKey) throws -> [Record] {
        var plaintext = try AES.GCM.open(AES.GCM.SealedBox(combined: bytes), using: key, authenticating: aad)
        defer { plaintext.resetBytes(in: 0..<plaintext.count) }
        let snapshot = try JSONDecoder().decode(Snapshot.self, from: plaintext)
        guard snapshot.version == 1 else { throw Failure.unavailable }
        try validate(snapshot.records)
        return snapshot.records
    }
    private func write(_ records: [Record], key: SymmetricKey) throws {
        try validate(records)
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        var plaintext = try encoder.encode(Snapshot(version: 1, records: records))
        defer { plaintext.resetBytes(in: 0..<plaintext.count) }
        guard let bytes = try AES.GCM.seal(plaintext, using: key, authenticating: aad).combined,
              bytes.count <= maximumFileBytes else { throw Failure.unavailable }
        let temporary = directory.appendingPathComponent(".capture-" + UUID().uuidString)
        let fd = open(temporary.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, S_IRUSR | S_IWUSR)
        guard fd >= 0 else { throw Failure.unavailable }
        let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
        defer { try? handle.close(); unlink(temporary.path) }
        try handle.write(contentsOf: bytes)
        var existing = stat()
        if lstat(file.path, &existing) == 0 { try privateFile(existing) }
        else if errno != ENOENT { throw Failure.unavailable }
        guard fsync(fd) == 0, rename(temporary.path, file.path) == 0 else { throw Failure.unavailable }
        let directoryFD = open(directory.path, O_RDONLY | O_NOFOLLOW)
        guard directoryFD >= 0 else { throw Failure.unavailable }
        defer { close(directoryFD) }
        guard fsync(directoryFD) == 0 else { throw Failure.unavailable }
    }
}
