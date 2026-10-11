import Foundation

@main struct NativePushHooksFixture {
    @MainActor static func main() throws {
        let mapper = NativePushSourceMessages(), now = 1_800_000_000
        func identity(team: String? = "fixture-team", generation: UInt64 = 1, session: UInt64 = 7) -> NativePushSourceMessages.Identity {
            .init(accountID: "fixture-account", sessionGeneration: session, teamID: team, teamGeneration: generation,
                macDeviceID: "fixture-mac", macInstanceTag: "stable", macBuildID: "fixture-build")
        }
        func settings(enabled: Bool = true, admission: String = "allowed", hidden: Bool = false) -> NativePushSourceMessages.Settings {
            .init(forwardingEnabled: enabled, mode: "onlyWhenAway", admission: admission, hideContent: hidden)
        }
        func payload(kind: PhonePushPayloadKind = .notify, reply: String = "", mac: String = "fixture-mac",
                     ids: [String] = ["notice-1", "other-中"], body: String = "Line one\n\"next\" \\ /") -> PhonePushPayload {
            .init(kind: kind, title: "Ready λ 中 👩🏽‍💻", subtitle: "Fixture workspace", body: body,
                replyShape: reply, workspaceId: "fixture-workspace", surfaceId: nil, retargetsToLiveSurfaceOwner: true,
                macDeviceId: mac, macInstanceTag: "stable", notificationId: "notice-1", notificationIds: ids,
                badgeCount: kind == .notify ? 3 : 0, hideContent: false)
        }
        func json(_ data: Data) throws -> Any { try JSONSerialization.jsonObject(with: data) }
        func host(_ value: NativePushSourceMessages.Identity? = identity(), signedOut: Bool = false) throws -> Any {
            try json(mapper.host(identity: value, confirmedSignedOut: signedOut, settings: settings(), nowEpochSeconds: now))
        }
        var result: [String: Any] = [:]
        result["first"] = try host()
        result["notify"] = try mapper.capture(payload(), identity: identity(), settings: settings(), nowEpochSeconds: now,
            correlationID: UUID(uuidString: "00000000-0000-4000-8000-000000000011")!).map(json)
        result["dismiss"] = try mapper.capture(payload(kind: .dismiss), identity: identity(),
            settings: settings(admission: "suppressed_mac_active"), nowEpochSeconds: now,
            correlationID: UUID(uuidString: "00000000-0000-4000-8000-000000000012")!).map(json)
        result["repeat"] = try host()
        result["unknown"] = try host(nil)
        result["recovered"] = try host()
        result["teamB"] = try host(identity(team: "fixture-other-team", generation: 2))
        result["teamA"] = try host(identity(generation: 3))
        result["signOut"] = try host(nil, signedOut: true)
        result["afterSignOut"] = try host(identity(generation: 3))
        result["admittedWhileActive"] = try mapper.capture(payload(reply: "text"), identity: identity(generation: 3),
            settings: settings(admission: "suppressed_mac_active", hidden: true), nowEpochSeconds: now,
            correlationID: UUID(uuidString: "00000000-0000-4000-8000-000000000013")!).map(json)
        var rejected = 0
        let invalid: [() throws -> Any] = [
            { try mapper.capture(payload(), identity: identity(), settings: settings(enabled: false), nowEpochSeconds: now) },
            { try mapper.capture(payload(reply: "html"), identity: identity(), settings: settings(), nowEpochSeconds: now) },
            { try mapper.capture(payload(mac: "other-mac"), identity: identity(), settings: settings(), nowEpochSeconds: now) },
            { try mapper.capture(payload(kind: .dismiss, ids: []), identity: identity(), settings: settings(), nowEpochSeconds: now) },
            { try mapper.capture(payload(body: String(repeating: "x", count: 1_048_576)), identity: identity(), settings: settings(), nowEpochSeconds: now) },
            { try mapper.host(identity: identity(), confirmedSignedOut: false, settings: settings(), nowEpochSeconds: -1) }
        ]
        for operation in invalid {
            do { _ = try operation(); throw NSError(domain: "Fixture unexpectedly accepted invalid input", code: 1) }
            catch NativePushSourceMessages.Failure.invalidInput { rejected += 1 }
        }
        result["rejected"] = rejected
        FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys]))
    }
}
