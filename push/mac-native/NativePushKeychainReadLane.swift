import Foundation

/// One blocking read on a dedicated queue, shared by concurrent callers. Cancellation
/// removes/resumes a waiter without waiting for Security.framework or canceling another
/// caller's read. Results are never cached and this lane never changes Keychain policy.
final class NativePushKeychainReadLane<Value: Sendable>: @unchecked Sendable {
    enum Failure: Error { case timedOut }
    private let queue: DispatchQueue
    private let work: @Sendable () throws -> Value
    private let lock = NSLock()
    private var running = false
    private var waiters: [UUID: CheckedContinuation<Value, any Error>] = [:]

    init(label: String, work: @escaping @Sendable () throws -> Value) {
        queue = DispatchQueue(label: label, qos: .utility)
        self.work = work
    }

    func value(timeoutNanoseconds: UInt64? = nil) async throws -> Value {
        guard let timeoutNanoseconds else { return try await waitForValue() }
        guard timeoutNanoseconds > 0 else { throw Failure.timedOut }
        return try await withThrowingTaskGroup(of: Value.self) { group in
            group.addTask { try await self.waitForValue() }
            group.addTask {
                try await Task.sleep(nanoseconds: timeoutNanoseconds)
                throw Failure.timedOut
            }
            defer { group.cancelAll() }
            return try await group.next()!
        }
    }

    private func waitForValue() async throws -> Value {
        let token = UUID()
        return try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                lock.lock()
                // Covers cancellation before registration; cancellation after this
                // check waits on the same lock and removes the registered waiter.
                if Task.isCancelled {
                    lock.unlock()
                    continuation.resume(throwing: CancellationError())
                    return
                }
                waiters[token] = continuation
                let start = !running
                running = true
                lock.unlock()
                if start { queue.async { self.read() } }
            }
        } onCancel: {
            self.cancel(token)
        }
    }

    private func cancel(_ token: UUID) {
        lock.lock()
        let continuation = waiters.removeValue(forKey: token)
        lock.unlock()
        continuation?.resume(throwing: CancellationError())
    }

    private func read() {
        lock.lock()
        if waiters.isEmpty {
            running = false
            lock.unlock()
            return
        }
        lock.unlock()
        let result = Result { try work() }
        lock.lock()
        let completed = Array(waiters.values)
        waiters.removeAll()
        running = false
        lock.unlock()
        for continuation in completed { continuation.resume(with: result) }
    }
}
