import CryptoKit
import Darwin
import Foundation

/// Short-lived secret stays in memory and is never a diagnostic description.
struct NativePushPairingOffer: Sendable, CustomStringConvertible {
    let body: Data
    let id: String
    let endpoint: String
    let fingerprint: String
    let expiresAt: Int64
    var description: String { "NativePushPairingOffer(redacted)" }

    init(body: Data, phoneBuildID: String, now: Int64) throws {
        guard body.count <= 8192,
              let value = try JSONSerialization.jsonObject(with: body) as? [String: Any],
              Set(value.keys) == Set(["version", "offerID", "expiresAt", "endpoint", "project", "phoneBuildID", "mac", "native", "helper", "secret"]),
              value["version"] as? Int == 1, value["phoneBuildID"] as? String == phoneBuildID,
              let id = value["offerID"] as? String, UUID(uuidString: id)?.uuidString.lowercased() == id,
              let expiry = value["expiresAt"] as? Int64, expiry > now, expiry - now <= 120_000,
              let endpoint = value["endpoint"] as? String, let url = URLComponents(string: endpoint),
              url.scheme == "https", url.host?.isEmpty == false, url.user == nil, url.password == nil,
              url.query == nil, url.fragment == nil, url.path == "/v1/push/enroll",
              let secret = value["secret"] as? String,
              secret.count == 43, secret.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }),
              let secretData = Data(base64Encoded: secret.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/") + "="),
              secretData.count == 32, secretData.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") == secret,
              let project = value["project"] as? [String: Any], Set(project.keys) == Set(["project", "sender", "application"]),
              project.values.allSatisfy(Self.text),
              let mac = value["mac"] as? [String: Any],
              Set(mac.keys) == Set(["accountID", "teamID", "macDeviceID", "macInstanceTag", "macBuildID"]),
              mac.allSatisfy({ $0.key == "teamID" && $0.value is NSNull || Self.text($0.value) }),
              let native = value["native"] as? [String: Any], let helper = value["helper"] as? [String: Any]
        else { throw NativePushPairingClient.Failure.unavailable }
        let nativeKey = try Self.key(native), helperKey = try Self.key(helper)
        guard ["installationID", "keyID", "publicKey"].allSatisfy({ native[$0] as? String != helper[$0] as? String }),
              nativeKey != helperKey else { throw NativePushPairingClient.Failure.unavailable }
        self.body = body; self.id = id; self.endpoint = endpoint; self.expiresAt = expiry
        let hex = SHA256.hash(data: helperKey).map { String(format: "%02X", $0) }.joined()
        self.fingerprint = stride(from: 0, to: 64, by: 4).map { index in
            return String(hex.dropFirst(index).prefix(4))
        }.joined(separator: " ")
    }
    private static func text(_ value: Any) -> Bool {
        guard let value = value as? String else { return false }
        return !value.isEmpty && value.utf16.count <= 1024 && !value.unicodeScalars.contains { $0.value < 32 }
    }
    private static func key(_ value: [String: Any]) throws -> Data {
        guard Set(value.keys) == Set(["version", "algorithm", "installationID", "keyID", "publicKey"]),
              value["version"] as? Int == 1, value["algorithm"] as? String == "rawX25519",
              text(value["installationID"] ?? NSNull()), text(value["keyID"] ?? NSNull()),
              let raw = value["publicKey"] as? String, let key = Data(base64Encoded: raw),
              key.count == 32, key.base64EncodedString() == raw else { throw NativePushPairingClient.Failure.unavailable }
        return key
    }
}

