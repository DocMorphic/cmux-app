import Foundation

private final class HeldRead: @unchecked Sendable {
    let gate = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    private var starts = 0
    private var ends = 0
    func run() -> Int {
        precondition(!Thread.isMainThread)
        lock.lock(); starts += 1; let value = starts; lock.unlock()
        gate.wait()
        lock.lock(); ends += 1; lock.unlock()
        return value
    }
    var counts: (Int, Int) {
        lock.lock(); defer { lock.unlock() }; return (starts, ends)
    }
}

@main struct KeychainLaneFixture {
    @MainActor static func until(_ condition: () -> Bool) async throws {
        let deadline = ContinuousClock.now + .seconds(2)
        while !condition() {
            precondition(ContinuousClock.now < deadline)
            try await Task.sleep(nanoseconds: 1_000_000)
        }
    }

    @MainActor static func main() async throws {
        let held = HeldRead()
        let lane = NativePushKeychainReadLane<Int>(label: "fixture.blocking-keychain") { held.run() }
        let first = Task { @MainActor in try await lane.value() }
        try await until { held.counts.0 == 1 }
        // This main actor is executing while its original caller awaits the blocked read.
        var uiTicks = 0
        for _ in 0..<5 { await Task.yield(); uiTicks += 1 }
        precondition(uiTicks == 5 && held.counts.1 == 0)
        let joined = (0..<16).map { _ in Task { try await lane.value() } }
        let canceled = Task { try await lane.value() }
        try await Task.sleep(nanoseconds: 10_000_000)
        precondition(held.counts.0 == 1)
        canceled.cancel()
        do { _ = try await canceled.value; preconditionFailure("Canceled waiter returned a key") }
        catch is CancellationError {}
        precondition(held.counts.1 == 0, "Cancellation waited for Security")
        do { _ = try await lane.value(timeoutNanoseconds: 20_000_000); preconditionFailure("Deadline did not expire") }
        catch NativePushKeychainReadLane<Int>.Failure.timedOut {}
        precondition(held.counts.0 == 1 && held.counts.1 == 0)
        held.gate.signal()
        let firstValue = try await first.value
        precondition(firstValue == 1)
        for task in joined { let value = try await task.value; precondition(value == 1) }
        let second = Task { try await lane.value() }
        try await until { held.counts.0 == 2 }
        held.gate.signal()
        let secondValue = try await second.value
        precondition(secondValue == 2, "Result was cached across completed reads")

        let untouched = HeldRead()
        let canceledLane = NativePushKeychainReadLane<Int>(label: "fixture.cancel-before-read") { untouched.run() }
        let alreadyCanceled = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await canceledLane.value()
        }
        do { _ = try await alreadyCanceled.value; preconditionFailure("Pre-canceled read started") }
        catch is CancellationError {}
        precondition(untouched.counts.0 == 0)
        print("keychain lane: main actor responsive; 17 readers coalesced; canceled waiter leaves; deadline leaves; no stale cache; pre-canceled work refused")
    }
}
