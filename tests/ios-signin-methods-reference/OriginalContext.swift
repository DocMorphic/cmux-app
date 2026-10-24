import SwiftUI
import UIKit
import Observation
import CmuxMobileSupport

// Authored offline service adapter. No SDK, token store, account or network is instantiated.
@MainActor @Observable final class AuthCoordinator {
    var isLoading = false
    var isRestoringSession = false
    func signInWithApple() async throws {}
    func signInWithGoogle() async throws {}
    func signInWithGitHub() async throws {}
    func revalidateSession() async { isRestoringSession = false }
}

public struct SignInMethodsReferenceRoot: View {
    @State private var auth = AuthCoordinator()
    let mode: String
    public init(mode: String) { self.mode = mode }
    public var body: some View {
        SignInView(mode: mode).environment(auth).onAppear {
            auth.isRestoringSession = mode == "restoring"
            auth.isLoading = mode == "google"
        }
    }
}

// Original view members are inserted byte-for-byte below; only methods are hosted.
private struct SignInView: View {
    private let usesStandaloneChrome = true
    @Environment(AuthCoordinator.self) private var authManager
    @State private var email = ""
    @State private var error: String?
    @State private var signingInProviders: Set<OAuthSignInProvider>
    @State private var isRequestingEmailVerification = false
    @State private var isRequestingBillingRecovery = false
    @State private var shouldAutofocusEmail = false
    @FocusState private var isEmailFocused: Bool
    init(mode: String) {
        _error = State(initialValue: mode == "error" ? L10n.string("mobile.auth.error.server", defaultValue: "Something went wrong. Please try again.") : nil)
        _signingInProviders = State(initialValue: mode == "google" ? [.google] : [])
    }
    private var signInEntryContent: some View { emailEntryView }
    private func sendCode(autofocusCodeOnSuccess: Bool) async { error = "Reference request" }
    private func signIn(with provider: OAuthSignInProvider) async { signingInProviders.insert(provider); authManager.isLoading = true }
    /* ORIGINAL_MEMBERS */
}
