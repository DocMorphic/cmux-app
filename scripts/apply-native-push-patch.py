#!/usr/bin/env python3
"""Install the audited native source hooks into an exact, private cmux checkout."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile

REF = "a5911a1f92f3eeb11c4d7f3e2ef89204718c5009"
PINS = {
    "CLI/CMUXCLI+CommandSuggestions.swift": "2472468564ea59ab24a14cab7c8c7a04f7985fa0f154b2ac516b28f1e7413754",
    "Sources/AppDelegate+AccountSignInWorkspace.swift": "1aab66dbc072c41eb020db9cce2d71f3a8dc6f1fb407723b5b2eb415dc04a9aa",
    "Sources/Auth/HostAccountFlow.swift": "df5795bee1c9507a63620bdfbb4ae4f15a05a79d1eddc2db9b813fc4162db5e1",
    "Sources/Auth/AccountSignInView.swift": "a174954d39c1ccac5961fb5b0e9a16ee836ae9477f1266c0cc52e71ec7f2d885",
    "Resources/Localizable.xcstrings": "508affaf559409add83f36b870f221646bdf613d873897a0363a07f5e5cb726d",
    "Sources/Mobile/MobileHostService.swift": "981e2a4c8d963337904793066b6cca4539627873b0d76ce2bcc51655cd7ded14",
    "Sources/Mobile/MobileHostService+PhonePushKeyExchange.swift": "09a9804a0176f1dc31f64fb7944fb99473ed1470542a519cd8ac3b14df5062cf",
    "Sources/Cloud/PhonePushClient.swift": "e895f51016156d18f11d4aa7d8b721252ff6759a80552b277ae3a7b45596c3df",
    "Sources/Cloud/PhoneReplyInboxClient.swift": "52f21c5af4eb53165914e4a8912c9300d92cde3258e04bc7ff12fdfcbd5ca191",
    "Sources/Auth/MacAuthComposition.swift": "4bb78b100be946c8970ab3001024ccbccea6de2140f09a545c2721c708a3e79a",
    "cmux.xcodeproj/project.pbxproj": "4c8bd5b04194579af34fbdf5cf2090723c2546d59029f92d444aaf54a99779bf",
    "Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/AuthCoordinator+TeamScopes.swift": "55c26389f160344ad666a0eeea48d1d1b37ac63c3e2e50f97602bed26f38a0fe",
    "Sources/AppDelegate.swift": "26597c0a8444b00d4d5f18b4660b7322294f210915646114f6786c87272ede75",
    "Sources/cmuxApp.swift": "350a1789e1387ea37a60fed18aa65bf1aa0d84ea900aabfbc08ddff1734483e3",
    "scripts/reload.sh": "a2690406a58bb5d0c7fb19fa2c9cd33f9a6ea4e7776f77b236483c2821b32a64",
}
ROOT = Path(__file__).resolve().parents[1]


def once(source, old, new):
    if source.count(old) != 1:
        raise ValueError("Pinned native source anchor changed; review upstream before applying")
    return source.replace(old, new, 1)


HELPERS = '''    /// Used by the native auth owner before its first transition await.
    func invalidateAndroidPushSource() {
        nativePushSource?.retireScope()
        nativePushMessages.invalidate()
    }
    private func nativePushScope(_ identity: NativePushSourceMessages.Identity) -> NativePushSourceScope {
        .init(accountID: identity.accountID, teamID: identity.teamID, macDeviceID: identity.macDeviceID,
            macInstanceTag: identity.macInstanceTag, macBuildID: identity.macBuildID)
    }
    private func nativePushContext(macDeviceID: String? = nil)
        -> (NativePushSourceMessages.Identity?, Bool) {
        guard let auth else { return (nil, false) }
        guard let session = auth.authenticatedSessionIdentity,
              auth.isAuthenticatedSessionIdentityCurrent(session) else {
            return (nil, nativePushAuthBootstrapped && !auth.isAuthenticated && !auth.isRestoringSession)
        }
        let scope = auth.authenticatedTeamScope
        guard auth.hasAuthenticatedTeamResolution else { return (nil, false) }
        let teamID: String?
        let teamGeneration: UInt64
        if let scope {
            guard scope.session == session, auth.isAuthenticatedTeamScopeCurrent(scope) else { return (nil, false) }
            teamID = scope.teamID
            teamGeneration = scope.generation
        } else {
            // A persisted/resolved team without verified membership is not a
            // teamless authority. Wait for the native team owner to recover it.
            guard auth.availableTeams.isEmpty, auth.resolvedTeamID == nil else { return (nil, false) }
            teamID = nil
            teamGeneration = 0
        }
        guard let deviceID = macDeviceID ?? identityPrewarm.deviceIDIfReady(),
              let buildID = Bundle.main.bundleIdentifier else { return (nil, false) }
        return (.init(accountID: session.accountID, sessionGeneration: session.generation,
            teamID: teamID, teamGeneration: teamGeneration, macDeviceID: deviceID,
            macInstanceTag: MobileHostIdentity.instanceTag(), macBuildID: buildID), false)
    }
    private func nativePushSettings() -> NativePushSourceMessages.Settings {
        let value = configuration()
        let next = NativePushSourceMessages.Settings(forwardingEnabled: value.forwardingEnabled, mode: value.mode.rawValue,
            admission: currentAdmission().rawValue, hideContent: value.hideContent)
        if let previous = nativePushLastSettings,
           (previous.forwardingEnabled && !next.forwardingEnabled) || (!previous.hideContent && next.hideContent) {
            // Fence old provider work before a queued control message can wait
            // behind capture backpressure. Replay requires fresh native state.
            nativePushSource?.invalidate()
        }
        nativePushLastSettings = next
        return next
    }
    func refreshAndroidPushTrust() { publishAndroidPushHost() }
    private func nativePushTrustedPhones(_ identity: NativePushSourceMessages.Identity?,
        storage suppliedStorage: (any PhonePushSharedStateStorage)? = nil) throws
        -> [NativePushSourceMessages.TrustedPhone] {
        guard let identity else { return [] }
        let storage = suppliedStorage ?? Bundle.main.phonePushSharedStateStorage
        // Audited PhonePushPeerKeyStore v2 namespace; contains public descriptors.
        let prefix = "cmux.phone-push.peer.v2."
        let keys = storage.keys(withPrefix: prefix).filter { $0 != prefix + "registry" }.sorted()
        guard keys.count <= 128 else { throw NativePushSourceMessages.Failure.invalidInput }
        let rows = keys.compactMap { key -> (tuple: Data, descriptor: Data)? in
            guard let tuple = Data(base64Encoded: String(key.dropFirst(prefix.count))),
                  let descriptor = storage.data(forKey: key) else { return nil }
            return (tuple, descriptor)
        }
        let phones = try NativePushSourceMessages.trustedPhones(rows: rows, identity: identity)
        if let previous = nativePushLastTrustedPhones, previous != phones {
            // A changed pin fences the old helper before provider IO can await
            // a queued control frame. Original captures remain durable.
            nativePushSource?.invalidate()
        }
        nativePushLastTrustedPhones = phones
        return phones
    }
    private func publishAndroidPushHost() {
        guard let nativePushSource else { return }
        let (identity, signedOut) = nativePushContext()
        do {
            let message = try nativePushMessages.host(identity: identity, confirmedSignedOut: signedOut,
                settings: nativePushSettings(), nowEpochSeconds: clock.nowEpochSeconds,
                trustedPhones: try nativePushTrustedPhones(identity))
            if !nativePushSource.offer([message], scope: identity.map { nativePushScope($0) }) { nativePushSource.invalidate() }
        } catch { nativePushSource.invalidate() }
    }
    private func publishAndroidPushPayload(_ payload: PhonePushPayload) {
        guard let nativePushSource else { return }
        guard let identity = nativePushContext(macDeviceID: payload.macDeviceId).0 else {
            publishAndroidPushHost()
            return
        }
        do {
            let messages = try nativePushMessages.capture(payload, identity: identity,
                settings: nativePushSettings(), nowEpochSeconds: clock.nowEpochSeconds,
                trustedPhones: try nativePushTrustedPhones(identity))
            if !nativePushSource.offer(messages, scope: nativePushScope(identity)) { nativePushSource.invalidate() }
        } catch { nativePushSource.invalidate() }
    }
'''


def reply_inbox_diagnostics(source):
    # Preserve auth/crypto/retry behavior. Only fixed categories and
    # numeric protocol codes may reach either logger; never error descriptions,
    # URLs, response bytes, credentials, identities or reply contents.
    source = once(source, "    private let retryAfterGate = CmxRetryAfterGate()\n", '''    private let retryAfterGate = CmxRetryAfterGate()

    private enum Operation: String { case fetch, ack }
    private enum Failure: String {
        case authUnavailable, serviceUnavailable, invalidURL, cancelled
        case nonHTTP, http, decoding, transport, unexpected
    }

    private func recordFailure(_ operation: Operation, _ cause: Failure, code: Int = 0) {
        if cause == .authUnavailable || cause == .cancelled {
            phoneReplyLog.debug("reply request unavailable operation=\\(operation.rawValue, privacy: .public) cause=\\(cause.rawValue, privacy: .public) code=\\(code, privacy: .public)")
        } else {
            phoneReplyLog.error("reply request failed operation=\\(operation.rawValue, privacy: .public) cause=\\(cause.rawValue, privacy: .public) code=\\(code, privacy: .public)")
        }
        #if DEBUG
        cmuxDebugLog("phoneReply.requestFailed operation=\\(operation.rawValue) cause=\\(cause.rawValue) code=\\(code)")
        #endif
    }
''')
    source = once(source, '            path: "/v1/replies/e2e",\n', '            operation: .fetch, path: "/v1/replies/e2e",\n')
    source = once(source, 'await authorizedRequest(path: "/v1/replies/e2e/ack")',
                  'await authorizedRequest(operation: .ack, path: "/v1/replies/e2e/ack")')
    for operation, result in [('fetch', 'nil'), ('ack', 'false')]:
        source = once(source, f'        guard (try? await retryAfterGate.wait()) != nil else {{ return {result} }}',
                      f'        guard (try? await retryAfterGate.wait()) != nil else {{\n'
                      f'            recordFailure(.{operation}, .cancelled)\n            return {result}\n        }}')
        source = once(source, f'            guard let http = response as? HTTPURLResponse else {{ return {result} }}',
                      f'            guard let http = response as? HTTPURLResponse else {{\n'
                      f'                recordFailure(.{operation}, .nonHTTP)\n                return {result}\n            }}')
        source = once(source, f'                await recordRetryAfter(http)\n                return {result}',
                      f'                await recordRetryAfter(http)\n'
                      f'                recordFailure(.{operation}, .http, code: http.statusCode)\n                return {result}')
        source = once(source, f'            guard (200...299).contains(http.statusCode) else {{ return {result} }}',
                      f'            guard (200...299).contains(http.statusCode) else {{\n'
                      f'                recordFailure(.{operation}, .http, code: http.statusCode)\n                return {result}\n            }}')
        source = once(source, f'''        }} catch {{
            phoneReplyLog.error("reply {operation} failed: \\(String(describing: error), privacy: .private)")
            return {result}
        }}''', f'''        }} catch is DecodingError {{
            recordFailure(.{operation}, .decoding)
            return {result}
        }} catch let error as URLError {{
            recordFailure(.{operation}, error.code == .cancelled ? .cancelled : .transport, code: error.code.rawValue)
            return {result}
        }} catch is CancellationError {{
            recordFailure(.{operation}, .cancelled)
            return {result}
        }} catch {{
            recordFailure(.{operation}, .unexpected)
            return {result}
        }}''')
    source = once(source, '    private func authorizedRequest(\n        path: String,',
                  '    private func authorizedRequest(\n        operation: Operation, path: String,')
    source = once(source, '''        guard let auth,
              let baseURL = PresenceHeartbeatClient.resolvedServiceURL() else { return nil }''', '''        guard let auth else {
            recordFailure(operation, .authUnavailable)
            return nil
        }
        guard let baseURL = PresenceHeartbeatClient.resolvedServiceURL() else {
            recordFailure(operation, .serviceUnavailable)
            return nil
        }''')
    source = once(source, '''        } catch {
            return nil // signed out; the reply waits out its server TTL
        }''', '''        } catch {
            recordFailure(operation, .authUnavailable)
            return nil // signed out; the reply waits out its server TTL
        }''')
    source = once(source, '''        guard var comps = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else {
            return nil
        }''', '''        guard var comps = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else {
            recordFailure(operation, .invalidURL)
            return nil
        }''')
    source = once(source, '        guard let url = comps.url else { return nil }', '''        guard let url = comps.url else {
            recordFailure(operation, .invalidURL)
            return nil
        }''')
    # The pinned worker requires the complete Mac target on both endpoints.
    # The upstream client omitted build/tag on fetch and all target fields on
    # ack, so the authenticated E2E routes reject those requests with HTTP 400.
    source = once(source, '''            queryItems: [URLQueryItem(
                name: "macDeviceId",
                value: MobileHostIdentity.deviceID()
            )]''', '''            queryItems: [
                URLQueryItem(name: "macDeviceId", value: MobileHostIdentity.deviceID()),
                URLQueryItem(name: "macInstanceTag", value: MobileHostIdentity.instanceTag() == "default" ? nil : MobileHostIdentity.instanceTag()),
                URLQueryItem(name: "macBuildID", value: Bundle.main.bundleIdentifier ?? "cmux"),
            ].filter { $0.value != nil }''')
    source = once(source, '            withJSONObject: ["replyIds": replyIds],', '''            withJSONObject: [
                "replyIds": replyIds,
                "macDeviceId": MobileHostIdentity.deviceID(),
                "macInstanceTag": MobileHostIdentity.instanceTag() == "default" ? NSNull() : MobileHostIdentity.instanceTag() as Any,
                "macBuildID": Bundle.main.bundleIdentifier ?? "cmux",
            ],''')
    return source


def patched_sources(original):
    for path, expected in PINS.items():
        if hashlib.sha256(original[path]).hexdigest() != expected:
            raise ValueError(f"Source mismatch: {path}; expected audited cmux {REF}")
    result = {}
    path = "Sources/Cloud/PhoneReplyInboxClient.swift"
    result[path] = reply_inbox_diagnostics(original[path].decode()).encode()
    path = "CLI/CMUXCLI+CommandSuggestions.swift"
    source = original[path].decode()
    # This pinned upstream revision left its Set initializer as an uncalled
    # closure, preventing the full app's bundled CLI target from compiling.
    source = once(source, "        return names\n    }\n}", "        return names\n    }()\n}")
    result[path] = source.encode()
    path = "Sources/AppDelegate+AccountSignInWorkspace.swift"
    source = original[path].decode()
    if source.count("panel.model.presentSignIn()") != 2:
        raise ValueError("Account workspace sign-in entrypoints changed")
    source = source.replace("panel.model.presentSignIn()", '''if Bundle.main.bundleIdentifier != "com.cmuxterm.app.debug.android.push" {
                panel.model.presentSignIn()
            }''')
    result[path] = source.encode()
    path = "Sources/Auth/HostAccountFlow.swift"
    source = original[path].decode()
    for signature, returned in [("    func startSignIn() {\n", "return"),
                                ("    func startSignInForPane() -> URL? {\n", "return nil")]:
        source = once(source, signature, signature + '''        if Bundle.main.bundleIdentifier == "com.cmuxterm.app.debug.android.push" {
            _ = AppDelegate.shared?.performAccountSignInWorkspaceAction()
            ''' + returned + '''
        }
''')
    signature = "    func switchAccount() async {\n"
    source = once(source, signature, signature + '''        if Bundle.main.bundleIdentifier == "com.cmuxterm.app.debug.android.push" {
            guard !isSwitchingAccount else { return }
            isSwitchingAccount = true
            defer { isSwitchingAccount = false }
            await signOut()
            _ = AppDelegate.shared?.performAccountSignInWorkspaceAction()
            return
        }
''')
    result[path] = source.encode()
    path = "Sources/Auth/AccountSignInView.swift"
    source = original[path].decode()
    source = once(source, "    var body: some View {\n        VStack(spacing: 16) {\n            switch model.phase {\n", '''    @ViewBuilder var body: some View {
        if Bundle.main.bundleIdentifier == "com.cmuxterm.app.debug.android.push" {
            if let auth = AppDelegate.shared?.auth {
                NativeCompanionEmailSignInView(auth: auth)
            } else {
                ProgressView()
            }
        } else {
            hostedBrowserBody
        }
    }

    private var hostedBrowserBody: some View {
        VStack(spacing: 16) {
            switch model.phase {
''')
    result[path] = source.encode()
    path = "Sources/Cloud/PhonePushClient.swift"
    source = original[path].decode()
    source = once(source, "    private var auth: AuthCoordinator?\n", "    private var auth: AuthCoordinator?\n"
        "    private var nativePushSource: (any NativePushSourceSink)?\n"
        "    private let nativePushMessages = NativePushSourceMessages()\n"
        "    private var nativePushLastSettings: NativePushSourceMessages.Settings?\n"
        "    private var nativePushLastTrustedPhones: [NativePushSourceMessages.TrustedPhone]?\n"
        "    private var nativePushAuthBootstrapped = false\n"
        "    private var nativePushLifecycleTask: Task<Void, Never>?\n"
        "    private var nativePushHeartbeatTask: Task<Void, Never>?\n")
    source = once(source, "    func configure(auth: AuthCoordinator) {\n        self.auth = auth\n",
        "    func configure(auth: AuthCoordinator, nativePushSource: (any NativePushSourceSink)? = nil) {\n"
        "        invalidateAndroidPushSource()\n        nativePushLifecycleTask?.cancel()\n"
        "        nativePushHeartbeatTask?.cancel()\n        nativePushLastSettings = nil\n        nativePushLastTrustedPhones = nil\n"
        "        nativePushAuthBootstrapped = false\n        self.nativePushSource = nativePushSource\n        self.auth = auth\n")
    marker = "            await self.bootstrapQueueAndObserve(auth: auth)\n        }\n    }\n"
    source = once(source, marker, marker[:-6] + '''        if nativePushSource != nil {
            nativePushLifecycleTask = Task { [weak self, weak auth] in
                guard let auth else { return }
                for await _ in auth.authenticatedTeamScopes() {
                    guard !Task.isCancelled, let self, self.auth === auth else { return }
                    self.publishAndroidPushHost()
                }
            }
            nativePushHeartbeatTask = Task { [weak self, weak auth] in
                while !Task.isCancelled {
                    guard let self, let auth, self.auth === auth else { return }
                    self.publishAndroidPushHost()
                    do { try await self.clock.sleep(for: .seconds(10)) } catch { return }
                }
            }
        }
    }
''')
    marker = '''        _ = try? await auth.authenticatedSessionSnapshot()
        guard !Task.isCancelled, self.auth === auth else { return }
'''
    source = once(source, marker, marker + "        nativePushAuthBootstrapped = true\n        publishAndroidPushHost()\n")
    marker = '''    private func enqueue(
        _ payload: PhonePushPayload
    ) -> PhonePushForwardAdmission {
        guard let identity = auth?.authenticatedSessionIdentity else {
            return .authenticationUnavailable
        }
'''
    source = once(source, marker, marker + "        publishAndroidPushPayload(payload)\n")
    marker = '''                notificationIds: Array(ids[start..<end]),
                badgeCount: badgeCount,
                hideContent: false
            )
'''
    source = once(source, marker, marker + "            publishAndroidPushPayload(payload)\n")
    source = once(source, "    private func publishStatusChanged() {\n", "    private func publishStatusChanged() {\n        publishAndroidPushHost()\n")
    source = once(source, "    private func handleAuthTransition(\n", HELPERS + "    private func handleAuthTransition(\n")
    result[path] = source.encode()
    path = "Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/AuthCoordinator+TeamScopes.swift"
    source = original[path].decode()
    marker = "    /// Current account and selected team, without accessing either credential.\n"
    source = once(source, marker, '''    /// Whether the current authenticated account has a verified team result.
    ///
    /// Distinguishes a confirmed teamless account from unresolved bootstrap or
    /// membership recovery without exporting credentials or cached authority.
    var hasAuthenticatedTeamResolution: Bool {
        guard let session = publishedAuthenticatedSessionIdentity,
              authenticatedTeamsSessionGeneration == session.generation else { return false }
        if resolvedTeamID == nil { return availableTeams.isEmpty }
        return authenticatedTeamScope != nil
    }

''' + marker)
    result[path] = source.encode()
    path = "Sources/Mobile/MobileHostService.swift"
    source = original[path].decode()
    marker = "    /// The signed-in local user's id, awaiting launch session restore first so\n"
    source = once(source, marker, '''    /// Bind optional push-key work to this host's actual auth owner across both
    /// bootstrap and Keychain suspension, including same-account replacement.
    func phonePushKeyExchangeSession() async
        -> (owner: AuthCoordinator, identity: AuthenticatedSessionIdentity)? {
        guard let auth else { return nil }
        await auth.awaitBootstrapped()
        guard self.auth === auth,
              let identity = auth.authenticatedSessionIdentity,
              auth.isAuthenticatedSessionIdentityCurrent(identity) else { return nil }
        return (auth, identity)
    }

    func isPhonePushKeyExchangeSessionCurrent(
        _ session: (owner: AuthCoordinator, identity: AuthenticatedSessionIdentity)
    ) -> Bool {
        self.auth === session.owner &&
            session.owner.isAuthenticatedSessionIdentityCurrent(session.identity)
    }

''' + marker)
    result[path] = source.encode()
    path = "Sources/Mobile/MobileHostService+PhonePushKeyExchange.swift"
    source = original[path].decode()
    source = once(source,
        "            guard let accountID = await currentAuthenticatedLocalUserID(), !accountID.isEmpty else {\n",
        "            guard let session = await phonePushKeyExchangeSession(), !session.identity.accountID.isEmpty else {\n")
    source = once(source, "            let macDeviceID = MobileHostIdentity.deviceID()\n",
        "            try Task.checkCancellation()\n            let accountID = session.identity.accountID\n\n            let macDeviceID = MobileHostIdentity.deviceID()\n")
    source = once(source, "            let macKey = try PhonePushKeyMaterial.current(bundleID: macBuildID)\n", '''            // Keep the existing Keychain identity/access controls, but never block
            // this main actor on a SecurityAgent decision. Recheck the host's
            // actual auth owner and session before pinning or refreshing trust.
            let macKey = try await NativePushKeyMaterialAccess.current()
            try Task.checkCancellation()
            guard isPhonePushKeyExchangeSessionCurrent(session) else {
                return .failure(MobileHostRPCError(code: "unauthorized",
                    message: "The Mac session changed during mobile push key exchange."))
            }
''')
    marker = "            let result = MobilePhonePushKeyExchangeResponse(\n"
    source = once(source, marker, "            PhonePushClient.shared.refreshAndroidPushTrust()\n\n" + marker)
    source = once(source, "        } catch let error as MobilePhonePushKeyExchangeError {\n", '''        } catch is CancellationError {
            return .failure(MobileHostRPCError(code: "cancelled",
                message: "Mobile push key exchange was cancelled."))
        } catch is NativePushKeyMaterialAccess.Failure {
            return .failure(MobileHostRPCError(code: "key_unavailable",
                message: "The Mac push key is unavailable. Unlock this Mac and review any Keychain prompt, then retry."))
        } catch let error as MobilePhonePushKeyExchangeError {
''')
    result[path] = source.encode()
    path = "Sources/Auth/MacAuthComposition.swift"
    source = original[path].decode()
    source = once(source, "            onSessionWillTransition: {\n", "            onSessionWillTransition: {\n                PhonePushClient.shared.invalidateAndroidPushSource()\n")
    source = once(source, "        self.cloudTeamScopeObserver = CloudTeamScopeObserver(auth: coordinator) { isSameAccount in\n",
        "        self.cloudTeamScopeObserver = CloudTeamScopeObserver(auth: coordinator) { isSameAccount in\n"
        "            PhonePushClient.shared.invalidateAndroidPushSource()\n")
    result[path] = source.encode()
    path = "Sources/AppDelegate.swift"
    source = original[path].decode()
    source = once(source, "import CmuxCloud\n", "import CmuxCloud\nimport CmuxPhonePush\n")
    source = once(source, "    private(set) var auth: MacAuthComposition?\n", '''    private(set) var auth: MacAuthComposition?
    private var nativeAndroidPushOwner: NativePushSourceOwner?
    private var nativeAndroidPushPairing: NativePushPairingPresentation?
    func showAndroidNotificationPairing() {
        guard let pairing = nativeAndroidPushPairing else {
            let alert = NSAlert()
            alert.messageText = String(localized: "androidPush.pair.title", defaultValue: "Pair Android Notifications")
            alert.informativeText = String(localized: "androidPush.pair.error", defaultValue: "Pairing is unavailable. Sign in on this Mac, enable phone notifications, and check the private helper setup. Then try again.")
            alert.runModal()
            return
        }
        pairing.show()
    }
''')
    source = once(source, "        PhonePushClient.shared.configure(auth: auth.coordinator)\n", '''        if nativeAndroidPushOwner == nil {
            if let bundleID = Bundle.main.bundleIdentifier {
                nativeAndroidPushOwner = try? NativePushSourceOwner(
                    environment: ProcessInfo.processInfo.environment,
                    loadDescriptor: { @Sendable in
                        let key = try PhonePushKeyMaterial.current(bundleID: bundleID)
                        return try JSONEncoder().encode(MobilePhonePushPublicKeyDescriptor(
                            installationID: key.installationID, keyID: key.keyID, publicKey: key.publicKeyData
                        ))
                    },
                    loadCaptureKey: { @Sendable allowCreation in
                        try NativePushCaptureKeyStore(bundleID: bundleID).load(allowCreation: allowCreation)
                    }
                )
            }
        }
        PhonePushClient.shared.configure(auth: auth.coordinator, nativePushSource: nativeAndroidPushOwner)
        try? nativeAndroidPushOwner?.start()
        if let owner = nativeAndroidPushOwner, nativeAndroidPushPairing == nil {
            nativeAndroidPushPairing = NativePushPairingPresentation(managesAppMenu: false, request: { [weak owner] action, value in
                guard let owner else { throw NativePushPairingClient.Failure.unavailable }
                return try await owner.requestPairing(action: action, value: value)
            })
            owner.onPairingInvalidation = { [weak pairing = nativeAndroidPushPairing] in pairing?.invalidateOffer() }
        }
        nativeAndroidPushPairing?.installMenu()
''')
    source = once(source, "    func menuNeedsUpdate(_ menu: NSMenu) {\n", "    func menuNeedsUpdate(_ menu: NSMenu) {\n        nativeAndroidPushPairing?.installMenu()\n")
    source = once(source, "    func installReloadConfigurationMenuItemAction() {\n", "    func installReloadConfigurationMenuItemAction() {\n        nativeAndroidPushPairing?.installMenu()\n")
    result[path] = source.encode()
    path = "Sources/cmuxApp.swift"
    source = original[path].decode()
    # The pinned scene already uses all ten CommandsBuilder buildBlock slots.
    # Nest two commands behind its existing final slot instead of adding an eleventh.
    source = once(source, "            windowAndViewCommands\n        }\n", "            androidPushAndWindowCommands\n        }\n")
    source = once(source, "    /// Presents window navigation and stateful View commands for the focused content.\n", '''    @CommandsBuilder
    private var androidPushAndWindowCommands: some Commands {
        NativePushPairingCommands(configured: ProcessInfo.processInfo.environment["CMUX_ANDROID_PUSH_CONFIG"] != nil) {
            appDelegate.showAndroidNotificationPairing()
        }
        windowAndViewCommands
    }

    /// Presents window navigation and stateful View commands for the focused content.
''')
    result[path] = source.encode()
    path = "scripts/reload.sh"
    source = original[path].decode()
    marker = '''  if [[ "$PROD_AUTH" -eq 1 ]]; then
    TAG_LAUNCH_ENV+=(CMUX_AUTH_ENVIRONMENT=production)
  fi
'''
    source = once(source, marker, marker + '''  # Tagged launch uses env -i; explicitly carry only the reviewed owner's
  # nonsecret configuration/executable paths. Partial setup must not silently
  # launch a build without its Android push owner.
  if [[ -n "${CMUX_ANDROID_PUSH_CONFIG:-}${CMUX_ANDROID_PUSH_NODE:-}${CMUX_ANDROID_PUSH_HELPER:-}" ]]; then
    for CMUX_ANDROID_PUSH_PATH_NAME in CMUX_ANDROID_PUSH_CONFIG CMUX_ANDROID_PUSH_NODE CMUX_ANDROID_PUSH_HELPER; do
      CMUX_ANDROID_PUSH_PATH_VALUE="${!CMUX_ANDROID_PUSH_PATH_NAME:-}"
      if [[ "$CMUX_ANDROID_PUSH_PATH_VALUE" != /* ]]; then
        echo "error: Android push owner requires all three absolute paths" >&2
        exit 1
      fi
      TAG_LAUNCH_ENV+=("$CMUX_ANDROID_PUSH_PATH_NAME=$CMUX_ANDROID_PUSH_PATH_VALUE")
    done
  fi
''')
    result[path] = source.encode()
    path = "cmux.xcodeproj/project.pbxproj"
    source = original[path].decode()
    native_sources = {
        "NativeCompanionEmailSignInModel": "push/mac-native/NativeCompanionEmailSignInModel.swift",
        "NativeCompanionEmailSignInView": "push/mac-native/NativeCompanionEmailSignInView.swift",
        "NativePushSourceMessages": "push/mac-native/NativePushSourceMessages.swift",
        "NativePushSourceSink": "push/mac-native/NativePushSourceSink.swift",
        "NativePushSourceDeliveryQueue": "push/mac-native/NativePushSourceDeliveryQueue.swift",
        "NativePushSourceProcessSink": "push/mac-native/NativePushSourceProcessSink.swift",
        "NativePushSourceFrame": "push/NativePushSourceFrame.swift",
        "NativePushOwnerDescriptorRepository": "push/mac-native/NativePushOwnerDescriptorRepository.swift",
        "NativePushSourceOwner": "push/mac-native/NativePushSourceOwner.swift",
        "NativePushCaptureRepository": "push/mac-native/NativePushCaptureRepository.swift",
        "NativePushCaptureKeyStore": "push/mac-native/NativePushCaptureKeyStore.swift",
        "NativePushSourceCaptureController": "push/mac-native/NativePushSourceCaptureController.swift",
        "NativePushPairingClient": "push/mac-native/NativePushPairingClient.swift",
        "NativePushPairingModel": "push/mac-native/NativePushPairingModel.swift",
        "NativePushPairingView": "push/mac-native/NativePushPairingView.swift",
        "NativePushPairingPresentation": "push/mac-native/NativePushPairingPresentation.swift",
        "NativePushPairingCommands": "push/mac-native/NativePushPairingCommands.swift",
        "NativePushKeychainReadLane": "push/mac-native/NativePushKeychainReadLane.swift",
        "NativePushKeyMaterialAccess": "push/mac-native/NativePushKeyMaterialAccess.swift",
    }
    for index, (name, source_path) in enumerate(native_sources.items(), 1):
        build = f"C0DEAD0000000000000000{index:02X}"
        file = f"C0DEAD0000000000000001{index:02X}"
        if build in source or file in source:
            raise ValueError("Native push Xcode ID collision")
        source = once(source, "/* End PBXBuildFile section */", f"\t\t{build} /* {name}.swift in Sources */ = {{isa = PBXBuildFile; fileRef = {file} /* {name}.swift */; }};\n/* End PBXBuildFile section */")
        source = once(source, "/* End PBXFileReference section */", f'\t\t{file} /* {name}.swift */ = {{isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = "Sources/Cloud/{name}.swift"; sourceTree = SOURCE_ROOT; }};\n/* End PBXFileReference section */')
        source = once(source, "\t\t\t\tD1F0A00100000000000000A2 /* PhonePushClient.swift */,", f"\t\t\t\t{file} /* {name}.swift */,\n\t\t\t\tD1F0A00100000000000000A2 /* PhonePushClient.swift */,")
        source = once(source, "\t\t\t\tD1F0A00100000000000000A1 /* PhonePushClient.swift in Sources */,", f"\t\t\t\t{build} /* {name}.swift in Sources */,\n\t\t\t\tD1F0A00100000000000000A1 /* PhonePushClient.swift in Sources */,")
        result[f"Sources/Cloud/{name}.swift"] = (ROOT / source_path).read_bytes()
    result[path] = source.encode()
    path = "Resources/Localizable.xcstrings"
    source = original[path].decode()
    entries = json.loads((ROOT / "push/mac-native/NativePushPairing.localizations.json").read_text())
    catalog = json.loads(source)
    if set(entries) & set(catalog["strings"]):
        raise ValueError("Native push localization key collision")
    inserted = json.dumps(entries, ensure_ascii=False, indent=2)[2:-2]
    source = once(source, '  "strings": {\n', '  "strings": {\n' + "\n".join("  " + row for row in inserted.splitlines()) + ",\n")
    if set(json.loads(source)["strings"]) != set(catalog["strings"]) | set(entries):
        raise ValueError("Native push localization insertion failed")
    result[path] = source.encode()
    return result


def install(target, check=False):
    original = {path: (target / path).read_bytes() for path in PINS}
    patched = patched_sources(original)
    for path in patched.keys() - original.keys():
        if (target / path).exists():
            raise ValueError(f"Refusing to replace existing native source: {path}")
    if check:
        return patched
    staged = {}
    changed = []
    try:
        for path, data in patched.items():
            destination = target / path
            descriptor, temporary = tempfile.mkstemp(prefix=".cmux-android-push-", dir=destination.parent)
            staged[path] = Path(temporary)
            with os.fdopen(descriptor, "wb") as output:
                output.write(data)
                output.flush()
                os.fsync(output.fileno())
            os.chmod(temporary, (destination.stat().st_mode & 0o777) if destination.exists() else 0o644)
        for path, temporary in staged.items():
            os.replace(temporary, target / path)
            changed.append(path)
    except Exception:
        for path in reversed(changed):
            if path in original:
                (target / path).write_bytes(original[path])
            else:
                (target / path).unlink()
        raise
    finally:
        for temporary in staged.values():
            temporary.unlink(missing_ok=True)
    return patched


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", type=Path, required=True)
    parser.add_argument("--check", action="store_true", help="Check exact source pins and all wiring without writing")
    args = parser.parse_args()
    patched = install(args.target.resolve(), args.check)
    print(f"Native producer hooks {'checked' if args.check else 'installed'}: {len(patched)} files at audited {REF}")
    print("Helper keys/TLS provisioning and tagged full-app/live acceptance remain required.")


if __name__ == "__main__":
    main()
