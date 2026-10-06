// One-shot private-pipe adapter. No Keychain discovery, networking, or logging.
import CryptoKit
import Foundation

private struct Tuple: Codable {
    let accountID: String?
    let teamID: String?
    let iosBuildID: String
    let iosInstallationID: String
    let macDeviceID: String?
    let macInstanceTag: String?
    let macBuildID: String?
}
private struct Recipient: Codable {
    let installationID: String
    let keyID: String
    let senderKeyID: String
    let tuple: Tuple
}
private struct Request: Decodable {
    let recipient: Recipient
    let recipientPublicKey: String
    let senderPrivateKey: String
    let senderPublicKey: String
    let plaintext: String
}
private struct Envelope: Encodable {
    let version = 2
    let installationID: String
    let keyID: String
    let senderKeyID: String
    let tuple: Tuple
    let encapsulatedKey: String
    let ciphertext: String
}
private enum Failure: Error { case invalid }
private func bytes(_ value: String, count: ClosedRange<Int>) throws -> Data {
    guard let result = Data(base64Encoded: value), count.contains(result.count),
          result.base64EncodedString() == value else { throw Failure.invalid }
    return result
}

@main private struct PushSeal {
    static func main() {
        do {
            guard CommandLine.arguments.count == 1 else { throw Failure.invalid }
            // Bound reads even for a caller that never closes stdin; parent also sets a timeout.
            var input = Data()
            while let chunk = try FileHandle.standardInput.read(upToCount: 4096), !chunk.isEmpty {
                input.append(chunk)
                guard input.count <= 65_536 else { throw Failure.invalid }
            }
            let request = try JSONDecoder().decode(Request.self, from: input)
            let target = request.recipient
            let fields = [target.installationID, target.keyID, target.senderKeyID,
                          target.tuple.iosBuildID, target.tuple.iosInstallationID]
                + [target.tuple.accountID, target.tuple.teamID, target.tuple.macDeviceID,
                   target.tuple.macInstanceTag, target.tuple.macBuildID].compactMap { $0 }
            guard fields.allSatisfy({ !$0.isEmpty && $0.utf16.count <= 1024 }),
                  target.installationID == target.tuple.iosInstallationID else { throw Failure.invalid }
            let senderKey = try Curve25519.KeyAgreement.PrivateKey(
                rawRepresentation: bytes(request.senderPrivateKey, count: 32...32))
            guard senderKey.publicKey.rawRepresentation == (try bytes(request.senderPublicKey, count: 32...32))
            else { throw Failure.invalid }
            let recipientKey = try Curve25519.KeyAgreement.PublicKey(
                rawRepresentation: bytes(request.recipientPublicKey, count: 32...32))
            let plaintext = try bytes(request.plaintext, count: 1...16_368)
            let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
            let context = Data("cmux-phone-push-v2|\(target.keyID)|\(target.senderKeyID)|".utf8)
                + (try encoder.encode(target.tuple))
            var sender = try HPKE.Sender(recipientKey: recipientKey,
                ciphersuite: .Curve25519_SHA256_ChachaPoly, info: context, authenticatedBy: senderKey)
            let ciphertext = try sender.seal(plaintext, authenticating: context)
            let envelope = Envelope(installationID: target.installationID, keyID: target.keyID,
                senderKeyID: target.senderKeyID, tuple: target.tuple,
                encapsulatedKey: sender.encapsulatedKey.base64EncodedString(),
                ciphertext: ciphertext.base64EncodedString())
            FileHandle.standardOutput.write(try encoder.encode(envelope))
        } catch {
            // Decoding/provider diagnostics can contain plaintext or key material.
            FileHandle.standardError.write(Data("Push encryption failed\n".utf8))
            exit(1)
        }
    }
}
