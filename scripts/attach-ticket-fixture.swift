import Foundation
import CMUXMobileCore

// Synthetic fixture data only. No account, route or token is read from this Mac.
func json(_ value: Any) throws -> Data { try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]) }
func object(_ data: Data) throws -> [String: Any] { try JSONSerialization.jsonObject(with: data) as! [String: Any] }
func snapshot(_ ticket: CmxAttachTicket) -> [String: Any] {
    let routes: [[String: Any]] = ticket.routes.map { route in
        var endpoint: [String: Any]
        switch route.endpoint {
        case let .hostPort(host, port): endpoint = ["type": "host_port", "host": host, "port": port]
        case let .url(url): endpoint = ["type": "url", "url": url]
        case let .peer(identity, hints):
            endpoint = ["type": "peer", "identity": identity.endpointID, "hints": hints.map { hint -> [String: Any] in
                ["kind": hint.kind.rawValue, "value": hint.value, "source": hint.source.rawValue,
                 "privacy": hint.privacyScope.rawValue,
                 "observed": hint.observedAt.map { Int64(($0.timeIntervalSince1970 * 1000).rounded(.down)) } as Any? ?? NSNull(),
                 "expires": hint.expiresAt.map { Int64(($0.timeIntervalSince1970 * 1000).rounded(.down)) } as Any? ?? NSNull(),
                 "profileSource": hint.networkProfile?.source.rawValue as Any? ?? NSNull(),
                 "profileId": hint.networkProfile?.profileID as Any? ?? NSNull(),
                 "inert": !hint.isSafeForCurrentWireFormat]
            }]
        }
        return ["id": route.id, "kind": route.kind.rawValue, "priority": route.priority, "endpoint": endpoint]
    }
    return ["workspace": ticket.workspaceID, "terminal": ticket.terminalID as Any? ?? NSNull(),
            "device": ticket.macDeviceID, "name": ticket.macDisplayName as Any? ?? NSNull(),
            "email": ticket.macUserEmail as Any? ?? NSNull(), "user": ticket.macUserID as Any? ?? NSNull(),
            "compatibility": ticket.macPairingCompatibilityVersion as Any? ?? NSNull(),
            "appVersion": ticket.macAppVersion as Any? ?? NSNull(), "appBuild": ticket.macAppBuild as Any? ?? NSNull(),
            "expires": ticket.expiresAt.map { Int64(($0.timeIntervalSince1970 * 1000).rounded(.down)) } as Any? ?? NSNull(),
            "token": ticket.authToken?.trimmingCharacters(in: .whitespacesAndNewlines) as Any? ?? NSNull(), "routes": routes]
}
var fixtures: [[String: Any]] = []
func add(_ name: String, _ payload: [String: Any], host: String = "attach", accepted: Bool = true) throws {
    let data = try json(payload)
    let encoded = data.base64EncodedString().replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    let url = "cmux-ios://\(host)?v=1&payload=\(encoded)"
    var fixture: [String: Any] = ["name": name, "json": String(decoding: data, as: UTF8.self), "url": url, "accepted": accepted]
    do {
        let ticket = try CmxAttachTicketInput.decode(url)
        guard accepted else { fatalError("Expected Swift rejection: \(name)") }
        fixture["expected"] = snapshot(ticket)
        if host == "attach" {
            let coder = CmxAttachTicketCompactCoder()
            let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
            let raw = try coder.isCompactPayload(data) ? coder.decode(data) : decoder.decode(CmxAttachTicket.self, from: data)
            fixture["rawExpected"] = snapshot(raw)
        }
    } catch {
        guard !accepted else { throw error }
    }
    fixtures.append(fixture)
}

let routes = [
    try CmxAttachRoute(id: "tailscale", kind: .tailscale, endpoint: .hostPort(host: "100.64.0.7", port: 58465)),
    try CmxAttachRoute(id: "tailscale_2", kind: .tailscale, endpoint: .hostPort(host: "mac.example.ts.net", port: 58466), priority: -4),
    try CmxAttachRoute(id: "custom", kind: .iroh, endpoint: .peer(identity: CmxIrohPeerIdentity(endpointID: String(repeating: "a", count: 64)), pathHints: []))
]
let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601; encoder.outputFormatting = [.sortedKeys]
let ticket = try CmxAttachTicket(workspaceID: "workspace-🧪", terminalID: "surface", macDeviceID: "ABCDEFAB-1234-5678-ABCD-123456789ABC",
    macDisplayName: "Fixture Mac", macUserID: "fixture-user", macPairingCompatibilityVersion: 1,
    macAppVersion: "0.70.0", macAppBuild: "100", routes: routes,
    expiresAt: Date(timeIntervalSince1970: 4_102_444_800), authToken: " fixture-token-not-a-credential ")
