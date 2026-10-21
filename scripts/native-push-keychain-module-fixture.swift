import Foundation

/// Inert replacement for the external Keychain/peer-storage boundary. No Security APIs.
public final class FixtureKeychain: @unchecked Sendable {
    public static let shared = FixtureKeychain()
    public let gate = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    private var held = false
    private var fail = false
    private var started = 0
    private var finished = 0
    public func configure(held: Bool, fail: Bool) {
        lock.lock(); self.held = held; self.fail = fail; lock.unlock()
    }
    public var counts: (Int, Int) {
        lock.lock(); defer { lock.unlock() }; return (started, finished)
    }
    public func read() throws -> PhonePushKeyMaterial {
        precondition(!Thread.isMainThread, "Keychain factory occupied the UI thread")
        lock.lock(); started += 1; let held = held; let fail = fail; lock.unlock()
        if held { gate.wait() }
        lock.lock(); finished += 1; lock.unlock()
        if fail { throw FixtureFailure.unavailable }
        return PhonePushKeyMaterial()
    }
    public enum FixtureFailure: Error { case unavailable }
}

public struct PhonePushKeyMaterial: Sendable {
    public let installationID = "fixture-installation"
    public let keyID = "fixture-key"
    public var publicKeyData: Data { Data(repeating: 0x42, count: 32) }
    public static func current(bundleID: String, accessGroup: String? = nil) throws -> Self {
        precondition(bundleID == (Bundle.main.bundleIdentifier ?? "cmux"))
        return try FixtureKeychain.shared.read()
    }
}

public struct PhonePushDeviceTuple: Sendable {
    public let accountID: String?
    public let teamID: String?
    public init(accountID: String?, teamID: String?, iosBuildID: String, iosInstallationID: String,
                macDeviceID: String, macInstanceTag: String, macBuildID: String) {
        self.accountID = accountID; self.teamID = teamID
        precondition(iosInstallationID == "fixture-phone" && macDeviceID == "fixture-device")
    }
}

@MainActor public struct PhonePushPeerKeyStore {
    public private(set) static var pinCount = 0
    public private(set) static var lastAccountID: String?
    public init() {}
    public func pin(_ publicKey: Data, keyID: String, for tuple: PhonePushDeviceTuple) {
        precondition(publicKey.count == 32 && keyID == "fixture-phone-key" && tuple.teamID == nil)
        Self.pinCount += 1; Self.lastAccountID = tuple.accountID
    }
}
