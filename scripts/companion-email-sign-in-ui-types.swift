import Observation

// Compile the actual SwiftUI form without linking the whole upstream app.
// These interface fixtures do not verify the real auth coordinator or server.
@MainActor @Observable final class CompanionAuthInterface {
    var isAuthenticated = false
    var isLoading = false
    var isRestoringSession = false
    func sendCode(to email: String) async throws {}
    func verifyCode(_ code: String) async throws {}
}
struct CompanionIdentityInterface { let email: String }
@MainActor @Observable final class CompanionAccountInterface {
    var currentIdentity: CompanionIdentityInterface?
}
@MainActor struct MacAuthComposition {
    let coordinator: CompanionAuthInterface
    let accountFlow: CompanionAccountInterface
}
