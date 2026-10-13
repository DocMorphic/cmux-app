import SwiftUI

/// Only the isolated Android-push companion uses this production email-code UI.
@MainActor struct NativeCompanionEmailSignInView: View {
    private let auth: MacAuthComposition
    @State private var model: NativeCompanionEmailSignInModel
    @FocusState private var inputFocused: Bool

    init(auth: MacAuthComposition) {
        self.auth = auth
        _model = State(initialValue: NativeCompanionEmailSignInModel(
            send: { try await auth.coordinator.sendCode(to: $0) },
            verify: { try await auth.coordinator.verifyCode($0) },
            authenticated: { auth.coordinator.isAuthenticated },
            authBusy: { auth.coordinator.isLoading || auth.coordinator.isRestoringSession }))
    }

    var body: some View {
        @Bindable var model = model
        VStack(spacing: 16) {
            if let identity = auth.accountFlow.currentIdentity, auth.coordinator.isAuthenticated {
                Image(systemName: "checkmark.circle.fill").font(.system(size: 40)).foregroundStyle(.green)
                    .accessibilityHidden(true)
                Text("You’re signed in now").font(.title2)
                Text(identity.email).textSelection(.enabled)
            } else {
                Image(systemName: "person.crop.circle").font(.system(size: 40)).accessibilityHidden(true)
                Text("Sign in to cmux").font(.title2)
                if model.stage == .email {
                    Text("Enter your cmux account email to receive a sign-in code.").foregroundStyle(.secondary)
                    TextField("Email", text: $model.email).textContentType(.emailAddress)
                        .focused($inputFocused).disabled(model.busy).onSubmit { model.submit() }
                        .accessibilityIdentifier("androidCompanion.signIn.email")
                } else {
                    Text("Enter the newest six-digit code sent to \(model.sentEmail).").foregroundStyle(.secondary)
                    TextField("Sign-in code", text: $model.code).textContentType(.oneTimeCode)
                        .focused($inputFocused).disabled(model.busy).onSubmit { model.submit() }
                        .accessibilityIdentifier("androidCompanion.signIn.code")
                }
                if let failure = model.failure {
                    Text(message(failure)).foregroundStyle(.red).fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("androidCompanion.signIn.error")
                }
                HStack {
                    if model.busy { ProgressView().controlSize(.small) }
                    Button(model.stage == .email ? "Send Code" : "Sign In") { model.submit() }
                        .buttonStyle(.borderedProminent)
                        .disabled(model.busy || auth.coordinator.isLoading || auth.coordinator.isRestoringSession)
                        .accessibilityIdentifier("androidCompanion.signIn.submit")
                }
                if model.stage == .code {
                    Button("Use another email or request a new code") { model.changeEmail(); inputFocused = true }
                        .disabled(model.busy).accessibilityIdentifier("androidCompanion.signIn.changeEmail")
                }
            }
        }.textFieldStyle(.roundedBorder).multilineTextAlignment(.center)
            .frame(maxWidth: 420, minHeight: 240)
            .frame(maxWidth: .infinity)
            .task { inputFocused = true }
            .onChange(of: model.stage) { _, _ in inputFocused = true }
            .onDisappear { model.cancel() }
            .accessibilityIdentifier("androidCompanion.signIn")
    }

    private func message(_ failure: NativeCompanionEmailSignInModel.Failure) -> String {
        switch failure {
        case .invalidEmail: "Enter a valid email address."
        case .invalidCode: "Enter the six-digit code from your newest email."
        case .send: "Couldn't send a code. Check your connection and try again."
        case .verify: "Couldn't sign in. Check your connection and use the newest code, or request a new one."
        }
    }
}
