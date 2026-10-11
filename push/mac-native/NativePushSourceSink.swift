import Foundation

/// Selected-team scope comes from the verified native identity, separately from
/// the account-scoped wire tuple. It contains no token or cached authorization.
struct NativePushSourceScope: Equatable, Sendable {
    let accountID: String
    let teamID: String?
    let macDeviceID: String
    let macInstanceTag: String
    let macBuildID: String
}

/// Private owner-supplied helper transport. Retain exact capture messages until
/// durable receipts; refresh live host controls before reconnect/retry. Queue
/// admission here must never mean phone delivery.
@MainActor
protocol NativePushSourceSink: AnyObject {
    /// Atomically admits one host/capture batch to the bounded transport queue.
    /// A false result must not retain a partial batch or silently evict old work.
    func offer(_ messages: [Data]) -> Bool

    func offer(_ messages: [Data], scope: NativePushSourceScope?) -> Bool

    /// Synchronously fences the previous live source and all provider work.
    /// Close/retire the child channel before recovery; discard stale queued host
    /// controls and require fresh native authority before replaying captures.
    func invalidate()

    /// Confirmed native auth/team incarnation retirement, unlike transport loss.
    func retireScope()
}

extension NativePushSourceSink {
    func offer(_ messages: [Data], scope: NativePushSourceScope?) -> Bool { offer(messages) }
    func retireScope() { invalidate() }
}
