import Foundation

/// Private owner-supplied helper transport. Retain exact capture messages until
/// durable receipts; refresh live host controls before reconnect/retry. Queue
/// admission here must never mean phone delivery.
@MainActor
protocol NativePushSourceSink: AnyObject {
    /// Atomically admits one host/capture batch to the bounded transport queue.
    /// A false result must not retain a partial batch or silently evict old work.
    func offer(_ messages: [Data]) -> Bool

    /// Synchronously fences the previous live source and all provider work.
    /// Close/retire the child channel before recovery; discard stale queued host
    /// controls and require fresh native authority before replaying captures.
    func invalidate()
}
