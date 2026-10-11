import Foundation
import Observation

@MainActor @Observable
final class NativePushPairingModel {
    typealias Request = @Sendable (String, String) async throws -> Data
    private(set) var offer: NativePushPairingOffer?
    private(set) var busy = false
    private(set) var failed = false
    private let request: Request
    private let now: () -> Int64
    private let onClear: () -> Void
    private var generation: UInt64 = 0
    private var task: Task<Void, Never>?
    var phoneBuildID = "io.github.docmorphic.cmuxapp"

    init(request: @escaping Request, now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
         onClear: @escaping () -> Void = {}) {
        self.request = request; self.now = now; self.onClear = onClear
    }
    func generate() {
        clear(); failed = false; busy = true
        let selected = generation, build = phoneBuildID, request = request
        task = Task { [weak self] in
            do {
                let body = try await request("offer", build)
                guard let self else { return }
                let next = try NativePushPairingOffer(body: body, phoneBuildID: build, now: self.now())
                guard selected == self.generation, !Task.isCancelled else {
                    Self.cancel(next.id, request: request); return
                }
                self.offer = next; self.busy = false
            } catch {
                guard let self, selected == self.generation, !Task.isCancelled else { return }
                self.failed = true; self.busy = false
            }
        }
    }
    func expire() { if let offer, now() >= offer.expiresAt { clear() } }
    func clear() {
        generation &+= 1; task?.cancel(); task = nil; busy = false
        onClear()
        if let offer { Self.cancel(offer.id, request: request) }
        offer = nil
    }
    private static func cancel(_ id: String, request: @escaping Request) {
        Task { _ = try? await request("cancel", id) }
    }
}
