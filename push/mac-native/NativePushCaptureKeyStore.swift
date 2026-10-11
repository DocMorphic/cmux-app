import CryptoKit
import Foundation
import Security

/// Independent producer-storage key for one custom bundle. The repository calls
/// this off the main actor. A missing key cannot be recreated when ciphertext exists.
struct NativePushCaptureKeyStore: Sendable {
    enum Failure: Error { case unavailable }
    let bundleID: String

    func load(allowCreation: Bool) throws -> Data {
        guard !bundleID.isEmpty, bundleID.utf16.count <= 1024,
              !bundleID.unicodeScalars.contains(where: { $0.value < 32 }) else { throw Failure.unavailable }
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "cmux-app.native.capture.v1." + bundleID,
            kSecAttrAccount as String: "producer-storage", kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecSuccess {
            guard let bytes = result as? Data, bytes.count == 32 else { throw Failure.unavailable }
            return bytes
        }
        guard status == errSecItemNotFound, allowCreation else { throw Failure.unavailable }
        let key = SymmetricKey(size: .bits256)
        var bytes = key.withUnsafeBytes { Data($0) }
        defer { bytes.resetBytes(in: 0..<bytes.count) }
        var item = query
        item.removeValue(forKey: kSecReturnData as String); item.removeValue(forKey: kSecMatchLimit as String)
        item[kSecValueData as String] = bytes
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let saved = SecItemAdd(item as CFDictionary, nil)
        if saved == errSecDuplicateItem { return try load(allowCreation: false) }
        guard saved == errSecSuccess else { throw Failure.unavailable }
        return Data(bytes)
    }
}
