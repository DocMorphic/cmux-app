import Foundation
import Observation

/// Uses the app's existing auth coordinator; it never reads or imports credentials.
@MainActor @Observable
final class NativeCompanionEmailSignInModel {
    enum Stage { case email, code }
    enum Failure { case invalidEmail, invalidCode, send, verify }
    var email = ""
    var code = ""
    private(set) var stage = Stage.email
    private(set) var busy = false
    private(set) var failure: Failure?
    private(set) var sentEmail = ""
    private(set) var completed = false
    @ObservationIgnored private let send: @MainActor (String) async throws -> Void
    @ObservationIgnored private let verify: @MainActor (String) async throws -> Void
    @ObservationIgnored private let authenticated: @MainActor () -> Bool
    @ObservationIgnored private let authBusy: @MainActor () -> Bool
    @ObservationIgnored private var operation: Task<Void, Never>?
    @ObservationIgnored private var revision = UUID()

    init(send: @escaping @MainActor (String) async throws -> Void,
         verify: @escaping @MainActor (String) async throws -> Void,
         authenticated: @escaping @MainActor () -> Bool,
         authBusy: @escaping @MainActor () -> Bool) {
        self.send = send; self.verify = verify
        self.authenticated = authenticated; self.authBusy = authBusy
    }

    func submit() {
        guard !busy, !authBusy(), !authenticated(), !completed else { return }
        let stage = stage
        let value = (stage == .email ? email : code).trimmingCharacters(in: .whitespacesAndNewlines)
        if stage == .email {
            let parts = value.split(separator: "@", omittingEmptySubsequences: false)
            guard value.count <= 320, parts.count == 2, parts.allSatisfy({ !$0.isEmpty }),
                  !value.contains(where: { $0.isWhitespace || $0.isNewline }) else {
                failure = .invalidEmail; return
            }
        } else {
            guard value.utf8.count == 6, value.utf8.allSatisfy({ (48...57).contains($0) }) else {
                failure = .invalidCode; return
            }
        }
        failure = nil; busy = true
        let request = UUID(); revision = request
        operation = Task { [weak self] in
            guard let self else { return }
            do {
                if stage == .email { try await send(value) }
                else { try await verify(value) }
                guard revision == request, !Task.isCancelled else { return }
                if stage == .email {
                    sentEmail = value; self.stage = .code; code = ""
                } else {
                    completed = authenticated()
                    if !completed { failure = .verify }
                    code = ""
                }
            } catch {
                guard revision == request, !Task.isCancelled else { return }
                failure = stage == .email ? .send : .verify
            }
            guard revision == request else { return }
            busy = false; operation = nil
        }
    }

    func changeEmail() {
        cancel(); stage = .email; sentEmail = ""; completed = false
    }

    func cancel() {
        revision = UUID(); operation?.cancel(); operation = nil
        busy = false; failure = nil; code = ""
    }
}
