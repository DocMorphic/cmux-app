import Foundation
#if canImport(CmuxPhonePush)
import CmuxPhonePush
#endif

/// Projects the authenticated native producer into the private helper protocol.
/// The app owner supplies verified identity, settings and its injected clock.
@MainActor
final class NativePushSourceMessages {
    struct TrustedPhone: Equatable, Sendable {
        let phoneBuildID: String
        let installationID: String
        let keyID: String
        let publicKey: Data
        var wire: [String: Any] {
            ["phoneBuildID": phoneBuildID, "installationID": installationID, "keyID": keyID,
             "publicKey": publicKey.base64EncodedString()]
        }
    }
    struct Identity: Equatable, Sendable {
        let accountID: String
        let sessionGeneration: UInt64
        let teamID: String?
        let teamGeneration: UInt64
        let macDeviceID: String
        let macInstanceTag: String
        let macBuildID: String
    }
    struct Settings: Equatable, Sendable {
        let forwardingEnabled: Bool
        let mode: String
        let admission: String
        let hideContent: Bool
    }
    enum Failure: Error { case invalidInput }
    private struct PeerTuple: Decodable {
        let accountID: String?
        let teamID: String?
        let iosBuildID: String
        let iosInstallationID: String
        let macDeviceID: String?
        let macInstanceTag: String?
        let macBuildID: String?
    }
    private struct PeerDescriptor: Decodable { let keyID: String; let publicKey: Data }

    /// Public pinned schema from the audited native peer store. Unrelated and
    /// malformed rows grant nothing. Conflicting aliases cannot choose a key.
    static func trustedPhones(rows: [(tuple: Data, descriptor: Data)], identity: Identity) throws -> [TrustedPhone] {
        guard rows.count <= 128 else { throw Failure.invalidInput }
        var phones: [TrustedPhone] = []
        for row in rows {
            guard row.tuple.count <= 16_384, row.descriptor.count <= 16_384,
                  let tuple = try? JSONDecoder().decode(PeerTuple.self, from: row.tuple),
                  tuple.accountID == identity.accountID, tuple.teamID == nil,
                  tuple.macDeviceID == identity.macDeviceID, tuple.macInstanceTag == identity.macInstanceTag,
                  tuple.macBuildID == identity.macBuildID,
                  ["io.github.docmorphic.cmuxapp", "io.github.docmorphic.cmuxapp.debug"].contains(tuple.iosBuildID),
                  let descriptor = try? JSONDecoder().decode(PeerDescriptor.self, from: row.descriptor) else { continue }
            let phone = TrustedPhone(phoneBuildID: tuple.iosBuildID, installationID: tuple.iosInstallationID,
                keyID: descriptor.keyID, publicKey: descriptor.publicKey)
            guard descriptor.publicKey.count == 32,
                  [phone.phoneBuildID, phone.installationID, phone.keyID].allSatisfy({ !$0.isEmpty && $0.utf16.count <= 1024 &&
                      !$0.unicodeScalars.contains(where: { $0.value < 32 }) }) else { continue }
            guard !phones.contains(where: { $0.phoneBuildID == phone.phoneBuildID && $0.installationID == phone.installationID })
            else { throw Failure.invalidInput }
            phones.append(phone)
        }
        return phones.sorted { [$0.phoneBuildID, $0.installationID].lexicographicallyPrecedes([$1.phoneBuildID, $1.installationID]) }
    }
    private var identity: Identity?
    private var epoch = UUID().uuidString.lowercased()

    /// A real auth transition must not preserve the old source epoch, including
    /// A → B → A. Temporary authority loss uses an invalidate message instead.
    func invalidate() { identity = nil; epoch = UUID().uuidString.lowercased() }

