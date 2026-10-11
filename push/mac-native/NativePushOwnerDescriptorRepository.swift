import Darwin
import Foundation

/// Publishes only the custom app's public push descriptor before each fresh host
/// observation and private helper launch. File/Keychain work stays off main.
actor NativePushOwnerDescriptorRepository {
    enum Failure: Error { case unavailable }
    private struct Configuration: Decodable {
        let version: Int
        let nativeDescriptorPath: String
        let stateDirectory: String
    }
    private struct Descriptor: Codable {
        let version: Int
        let algorithm: String
        let installationID: String
        let keyID: String
        let publicKey: Data
    }
    private let configurationURL: URL
    private let loadDescriptor: @Sendable () throws -> Data

    init(configurationURL: URL, loadDescriptor: @escaping @Sendable () throws -> Data) {
        self.configurationURL = configurationURL; self.loadDescriptor = loadDescriptor
    }

    func controlSocket() throws -> String {
        let configuration = try JSONDecoder().decode(Configuration.self, from: readPrivate(configurationURL))
        guard configuration.version == 1, configuration.stateDirectory.hasPrefix("/"),
              !configuration.stateDirectory.contains("\0") else { throw Failure.unavailable }
        return URL(fileURLWithPath: configuration.stateDirectory).appendingPathComponent("control/owner.sock").path
    }

    func captures(loadKey: @escaping @Sendable (Bool) throws -> Data) throws -> NativePushCaptureRepository {
        do {
            let configuration = try JSONDecoder().decode(Configuration.self, from: readPrivate(configurationURL))
            guard configuration.version == 1, configuration.stateDirectory.hasPrefix("/"),
                  !configuration.stateDirectory.contains("\0") else { throw Failure.unavailable }
            let state = URL(fileURLWithPath: configuration.stateDirectory)
            var existing = stat()
            if lstat(state.path, &existing) != 0 {
                guard errno == ENOENT, mkdir(state.path, S_IRWXU) == 0 else { throw Failure.unavailable }
            }
            try privateDirectory(state)
            return try NativePushCaptureRepository(directory: state.appendingPathComponent("producer"), loadKey: loadKey)
        } catch { throw Failure.unavailable }
    }

    func publish() throws {
        do {
            let configuration = try JSONDecoder().decode(Configuration.self, from: readPrivate(configurationURL))
            guard configuration.version == 1, configuration.nativeDescriptorPath.hasPrefix("/") else { throw Failure.unavailable }
            let target = URL(fileURLWithPath: configuration.nativeDescriptorPath)
            try privateDirectory(target.deletingLastPathComponent())
            var existing = stat()
            if lstat(target.path, &existing) == 0 { try privateFile(existing) }
            else if errno != ENOENT { throw Failure.unavailable }
            let supplied = try loadDescriptor()
            guard supplied.count <= 16_384,
                  let object = try JSONSerialization.jsonObject(with: supplied) as? [String: Any],
                  Set(object.keys) == Set(["version", "algorithm", "installationID", "keyID", "publicKey"]) else { throw Failure.unavailable }
            let descriptor = try JSONDecoder().decode(Descriptor.self, from: supplied)
            guard descriptor.version == 1, descriptor.algorithm == "rawX25519", descriptor.publicKey.count == 32,
                  [descriptor.installationID, descriptor.keyID].allSatisfy({ !$0.isEmpty && $0.utf16.count <= 1024 &&
                      !$0.unicodeScalars.contains(where: { $0.value < 32 }) }) else { throw Failure.unavailable }
            let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
            let data = try encoder.encode(descriptor)
            if let current = try? readPrivate(target), current == data { return }
            let temporary = target.deletingLastPathComponent().appendingPathComponent(".cmux-push-" + UUID().uuidString)
            let fd = open(temporary.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, S_IRUSR | S_IWUSR)
            guard fd >= 0 else { throw Failure.unavailable }
            let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
            defer { try? handle.close(); unlink(temporary.path) }
            try handle.write(contentsOf: data)
            guard fsync(fd) == 0, rename(temporary.path, target.path) == 0 else { throw Failure.unavailable }
        } catch { throw Failure.unavailable }
    }

    private func privateDirectory(_ url: URL) throws {
        var value = stat()
        guard url.isFileURL, url.path.hasPrefix("/"), lstat(url.path, &value) == 0,
              (value.st_mode & S_IFMT) == S_IFDIR, value.st_uid == getuid(), (value.st_mode & 0o077) == 0 else { throw Failure.unavailable }
    }
    private func privateFile(_ value: stat) throws {
        guard (value.st_mode & S_IFMT) == S_IFREG, value.st_uid == getuid(), value.st_nlink == 1,
              (value.st_mode & 0o077) == 0 else { throw Failure.unavailable }
    }
    private func readPrivate(_ url: URL) throws -> Data {
        try privateDirectory(url.deletingLastPathComponent())
        let fd = open(url.path, O_RDONLY | O_NOFOLLOW | O_NONBLOCK)
        guard fd >= 0 else { throw Failure.unavailable }
        let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
        defer { try? handle.close() }
        var value = stat()
        guard fstat(fd, &value) == 0, value.st_size > 0, value.st_size <= 65_536 else { throw Failure.unavailable }
        try privateFile(value)
        var data = Data()
        while let chunk = try handle.read(upToCount: min(4096, 65_537 - data.count)), !chunk.isEmpty {
            data.append(chunk)
            guard data.count <= 65_536 else { throw Failure.unavailable }
        }
        return data
    }
}
