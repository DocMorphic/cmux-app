// Compile with the unchanged upstream crypto implementation. Public, fixed test keys only.
import CryptoKit
import Foundation

@main struct ReplyFixture {
    static func main() throws {
        let recipient = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data((0..<32).map(UInt8.init)))
        let sender = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data((32..<64).map(UInt8.init)))
        let tuple = PhonePushDeviceTuple(accountID: "test-account", teamID: nil,
            iosBuildID: "io.github.docmorphic.cmuxapp", iosInstallationID: "test-phone",
            macDeviceID: "test-mac", macInstanceTag: "test-instance", macBuildID: "dev.cmux.app")
        let input = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
        let records = try JSONSerialization.jsonObject(with: input) as! [[String: Any]]
        precondition(records.count == 2)
        for record in records {
            let request = record["request"] as! [String: Any]
            let envelope = try JSONDecoder().decode(PhonePushEncryptedPayload.self,
                from: JSONSerialization.data(withJSONObject: request["encryptedPayload"]!))
            let clear = try PhonePushCrypto().decrypt(envelope: envelope, tuple: tuple,
                recipientInstallationID: "test-mac-installation", recipientKeyID: "test-recipient-key",
                trustedSenderKeyID: "test-sender-key", senderPublicKey: sender.publicKey.rawRepresentation,
                privateKey: recipient)
            let value = try JSONSerialization.jsonObject(with: clear) as! [String: Any]
            precondition(value["text"] as? String == record["expectedText"] as? String)
            precondition(value["replyId"] as? String == request["replyId"] as? String)
            precondition(value["accountID"] as? String == "test-account")
            precondition(value["macDeviceId"] as? String == "test-mac")
            precondition(value["workspaceId"] as? String == "workspace")
            precondition(value["surfaceId"] as? String == "surface")
            precondition(value["retargetsToLiveSurfaceOwner"] as? Bool == false)
            precondition(value["issuedAtEpochSeconds"] as? Double == record["issuedAt"] as? Double)
            precondition(value["expiresAtEpochSeconds"] as! Double - (value["issuedAtEpochSeconds"] as! Double) == 900)
            do {
                _ = try PhonePushCrypto().decrypt(envelope: envelope, tuple: tuple,
                    recipientInstallationID: "test-phone", recipientKeyID: "test-recipient-key",
                    trustedSenderKeyID: "test-sender-key", senderPublicKey: sender.publicKey.rawRepresentation,
                    privateKey: recipient)
                fatalError("Reply incorrectly accepted a phone recipient")
            } catch {}
        }
        print("Apple CryptoKit opened both Android replies at the Mac installation; destinations, text and expiry matched")
    }
}