let full = try object(encoder.encode(ticket))
try add("full_mixed", full)
try add("compact_mixed", object(CmxAttachTicketCompactCoder().encode(ticket, routeDisclosureMode: .legacyPrivateNetworkCompatibility)))
try add("compact_iroh_identity", object(CmxAttachTicketCompactCoder().encode(ticket, routeDisclosureMode: .irohIdentityOnly)))
var changed = full; changed["expiresAt"] = "2020-01-01T00:00:00Z"; try add("expired_full_still_decodes", changed)
changed = full; changed.removeValue(forKey: "macPairingCompatibilityVersion"); changed.removeValue(forKey: "auth_token"); changed["authToken"] = "alias-token"; try add("camel_token_unknown_compatibility", changed)
changed = full; changed["authToken"] = "ignored-alias"; try add("canonical_token_wins", changed)
changed = full; changed["auth_token"] = NSNull(); changed["authToken"] = "alias-token"; try add("null_canonical_uses_alias", changed)
changed = full; changed["workspaceID"] = ""; changed["terminalID"] = NSNull(); changed["expiresAt"] = NSNull(); try add("mac_wide_without_expiry", changed)
var compact: [String: Any] = ["v": 1, "d": "opaque CASE ", "u": "fixture@example.invalid", "e": "ignored", "n": "ignored",
    "auth_token": "ignored-compact-token", "r": [["i": "explicit", "k": "tailscale", "e": ["t": "host_port", "h": "100.64.0.8", "p": 58465]]]]
try add("first_compact_revision", compact)
compact["r"] = [["k": "tailscale", "e": ["t": "host_port", "h": "100.64.0.8", "p": 58465, "i": "ignored"]]]
try add("explicit_endpoint_type_wins", compact)
var malformedCompact = compact
malformedCompact["r"] = [["k": "tailscale", "e": ["t": "host_port", "h": "100.64.0.8", "p": 58465, "i": 7]]]
try add("compact_type_checks_unused_fields", malformedCompact, accepted: false)
changed = full; changed["expiresAt"] = "2030-01-01T00:00:00.500Z"; try add("fractional_date", changed)
changed = full; changed["routes"] = [["id": "web", "kind": "websocket", "endpoint": ["type": "url", "url": "wss://fixture.invalid/rpc"]]]; try add("websocket_shape", changed)
changed = full; changed["routes"] = [["id": "loopback", "kind": "debug_loopback", "endpoint": ["type": "host_port", "host": "127.0.0.1", "port": 58465]]]; try add("loopback_is_structural_only", changed)
func peerPayload(_ fields: [String: Any]) -> [String: Any] {
    var value = full
    var endpoint: [String: Any] = ["type": "peer", "id": String(repeating: "a", count: 64)]
    endpoint.merge(fields) { _, new in new }
    value["routes"] = [["id": "iroh", "kind": "iroh", "endpoint": endpoint]]
    return value
}
try add("legacy_peer_hints", peerPayload(["relay_hint": "relay_1", "direct_addrs": ["100.64.0.9:4444"], "relay_url": "https://relay.example.com"]))
try add("unsafe_legacy_hint_is_inert", peerPayload(["direct_addrs": ["not-an-address"], "relay_url": "http://127.0.0.1"]))
let publicHint: [String: Any] = ["kind": "direct_address", "value": "8.8.8.8:4444", "source": "native", "privacy_scope": "public_internet"]
try add("current_public_hint", peerPayload(["path_hints": [publicHint]]))
let privateHint: [String: Any] = ["kind": "direct_address", "value": "100.64.0.9:4444", "source": "tailscale", "privacy_scope": "private_network",
    "observed_at": "2030-01-01T00:00:00Z", "expires_at": "2030-01-01T01:00:00Z",
    "network_profile": ["source": "tailscale", "profile_id": String(repeating: "b", count: 64)]]
try add("current_private_hint", peerPayload(["path_hints": [privateHint]]))
var oldPrivate = privateHint; oldPrivate.removeValue(forKey: "expires_at"); try add("incomplete_private_hint_is_inert", peerPayload(["path_hints": [oldPrivate]]))
let ancient: [String: Any] = ["version": 1, "mac_device_id": "ABCDEFAB-1234-5678-ABCD-123456789ABC", "mac_display_name": "Fixture",
    "host": "100.64.0.7", "port": 58465, "expires_at": "2100-01-01T00:00:00Z", "transport": "tailscale"]