/// Bounded Unix socket IO runs in an actor, outside the UI/main actor.
actor NativePushPairingClient {
    enum Failure: Error { case unavailable }
    private let path: String
    init(path: String) { self.path = path }
    func request(action: String, value: String) throws -> Data {
        guard ["offer", "cancel"].contains(action), !value.isEmpty, value.utf16.count <= 1024,
              path.hasPrefix("/"), !path.contains("\0"), path.utf8.count <= 100 else { throw Failure.unavailable }
        let parent = URL(fileURLWithPath: path).deletingLastPathComponent().path
        var directory = stat(), entry = stat()
        guard lstat(parent, &directory) == 0, directory.st_mode & S_IFMT == S_IFDIR,
              directory.st_uid == getuid(), directory.st_mode & 0o077 == 0,
              lstat(path, &entry) == 0, entry.st_mode & S_IFMT == S_IFSOCK,
              entry.st_uid == getuid(), entry.st_mode & 0o077 == 0 else { throw Failure.unavailable }
        let fd = socket(AF_UNIX, SOCK_STREAM, 0)
        guard fd >= 0 else { throw Failure.unavailable }
        defer { Darwin.close(fd) }
        var noSignal: Int32 = 1
        guard setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &noSignal, socklen_t(MemoryLayout<Int32>.size)) == 0,
              fcntl(fd, F_SETFL, O_NONBLOCK) == 0 else { throw Failure.unavailable }
        var address = sockaddr_un(); address.sun_family = sa_family_t(AF_UNIX)
        address.sun_len = UInt8(MemoryLayout<sockaddr_un>.size)
        let name = Array(path.utf8) + [0]
        withUnsafeMutableBytes(of: &address.sun_path) { $0.copyBytes(from: name) }
        let result = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) }
        }
        let deadline = ProcessInfo.processInfo.systemUptime + 4
        if result != 0 {
            guard errno == EINPROGRESS else { throw Failure.unavailable }
            try ready(fd, events: Int16(POLLOUT), deadline: deadline)
            var error: Int32 = 0, size = socklen_t(MemoryLayout<Int32>.size)
            guard getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &size) == 0, error == 0 else { throw Failure.unavailable }
        }
        var uid: uid_t = 0, gid: gid_t = 0
        guard getpeereid(fd, &uid, &gid) == 0, uid == getuid() else { throw Failure.unavailable }
        var request = try JSONSerialization.data(withJSONObject: ["action": action, "value": value]); request.append(10)
        var sent = 0
        while sent < request.count {
            try ready(fd, events: Int16(POLLOUT), deadline: deadline)
            let count = request.withUnsafeBytes { send(fd, $0.baseAddress!.advanced(by: sent), request.count - sent, 0) }
            if count < 0 && [EAGAIN, EINTR].contains(errno) { continue }
            guard count > 0 else { throw Failure.unavailable }; sent += count
        }
        var data = Data(), buffer = [UInt8](repeating: 0, count: 4096)
        while true {
            try ready(fd, events: Int16(POLLIN), deadline: deadline)
            let count = recv(fd, &buffer, buffer.count, 0)
            if count < 0 && [EAGAIN, EINTR].contains(errno) { continue }
            guard count >= 0 else { throw Failure.unavailable }
            if count == 0 { break }
            guard data.count + count <= 32_768 else { throw Failure.unavailable }
            data.append(contentsOf: buffer.prefix(count))
        }
        guard let response = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(response.keys) == Set(["ok", "result"]), response["ok"] as? Bool == true,
              let result = response["result"] as? [String: Any] else { throw Failure.unavailable }
        return try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys, .withoutEscapingSlashes])
    }
    private func ready(_ fd: Int32, events: Int16, deadline: TimeInterval) throws {
        while ProcessInfo.processInfo.systemUptime < deadline {
            try Task.checkCancellation()
            var descriptor = pollfd(fd: fd, events: events, revents: 0)
            let result = poll(&descriptor, 1, 100)
            if result < 0 && errno == EINTR { continue }
            guard result >= 0, descriptor.revents & Int16(POLLERR | POLLNVAL) == 0 else { throw Failure.unavailable }
            if descriptor.revents & (events | Int16(POLLHUP)) != 0 { return }
        }
        throw Failure.unavailable
    }
}
