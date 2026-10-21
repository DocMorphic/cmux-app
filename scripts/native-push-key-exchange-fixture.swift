import CMUXMobileCore
import CmuxPhonePush
import Foundation

@MainActor final class AuthCoordinator {
    struct User { let id: String }
    var accountID: String? = "fixture-account"
    var generation: UInt64 = 1
    var isAuthenticated: Bool { accountID != nil }
    var currentUser: User? { accountID.map { User(id: $0) } }
    var holdBootstrap = false
    private(set) var bootstrapEntered = false
    private var bootstrapContinuation: CheckedContinuation<Void, Never>?
    func awaitBootstrapped() async {
        bootstrapEntered = true
        if holdBootstrap {
            await withCheckedContinuation { bootstrapContinuation = $0 }
        }
    }
    func releaseBootstrap() {
        bootstrapContinuation?.resume()
        bootstrapContinuation = nil
    }
    var authenticatedSessionIdentity: AuthenticatedSessionIdentity? {
        accountID.map { AuthenticatedSessionIdentity(generation: generation, accountID: $0) }
    }
    func isAuthenticatedSessionIdentityCurrent(_ identity: AuthenticatedSessionIdentity) -> Bool {
        authenticatedSessionIdentity == identity
    }
}
@MainActor final class PhonePushClient {
    static let shared = PhonePushClient()
    private(set) var refreshes = 0
    func refreshAndroidPushTrust() { refreshes += 1 }
}
enum MobileHostIdentity {
    static func deviceID() -> String { "fixture-device" }
    static func instanceTag() -> String { "fixture-instance" }
}

@main struct KeyExchangeFixture {
    @MainActor static func until(_ condition: () -> Bool) async throws {
        let deadline = ContinuousClock.now + .seconds(5)
        while !condition() {
            precondition(ContinuousClock.now < deadline)
            try await Task.sleep(nanoseconds: 1_000_000)
        }
    }
    @MainActor static func main() async throws {
        let mode = CommandLine.arguments[1]
        let auth = AuthCoordinator()
        let host = MobileHostService(auth: auth)
        auth.holdBootstrap = mode.hasPrefix("bootstrap-")
        let held = ["account-change", "same-account-reentry", "coordinator-replaced", "cancelled", "deadline"].contains(mode)
        FixtureKeychain.shared.configure(held: held, fail: mode == "keychain-error")
        if mode == "signed-out" { auth.accountID = nil }
        let descriptor = MobilePhonePushPublicKeyDescriptor(installationID: "fixture-phone", keyID: "fixture-phone-key",
            publicKey: Data(repeating: 0x24, count: mode == "malformed" ? 1 : 32))
        let exchange = MobilePhonePushKeyExchangeRequest(clientID: "fixture-client", iosBuildID: "fixture.android", descriptor: descriptor)
        let data = try JSONEncoder().encode(exchange)
        let params = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let request = MobileHostRPCRequest(id: 1, method: "phone_push.keys.exchange", params: params, auth: nil)
        let response = Task { @MainActor in await host.handlePhonePushKeyExchange(request) }
        if mode.hasPrefix("bootstrap-") {
            try await until { auth.bootstrapEntered }
            if mode == "bootstrap-cancelled" { response.cancel() }
            else { host.fixtureReplaceAuth(AuthCoordinator()) }
            auth.releaseBootstrap()
        }
        if held {
            try await until { FixtureKeychain.shared.counts.0 == 1 }
            // A real main-actor request is suspended on the dedicated worker; UI work runs.
            await Task.yield()
            precondition(FixtureKeychain.shared.counts.1 == 0)
            switch mode {
            case "account-change": auth.accountID = "fixture-other"; auth.generation += 1
            case "same-account-reentry": auth.accountID = nil; auth.generation += 1; auth.accountID = "fixture-account"; auth.generation += 1
            case "coordinator-replaced": host.fixtureReplaceAuth(AuthCoordinator())
            case "cancelled": response.cancel()
            default: break
            }
            if mode != "deadline" && mode != "cancelled" { FixtureKeychain.shared.gate.signal() }
        }
        let result = await response.value
        if mode == "valid" {
            guard case let .ok(payload) = result else { preconditionFailure("Valid exchange failed") }
            let encoded = try JSONSerialization.data(withJSONObject: payload)
            let reply = try JSONDecoder().decode(MobilePhonePushKeyExchangeResponse.self, from: encoded)
            try reply.validate()
            precondition(reply.accountID == "fixture-account" && reply.teamID == nil && reply.macDeviceID == "fixture-device")
            precondition(reply.descriptor.keyID == "fixture-key" && reply.descriptor.installationID == "fixture-installation")
            precondition(PhonePushPeerKeyStore.pinCount == 1 && PhonePushClient.shared.refreshes == 1)
            precondition(PhonePushPeerKeyStore.lastAccountID == "fixture-account")
        } else {
            guard case let .failure(error) = result else { preconditionFailure("Retired/invalid exchange succeeded") }
            let expected = mode == "malformed" ? "invalid_request" : ["cancelled", "bootstrap-cancelled"].contains(mode) ? "cancelled" :
                ["deadline", "keychain-error"].contains(mode) ? "key_unavailable" : "unauthorized"
            precondition(error.code == expected, "Wrong failure classification")
            precondition(PhonePushPeerKeyStore.pinCount == 0 && PhonePushClient.shared.refreshes == 0)
            if ["signed-out", "malformed", "bootstrap-owner-replaced", "bootstrap-cancelled"].contains(mode) { precondition(FixtureKeychain.shared.counts.0 == 0) }
        }
        if mode == "deadline" || mode == "cancelled" {
            precondition(FixtureKeychain.shared.counts.1 == 0, "Failure waited for the blocked Keychain read")
            FixtureKeychain.shared.gate.signal()
            try await until { FixtureKeychain.shared.counts.1 == 1 }
            precondition(PhonePushPeerKeyStore.pinCount == 0)
        }
        print("actual patched key-exchange handler: \(mode) passed")
    }
}