    func host(identity next: Identity?, confirmedSignedOut: Bool, settings: Settings,
              nowEpochSeconds: Int, trustedPhones: [TrustedPhone] = []) throws -> Data {
        guard nowEpochSeconds >= 0, nowEpochSeconds <= (9_007_199_254_740_991 - 30_000) / 1000 else {
            throw Failure.invalidInput
        }
        let now = nowEpochSeconds * 1000
        guard let next else {
            if confirmedSignedOut {
                invalidate()
                return try encode(["type": "host", "snapshot": [
                    "state": "signed-out", "observedAt": now, "validUntil": now + 30_000]])
            }
            // Unknown bootstrap/team/token state is not proof of logout.
            return try encode(["type": "invalidate"])
        }
        guard !next.accountID.isEmpty, !next.macDeviceID.isEmpty,
              !next.macInstanceTag.isEmpty, !next.macBuildID.isEmpty,
              next.teamID == nil || next.teamID?.isEmpty == false,
              ["always", "onlyWhenAway"].contains(settings.mode),
              ["allowed", "forwarding_disabled", "suppressed_mac_active", "unknown"].contains(settings.admission)
        else { throw Failure.invalidInput }
        guard trustedPhones.count <= 128,
              trustedPhones.allSatisfy({ $0.publicKey.count == 32 && [$0.phoneBuildID, $0.installationID, $0.keyID].allSatisfy {
                  !$0.isEmpty && $0.utf16.count <= 1024 && !$0.unicodeScalars.contains(where: { $0.value < 32 })
              } }),
              Set(trustedPhones.map { [$0.phoneBuildID, $0.installationID] }).count == trustedPhones.count
        else { throw Failure.invalidInput }
        if identity != next { identity = next; epoch = UUID().uuidString.lowercased() }
        return try encode(["type": "host", "snapshot": [
            "state": "ready", "epoch": epoch, "observedAt": now, "validUntil": now + 30_000,
            "trustedPhones": trustedPhones.map(\.wire),
            // The native phone key exchange/recipient tuple is account scoped.
            // Keep verified selected-team identity/generation in the source epoch
            // above, while matching its actual teamID:null encryption contract.
            "authority": ["accountID": next.accountID, "teamID": NSNull(),
                "macDeviceID": next.macDeviceID, "macInstanceTag": next.macInstanceTag, "macBuildID": next.macBuildID],
            "settings": ["forwardingEnabled": settings.forwardingEnabled, "mode": settings.mode,
                "admission": settings.admission, "hideContent": settings.hideContent]]])
    }

    /// Called only after the native phone gate/authentication. Payload mapping
    /// preserves explicit IDs, text, routing and the original 120-second expiry.
    func capture(_ payload: PhonePushPayload, identity next: Identity, settings: Settings,
                 nowEpochSeconds: Int, correlationID: UUID = UUID(), trustedPhones: [TrustedPhone] = []) throws -> [Data] {
        guard settings.forwardingEnabled, payload.badgeCount >= 0,
              payload.badgeCount <= 2_147_483_647,
              payload.macDeviceId == next.macDeviceID, payload.macInstanceTag == next.macInstanceTag,
              nowEpochSeconds >= 0, nowEpochSeconds <= (9_007_199_254_740_991 - 120_000) / 1000
        else { throw Failure.invalidInput }
        if payload.kind == .notify {
            guard ["", "none", "text"].contains(payload.replyShape) else {
                throw Failure.invalidInput
            }
        } else {
            guard !payload.notificationIds.isEmpty, payload.notificationIds.count <= 4096 else { throw Failure.invalidInput }
        }
        let state = try host(identity: next, confirmedSignedOut: false, settings: settings,
            nowEpochSeconds: nowEpochSeconds, trustedPhones: trustedPhones)
        var event: [String: Any] = ["kind": payload.kind.rawValue,
            "correlationID": correlationID.uuidString.lowercased(), "badgeCount": payload.badgeCount,
            "hideContent": payload.hideContent]
        if payload.kind == .notify {
            event.merge(["title": payload.title, "subtitle": payload.subtitle, "body": payload.body,
                "replyShape": payload.replyShape.isEmpty ? "none" : payload.replyShape,
                "retargetsToLiveSurfaceOwner": payload.retargetsToLiveSurfaceOwner,
                "workspaceId": payload.workspaceId as Any? ?? NSNull(), "surfaceId": payload.surfaceId as Any? ?? NSNull(),
                "notificationId": payload.notificationId as Any? ?? NSNull()]) { _, value in value }
        } else { event["notificationIds"] = payload.notificationIds }
        let capture = try encode(["type": "capture", "request": ["event": event, "sourceEpoch": epoch,
            "phoneEligible": payload.kind == .notify, "expiresAt": (nowEpochSeconds + 120) * 1000]])
        return [state, capture]
    }

    private func encode(_ value: [String: Any]) throws -> Data {
        let bytes = try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
        guard bytes.count <= 1_048_576 else { throw Failure.invalidInput }
        return bytes
    }
}
