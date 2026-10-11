import CoreFoundation
import Foundation

/// Receipt and replay state for the native owner's private helper process.
/// This type performs no IO and never treats persisted data as live authority.
@MainActor
final class NativePushSourceDeliveryQueue {
    enum Failure: Error { case invalidInput, invalidReceipt, unavailable }
    enum Admission: Equatable { case accepted, rejected, retireTransport }
    enum ReceiptAction: Equatable { case advance, duplicate, retryLater }
    private struct Binding: Equatable {
        let epoch: String
        let account: String
        let team: String?
        let device: String
        let instance: String
        let build: String
    }
    private struct Host {
        let body: Data
        let revision: UInt64
        let observedAt: Int64
        let validUntil: Int64
        let binding: Binding?
        let forwarding: Bool
        let hideContent: Bool
        func isLive(_ now: Int64) -> Bool { observedAt <= now && now < validUntil }
    }
    private struct Capture {
        let id: UUID
        let body: Data
        let expiresAt: Int64
    }
    private enum Role { case bootstrap, host(UInt64), capture(UUID) }
    private struct Pending { let wire: Data; let sequence: UInt64; let role: Role }
    private struct Receipt: Equatable {
        let sequence: UInt64
        let kind: String
        let acknowledged: Bool
    }
    private let capacity: Int
    private let byteCapacity: Int
    private var captures: [Capture] = []
    private var host: Host?
    private var revision: UInt64 = 0
    private var encoder: NativePushSourceFrameEncoder?
    private var sequence: UInt64 = 0
    private var sentHostRevision: UInt64?
    private var pending: Pending?
    private var lastReceipt: Receipt?

    init(capacity: Int = 128, byteCapacity: Int = 8 * 1_048_576) throws {
        guard (1...128).contains(capacity), (1...8 * 1_048_576).contains(byteCapacity) else {
            throw Failure.invalidInput
        }
        self.capacity = capacity
        self.byteCapacity = byteCapacity
    }

    var retainedCount: Int { captures.count }
    var retainedBytes: Int { captures.reduce(0) { $0 + $1.body.count } }
    var hasSession: Bool { encoder != nil }

    /// Accepts only one native host/control or one atomic host/capture batch.
    /// A rejected batch does not update host state or retain a prefix.
    func offer(_ messages: [Data], now: Int64) -> Admission {
        do {
            guard Self.validTime(now), (1...2).contains(messages.count),
                  messages.allSatisfy({ !$0.isEmpty && $0.count <= 1_048_576 }) else {
                return .rejected
            }
            let control = try Self.object(messages[0])
            if control["type"] as? String == "invalidate" {
                guard messages.count == 1, control.count == 1 else { return .rejected }
                invalidate()
                return .retireTransport
            }
            guard revision < UInt64.max else { return .rejected }
            let next = try Self.parseHost(control, body: messages[0], revision: revision + 1)
            guard next.isLive(now) else { return .rejected }
            var accepted = captures.filter { $0.expiresAt > now || pendingCaptureID == $0.id }
            if messages.count == 2 {
                let message = try Self.object(messages[1])
                guard message.count == 2, message["type"] as? String == "capture",
                      let request = message["request"] as? [String: Any],
                      let expiry = Self.integer(request["expiresAt"]), expiry % 1000 == 0,
                      let epoch = request["sourceEpoch"] as? String,
                      epoch == next.binding?.epoch else { return .rejected }
                // Expired native events need no renewed expiry or wire admission.
                if expiry > now { accepted.append(.init(id: UUID(), body: messages[1], expiresAt: expiry)) }
            }
            guard accepted.count <= capacity,
                  accepted.reduce(0, { $0 + $1.body.count }) <= byteCapacity else { return .rejected }
            let retire = host.map {
                $0.binding != next.binding || ($0.forwarding && !next.forwarding) ||
                (!$0.hideContent && next.hideContent)
            } ?? false
            captures = accepted
            host = next
            revision = next.revision
            if retire { disconnect(); return .retireTransport }
            return .accepted
        } catch { return .rejected }
    }

    /// Called only after the owner has retired/reaped the previous private child.
    /// A new connection cannot replay stale queued host controls.
    func begin(sessionID: String, key: Data, now: Int64) throws {
        guard Self.validTime(now), encoder == nil, host?.isLive(now) == true else {
            throw Failure.unavailable
        }
        let next = try NativePushSourceFrameEncoder(sessionID: sessionID, key: key)
        let wire = try JSONSerialization.data(withJSONObject: ["type": "bootstrap", "version": 1,
            "sessionID": sessionID, "key": key.base64EncodedString()], options: [.sortedKeys])
        encoder = next
        sequence = 0
        sentHostRevision = nil
        lastReceipt = nil
        pending = .init(wire: wire, sequence: 0, role: .bootstrap)
    }

