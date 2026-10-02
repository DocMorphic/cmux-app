// Compile alongside the pinned upstream PhonePushCrypto.swift. Only public test
// keys derived from fixed byte sequences are used; no Keychain method is called.
import CryptoKit
import Foundation

@main struct Fixture {
    static func main() throws {
        let recipient = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data((0..<32).map(UInt8.init)))
        let sender = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data((32..<64).map(UInt8.init)))
        let crypto = PhonePushCrypto()
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .prettyPrinted]
        let tuples = [
            PhonePushDeviceTuple(accountID: "test-account", teamID: "test-team", iosBuildID: "io.github.docmorphic.cmuxapp",
                iosInstallationID: "test-phone", macDeviceID: "test-mac", macInstanceTag: "test-instance", macBuildID: "dev.cmux.app"),
            PhonePushDeviceTuple(accountID: nil, teamID: nil, iosBuildID: "test/build", iosInstallationID: "test-phone",
                macDeviceID: "test/λ/中/😀\u{2028}\u{2029}", macInstanceTag: "test\n\t\u{0001}\"\\", macBuildID: nil)
        ]
        if CommandLine.arguments.count == 3 && CommandLine.arguments[1] == "--verify" {
            let data = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[2]))
            let envelopes = try JSONDecoder().decode([PhonePushEncryptedPayload].self, from: data)
            guard envelopes.count == tuples.count else { fatalError("Wrong fixture count") }
            for (index, envelope) in envelopes.enumerated() {
                let opened = try crypto.decrypt(envelope: envelope, tuple: tuples[index], recipientInstallationID: "test-phone",
                    recipientKeyID: "test-recipient-key", trustedSenderKeyID: "test-sender-key",
                    senderPublicKey: sender.publicKey.rawRepresentation, privateKey: recipient)
                guard opened == Data("Generated Android push fixture λ 中 \(index)".utf8) else { fatalError("Wrong plaintext") }
            }
            print("Apple CryptoKit opened both Android authenticated HPKE envelopes")
            return
        }
        let canonical = JSONEncoder(); canonical.outputFormatting = [.sortedKeys]
        var fixtures = [[String: Any]]()
        for (index, tuple) in tuples.enumerated() {
            let plaintext = Data("Generated Apple push fixture λ 中 \(index)".utf8)
            let envelope = try crypto.encrypt(plaintext: plaintext, tuple: tuple,
                recipientPublicKey: recipient.publicKey.rawRepresentation, keyID: "test-recipient-key",
                senderKeyID: "test-sender-key", senderPrivateKey: sender, installationID: "test-phone")
            fixtures.append([
                "envelope": try JSONSerialization.jsonObject(with: encoder.encode(envelope)),
                "canonicalTupleBase64": try canonical.encode(tuple).base64EncodedString(),
                "plaintextBase64": plaintext.base64EncodedString(),
                "recipientPrivateKeyBase64": recipient.rawRepresentation.base64EncodedString(),
                "recipientPublicKeyBase64": recipient.publicKey.rawRepresentation.base64EncodedString(),
                "senderPrivateKeyBase64": sender.rawRepresentation.base64EncodedString(),
                "senderPublicKeyBase64": sender.publicKey.rawRepresentation.base64EncodedString()
            ])
        }
        let data = try JSONSerialization.data(withJSONObject: fixtures, options: [.prettyPrinted, .sortedKeys])
        FileHandle.standardOutput.write(data)
    }
}