try add("ancient_pair", ancient, host: "pair")
changed = ancient; changed["expires_at"] = "2020-01-01T00:00:00Z"; try add("expired_ancient_pair", changed, host: "pair", accepted: false)
changed = ancient; changed["some_secret"] = "synthetic"; try add("ancient_forbids_secret_fields", changed, host: "pair", accepted: false)
for (name, key, value) in [("unknown_version", "version", 2 as Any), ("no_routes", "routes", [] as Any),
    ("blank_token", "auth_token", " \n" as Any), ("wrong_workspace_type", "workspaceID", 7 as Any), ("invalid_date", "expiresAt", "yesterday" as Any)] {
    changed = full; changed[key] = value; try add(name, changed, accepted: false)
}
changed = full; changed["routes"] = [["id": "bad", "kind": "iroh", "endpoint": ["type": "host_port", "host": "100.64.0.7", "port": 58465]]]; try add("endpoint_kind_mismatch", changed, accepted: false)
changed = full; changed["routes"] = [["id": "bad", "kind": "iroh", "endpoint": ["type": "peer", "id": String(repeating: "A", count: 64)]]]; try add("noncanonical_peer", changed, accepted: false)
try add("too_many_hints", peerPayload(["path_hints": Array(repeating: publicHint, count: 17)]), accepted: false)
for address in ["127.0.0.1:80", "10.0.0.1:80", "192.0.2.1:80", "[::1]:80", "[2001:db8::1]:80", "[::ffff:127.0.0.1]:80", "8.8.8.8:080", "[8.8.8.8]:80", "169.254.1.1:80", "[fe80::1]:80", "[fd00:ec2::254]:80", "[3fff::1]:80"] {
    var hint = publicHint; hint["value"] = address; try add("forbidden_public_\(address)", peerPayload(["path_hints": [hint]]), accepted: false)
}
for address in ["1.1.1.1:443", "[2606:4700:4700::1111]:443", "[::ffff:8.8.8.8]:443"] {
    var hint = publicHint; hint["value"] = address; try add("valid_public_\(address)", peerPayload(["path_hints": [hint]]))
}
for url in ["http://relay.example.com", "https://relay.local", "https://127.0.0.1", "https://user@relay.example.com", "https://relay.example.com/path", "https://relay.example.com?x=1"] {
    let hint: [String: Any] = ["kind": "relay_url", "value": url, "source": "native", "privacy_scope": "public_internet"]
    try add("forbidden_relay_\(url)", peerPayload(["path_hints": [hint]]), accepted: false)
}
var invalidPrivate = privateHint; invalidPrivate["expires_at"] = "2030-01-01T01:00:01Z"; try add("private_ttl_exceeded", peerPayload(["path_hints": [invalidPrivate]]), accepted: false)
invalidPrivate = privateHint; invalidPrivate["network_profile"] = ["source": "lan", "profile_id": String(repeating: "b", count: 64)]; try add("profile_source_mismatch", peerPayload(["path_hints": [invalidPrivate]]), accepted: false)
invalidPrivate = privateHint; invalidPrivate["expires_at"] = privateHint["observed_at"]; try add("zero_private_lifetime", peerPayload(["path_hints": [invalidPrivate]]), accepted: false)
var hint = privateHint; hint.removeValue(forKey: "network_profile"); hint["network_profile_id"] = String(repeating: "b", count: 64)
try add("legacy_profile_identifier", peerPayload(["path_hints": [hint]]))
hint = publicHint; hint["network_profile"] = privateHint["network_profile"]; try add("public_profile_rejected", peerPayload(["path_hints": [hint]]), accepted: false)
hint = privateHint; hint["source"] = "lan"; hint["privacy_scope"] = "local_network"; hint["network_profile"] = ["source": "lan", "profile_id": String(repeating: "b", count: 64)]
try add("current_lan_hint", peerPayload(["path_hints": [hint]]))
hint = privateHint; hint["source"] = "custom_vpn"; hint["network_profile"] = ["source": "custom_vpn", "profile_id": String(repeating: "b", count: 64)]
try add("current_custom_vpn_hint", peerPayload(["path_hints": [hint]]))
try add("empty_current_hints_override_legacy", peerPayload(["path_hints": [], "direct_addrs": 7, "relay_url": "not a url"]))
FileHandle.standardOutput.write(try json(fixtures))