    /// Until a receipt, returns the exact same frame even if its lease/expiry
    /// passes. An uncertain acknowledgment must not change sequence/body.
    func next(now: Int64) throws -> Data? {
        guard Self.validTime(now) else { throw Failure.invalidInput }
        if let pending { return pending.wire }
        guard let encoder else { return nil }
        guard let host, host.isLive(now) else { return nil }
        guard sequence > 0, sequence < 9_007_199_254_740_991 else { throw Failure.unavailable }
        let body: Data
        let role: Role
        if sentHostRevision != host.revision {
            body = host.body
            role = .host(host.revision)
        } else {
            captures.removeAll { $0.expiresAt <= now }
            guard let capture = captures.first else { return nil }
            body = capture.body
            role = .capture(capture.id)
        }
        let wire = try encoder.encode(body: body, sequence: sequence)
        pending = .init(wire: wire, sequence: sequence, role: role)
        return wire
    }

    func receive(_ bytes: Data) throws -> ReceiptAction {
        guard bytes.count <= 512 else { throw Failure.invalidReceipt }
        let value = try Self.object(bytes)
        guard value.count == 3, let number = Self.integer(value["sequence"]),
              let kind = value["kind"] as? String,
              let acknowledged = Self.boolean(value["acknowledged"]) else { throw Failure.invalidReceipt }
        let receipt = Receipt(sequence: UInt64(number), kind: kind, acknowledged: acknowledged)
        if lastReceipt == receipt { return .duplicate }
        guard let pending, receipt.sequence == pending.sequence else { throw Failure.invalidReceipt }
        if !acknowledged {
            guard case .capture = pending.role,
                  ["source-unavailable", "backpressure", "storage-unavailable"].contains(kind) else {
                throw Failure.invalidReceipt
            }
            // The helper explicitly did not commit this sequence. A fresh live
            // host may replace it before replaying the unchanged capture body.
            self.pending = nil
            sentHostRevision = nil
            return .retryLater
        }
        switch pending.role {
        case .bootstrap:
            guard kind == "ready" else { throw Failure.invalidReceipt }
        case .host(let revision):
            guard kind == "state-updated" else { throw Failure.invalidReceipt }
            sentHostRevision = revision
        case .capture(let id):
            guard ["captured", "duplicate", "expired", "retired"].contains(kind),
                  captures.first?.id == id else { throw Failure.invalidReceipt }
            captures.removeFirst()
        }
        lastReceipt = receipt
        self.pending = nil
        sequence += 1
        return .advance
    }

    /// Transport loss retains original capture bodies. Current authority must
    /// be refreshed by the native owner before reconnect/replay.
    func invalidate() { disconnect(); host = nil }
    private func disconnect() {
        encoder = nil
        pending = nil
        lastReceipt = nil
        sentHostRevision = nil
        sequence = 0
    }
    private var pendingCaptureID: UUID? {
        if case .capture(let id) = pending?.role { return id }
        return nil
    }
    private static func object(_ data: Data) throws -> [String: Any] {
        guard String(data: data, encoding: .utf8) != nil,
              let value = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw Failure.invalidInput
        }
        return value
    }
    private static func validTime(_ value: Int64) -> Bool { (0...9_007_199_254_740_991).contains(value) }
    private static func integer(_ value: Any?) -> Int64? {
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
              number.doubleValue.isFinite, number.doubleValue.rounded(.towardZero) == number.doubleValue,
              number.doubleValue >= 0, number.doubleValue <= 9_007_199_254_740_991 else { return nil }
        return number.int64Value
    }
    private static func boolean(_ value: Any?) -> Bool? {
        guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { return nil }
        return number.boolValue
    }
    private static func parseHost(_ control: [String: Any], body: Data, revision: UInt64) throws -> Host {
        guard control.count == 2, control["type"] as? String == "host",
              let snapshot = control["snapshot"] as? [String: Any],
              let observed = integer(snapshot["observedAt"]), let until = integer(snapshot["validUntil"]),
              until > observed, until - observed <= 30_000 else { throw Failure.invalidInput }
        if snapshot["state"] as? String == "signed-out" {
            guard snapshot.count == 3 else { throw Failure.invalidInput }
            return .init(body: body, revision: revision, observedAt: observed, validUntil: until,
                binding: nil, forwarding: false, hideContent: true)
        }
        guard snapshot.count == 6, snapshot["state"] as? String == "ready",
              let epoch = snapshot["epoch"] as? String, !epoch.isEmpty,
              let authority = snapshot["authority"] as? [String: Any], authority.count == 5,
              let account = authority["accountID"] as? String, !account.isEmpty,
              let device = authority["macDeviceID"] as? String, !device.isEmpty,
              let instance = authority["macInstanceTag"] as? String, !instance.isEmpty,
              let build = authority["macBuildID"] as? String, !build.isEmpty,
              authority["teamID"] is NSNull || authority["teamID"] is String,
              let settings = snapshot["settings"] as? [String: Any], settings.count == 4,
              let forwarding = boolean(settings["forwardingEnabled"]), let hide = boolean(settings["hideContent"]),
              let mode = settings["mode"] as? String, ["always", "onlyWhenAway"].contains(mode),
              let admission = settings["admission"] as? String,
              ["allowed", "forwarding_disabled", "suppressed_mac_active", "unknown"].contains(admission) else {
            throw Failure.invalidInput
        }
        return .init(body: body, revision: revision, observedAt: observed, validUntil: until,
            binding: .init(epoch: epoch, account: account, team: authority["teamID"] as? String,
                device: device, instance: instance, build: build), forwarding: forwarding, hideContent: hide)
    }
}
