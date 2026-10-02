// Compile with the pinned Mac payload encoder and HPKE implementation.
// This inert type satisfies the unused belongs(to:) method's package dependency.
import CryptoKit
import Foundation
public struct AuthenticatedSessionSnapshot { public let accountID: String; public let generation: UInt64 }

@main struct MessageFixture {
    static func main() throws {
        let recipient = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data((0..<32).map(UInt8.init)))
        let sender = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data((32..<64).map(UInt8.init)))
        let tuple = PhonePushDeviceTuple(accountID: "fixture-user", teamID: nil,
            iosBuildID: "android.fixture", iosInstallationID: "fixture-phone", macDeviceID: "physical-mac",
            macInstanceTag: "stable", macBuildID: "fixture.mac")
        var result = [[String: Any]]()
        for (index, kind) in [PhonePushPayloadKind.notify, .dismiss].enumerated() {
            let payload = PhonePushPayload(kind: kind, title: "Ready λ 中", subtitle: "Fixture workspace", body: "Choose the next step",
                replyShape: "text", workspaceId: "workspace", surfaceId: "surface", retargetsToLiveSurfaceOwner: false,
                macDeviceId: "physical-mac", macInstanceTag: "stable", notificationId: "notice", notificationIds: ["notice"],
                badgeCount: kind == .notify ? 1 : 0, hideContent: false)
            let request = try PhonePushRequestEnvelope(payload: payload,
                correlationID: UUID(uuidString: index == 0 ? "00000000-0000-4000-8000-000000000001" : "00000000-0000-4000-8000-000000000002")!,
                expirationEpochSeconds: 1_800_000_120, macPushPublicKey: sender.publicKey.rawRepresentation.base64EncodedString(),
                macInstallationID: "fixture-mac", macBuildID: "fixture.mac")
            let encrypted = try PhonePushCrypto().encrypt(plaintext: request.body, tuple: tuple,
                recipientPublicKey: recipient.publicKey.rawRepresentation, keyID: "phone-key", senderKeyID: "mac-key",
                senderPrivateKey: sender, installationID: "fixture-phone")
            result.append(["kind": kind.rawValue, "cmux": ["encryptedPayloads": [try JSONSerialization.jsonObject(with: JSONEncoder().encode(encrypted))]],
                "plaintext": try JSONSerialization.jsonObject(with: request.body)])
        }
        FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted, .sortedKeys]))
    }
}
