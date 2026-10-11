import CryptoKit
import Foundation

/// Codec for a native producer whose fresh session/key was provisioned over a
/// private host-owned bootstrap. Does not provision keys, open IO or infer eligibility.
struct NativePushSourceFrameEncoder {
    enum Failure: Error { case invalidInput }
    private let sessionID: String
    private let key: SymmetricKey

    init(sessionID: String, key: Data) throws {
        guard key.count == 32, let uuid = UUID(uuidString: sessionID),
              uuid.uuidString.lowercased() == sessionID else { throw Failure.invalidInput }
        self.sessionID = sessionID
        self.key = SymmetricKey(data: key)
    }

    /// The owning producer retains the same body/correlation/expiry until a
    /// durable capture receipt. Sequence ownership belongs to that producer.
    func encode(body: Data, sequence: UInt64) throws -> Data {
        guard sequence > 0, sequence < 9_007_199_254_740_991,
              body.count <= 1_048_576 else { throw Failure.invalidInput }
        var authenticated = Data()
        for field in [Data("cmux-app.native.push.v1".utf8), Data(sessionID.utf8),
                      Data(String(sequence).utf8), body] {
            var length = UInt32(field.count).bigEndian
            withUnsafeBytes(of: &length) { authenticated.append(contentsOf: $0) }
            authenticated.append(field)
        }
        let proof = Data(HMAC<SHA256>.authenticationCode(for: authenticated, using: key))
        return try JSONSerialization.data(withJSONObject: [
            "version": 1, "sessionID": sessionID, "sequence": sequence,
            "body": body.base64EncodedString(), "proof": proof.base64EncodedString()
        ], options: [.sortedKeys])
    }
}
