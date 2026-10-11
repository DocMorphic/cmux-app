import Foundation
import CmuxPhonePush

final class FixturePublicStorage: PhonePushSharedStateStorage {
    var values: [String: Data] = [:]
    func data(forKey key: String) -> Data? { values[key] }
    func setData(_ data: Data?, forKey key: String) { values[key] = data }
    func keys(withPrefix prefix: String) -> [String] { values.keys.filter { $0.hasPrefix(prefix) } }
}
@MainActor final class FixtureTrustSink {
    var invalidations = 0
    func invalidate() { invalidations += 1 }
}
@MainActor final class NativeTrustHookFixture {
    private var nativePushLastTrustedPhones: [NativePushSourceMessages.TrustedPhone]?
    private var nativePushSource: FixtureTrustSink? = FixtureTrustSink()
    private func publishAndroidPushHost() {}
    // ACTUAL_NATIVE_TRUST_HOOK

    func run() throws {
        let storage = FixturePublicStorage(), store = PhonePushPeerKeyStore(storage: storage)
        let identity = NativePushSourceMessages.Identity(accountID: "fixture-account", sessionGeneration: 1,
            teamID: "verified-selected-team", teamGeneration: 2, macDeviceID: "fixture-mac", macInstanceTag: "fixture-tag", macBuildID: "fixture-build")
        func tuple(account: String = "fixture-account", team: String? = nil, phone: String = "fixture-phone",
            build: String = "io.github.docmorphic.cmuxapp", mac: String = "fixture-mac", tag: String = "fixture-tag",
            macBuild: String = "fixture-build") -> PhonePushDeviceTuple {
            .init(accountID: account, teamID: team, iosBuildID: build, iosInstallationID: phone,
                macDeviceID: mac, macInstanceTag: tag, macBuildID: macBuild)
        }
        let target = tuple(), original = PhonePushPeerDescriptor(keyID: "fixture-key", publicKey: Data(repeating: 3, count: 32))
        store.pin(original, for: target)
        let first = try nativePushTrustedPhones(identity, storage: storage)
        precondition(first.count == 1 && first[0].keyID == original.keyID && first[0].publicKey == original.publicKey)
        precondition(nativePushSource?.invalidations == 0)
        let mapper = NativePushSourceMessages(), queue = try NativePushSourceDeliveryQueue()
        let body = try mapper.host(identity: identity, confirmedSignedOut: false,
            settings: .init(forwardingEnabled: true, mode: "always", admission: "allowed", hideContent: false),
            nowEpochSeconds: 1_800_000_000, trustedPhones: first)
        precondition(queue.offer([body], now: 1_800_000_000_000) == .accepted)
        var malformed = try JSONSerialization.jsonObject(with: body) as! [String: Any]
        var snapshot = malformed["snapshot"] as! [String: Any]
        snapshot["trustedPhones"] = [first[0].wire, first[0].wire]; malformed["snapshot"] = snapshot
        let ambiguous = try JSONSerialization.data(withJSONObject: malformed)
        precondition(queue.offer([ambiguous], now: 1_800_000_000_000) == .rejected)
        precondition(queue.canBegin(now: 1_800_000_000_000))
        for other in [tuple(account: "other"), tuple(team: "other"), tuple(build: "ios.fixture"), tuple(mac: "other"),
                      tuple(tag: "other"), tuple(macBuild: "other")] { store.pin(original, for: other) }
        let unrelated = try nativePushTrustedPhones(identity, storage: storage)
        precondition(unrelated == first)
        let rotated = PhonePushPeerDescriptor(keyID: "rotated-key", publicKey: Data(repeating: 4, count: 32))
        store.pin(rotated, for: target)
        let next = try nativePushTrustedPhones(identity, storage: storage)
        precondition(next.count == 1 && next[0].keyID == rotated.keyID && nativePushSource?.invalidations == 1)
        let prefix = "cmux.phone-push.peer.v2."
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        let key = prefix + (try encoder.encode(target)).base64EncodedString()
        storage.setData(nil, forKey: key)
        let removed = try nativePushTrustedPhones(identity, storage: storage)
        precondition(removed.isEmpty)
        precondition(nativePushSource?.invalidations == 2)
        storage.setData(Data("invalid descriptor".utf8), forKey: key)
        let corrupt = try nativePushTrustedPhones(identity, storage: storage)
        precondition(corrupt.isEmpty)
        store.pin(original, for: target)
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        storage.setData(try JSONEncoder().encode(rotated), forKey: prefix + (try encoder.encode(target)).base64EncodedString())
        do { _ = try nativePushTrustedPhones(identity, storage: storage); preconditionFailure("Conflicting aliases accepted") }
        catch NativePushSourceMessages.Failure.invalidInput {}
        for index in 0..<129 { storage.setData(Data(), forKey: prefix + "oversized-fixture-\(index)") }
        do { _ = try nativePushTrustedPhones(identity, storage: storage); preconditionFailure("Oversized registry accepted") }
        catch NativePushSourceMessages.Failure.invalidInput {}
        print("Actual native peer store/hook: account-scoped pin, six unrelated scopes, rotation fencing, removal fencing, corrupt row, conflicting aliases and registry bound passed")
    }
}
@main struct NativePushTrustFixture {
    @MainActor static func main() throws { try NativeTrustHookFixture().run() }
}
