import Foundation

@main struct CompanionEmailSignInFixture {
    enum FixtureFailure: Error { case assertion(Int), rejected }
    @MainActor static func main() async {
        do { try await run(); print("Companion email sign-in: validation, email/code stages, duplicate submission, retries, auth busy, cancellation and late completion passed") }
        catch { FileHandle.standardError.write(Data("Email sign-in fixture failed: \(error)\n".utf8)); exit(1) }
    }
    @MainActor static func run() async throws {
        func require(_ value: @autoclosure () -> Bool, line: Int = #line) throws {
            if !value() { throw FixtureFailure.assertion(line) }
        }
        func settle(_ predicate: @MainActor () -> Bool) async throws {
            for _ in 0..<1000 {
                if predicate() { return }
                try await Task.sleep(for: .milliseconds(1))
            }
            throw FixtureFailure.assertion(#line)
        }
        var sent: [String] = []; var verified: [String] = []
        var authenticated = false; var authBusy = false
        var rejectSend = false; var rejectVerify = false
        let model = NativeCompanionEmailSignInModel(send: {
            if rejectSend { throw FixtureFailure.rejected }; sent.append($0)
        }, verify: {
            if rejectVerify { throw FixtureFailure.rejected }; verified.append($0); authenticated = true
        }, authenticated: { authenticated }, authBusy: { authBusy })
        for invalid in ["42", "bad", "a@@b", "a@", "@a", "a b@c", String(repeating: "a", count: 321) + "@b"] {
            model.email = invalid; model.submit()
            try require(model.failure == .invalidEmail && sent.isEmpty)
        }
        model.email = "  owner@example.test \n"; rejectSend = true; model.submit()
        try await settle { !model.busy }
        try require(model.failure == .send && model.stage == .email)
        rejectSend = false; model.submit(); model.submit()
        try await settle { !model.busy }
        try require(sent == ["owner@example.test"] && model.sentEmail == "owner@example.test" && model.stage == .code)
        for sample in ["", "12345", "1234567", "123 56", "１２３４５６", "abcdef", "aB-12 cd", "Åå😀AB12CD", "123456", "ab12cd"] {
            try require(NativeCompanionEmailSignInModel.normalizedCode(sample) == SignInCodeInputPolicy.normalizedCode(sample))
        }
        for invalid in ["", "12345", "123 56", "１２３４５６"] {
            model.code = invalid; model.submit()
            try require(model.failure == .invalidCode && verified.isEmpty)
        }
        model.code = " ab12cd "; rejectVerify = true; model.submit()
        try await settle { !model.busy }
        try require(model.failure == .verify && model.stage == .code && !model.completed)
        rejectVerify = false; model.submit(); model.submit()
        try await settle { !model.busy }
        try require(verified == ["AB12CD"] && model.completed && model.code.isEmpty)
        model.submit(); try require(verified.count == 1)

        authenticated = false; model.changeEmail(); model.email = "new@example.test"
        authBusy = true; model.submit(); try require(!model.busy && sent.count == 1)
        authBusy = false; model.submit(); try await settle { !model.busy }
        try require(model.stage == .code && model.sentEmail == "new@example.test")
        model.changeEmail(); try require(model.stage == .email && model.sentEmail.isEmpty && model.code.isEmpty)

        var release: CheckedContinuation<Void, Never>?
        let held = NativeCompanionEmailSignInModel(send: { _ in
            await withCheckedContinuation { release = $0 }
        }, verify: { _ in }, authenticated: { false }, authBusy: { false })
        held.email = "held@example.test"; held.submit()
        try await settle { release != nil }
        held.cancel(); try require(!held.busy && held.stage == .email)
        release?.resume(); release = nil
        try await Task.sleep(for: .milliseconds(10))
        try require(held.stage == .email && held.sentEmail.isEmpty && held.failure == nil)

        let incomplete = NativeCompanionEmailSignInModel(send: { _ in }, verify: { _ in },
            authenticated: { false }, authBusy: { false })
        incomplete.email = "owner@example.test"; incomplete.submit(); try await settle { !incomplete.busy }
        incomplete.code = "123456"; incomplete.submit(); try await settle { !incomplete.busy }
        try require(incomplete.failure == .verify && !incomplete.completed)
    }
}
