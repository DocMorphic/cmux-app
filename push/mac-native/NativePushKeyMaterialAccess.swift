import CmuxPhonePush
import Foundation

/// Retains the existing bundle-scoped Keychain identity and policy. Security may
/// still require a user decision; it must never occupy the app's main actor.
enum NativePushKeyMaterialAccess {
    enum Failure: Error { case unavailable }
    private static let lane = NativePushKeychainReadLane<PhonePushKeyMaterial>(
        label: "com.cmuxterm.android-push.key-material"
    ) {
        try PhonePushKeyMaterial.current(bundleID: Bundle.main.bundleIdentifier ?? "cmux")
    }

    static func current() async throws -> PhonePushKeyMaterial {
        do {
            // Finish before Android's optional 3-second setup attempt expires;
            // the underlying Security call remains on its one dedicated worker.
            let value = try await lane.value(timeoutNanoseconds: 2_000_000_000)
            try Task.checkCancellation()
            return value
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw Failure.unavailable
        }
    }
}
