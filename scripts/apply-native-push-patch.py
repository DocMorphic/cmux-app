#!/usr/bin/env python3
"""Install the audited native source hooks into an exact, private cmux checkout."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile

REF = "f4b1509054949eaad5d695569ad443c4c18ed68d"
PINS = {
    "Resources/Localizable.xcstrings": "d942497a9ff1a2970e2e3f6941b4e239a24d172dbe9d02971fe33776578dd35b",
    "Sources/Mobile/MobileHostService+PhonePushKeyExchange.swift": "09a9804a0176f1dc31f64fb7944fb99473ed1470542a519cd8ac3b14df5062cf",
    "Sources/Cloud/PhonePushClient.swift": "e895f51016156d18f11d4aa7d8b721252ff6759a80552b277ae3a7b45596c3df",
    "Sources/Auth/MacAuthComposition.swift": "71184d168ad431dd0867a7efb64f6ea61b2fd66b8c3fcf6b9c3ee1ae6be10d57",
    "cmux.xcodeproj/project.pbxproj": "78898acbeb053763c315c44b6dfe43fb6ff51da3cba0a073958b2192976f5a50",
    "Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/AuthCoordinator+TeamScopes.swift": "55c26389f160344ad666a0eeea48d1d1b37ac63c3e2e50f97602bed26f38a0fe",
    "Sources/AppDelegate.swift": "e24fe250dfd1d95e0f64f09060281370b05e1ae6c172a25233a301d47ffca7b0",
    "scripts/reload.sh": "b42199aa1416b20b51f48cc507e3bc0595347fcacf0fb470fb302912498d5216",
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


def patched_sources(original):
    for path, expected in PINS.items():
        if hashlib.sha256(original[path]).hexdigest() != expected:
            raise ValueError(f"Source mismatch: {path}; expected audited cmux {REF}")
    result = {}
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
    path = "Sources/Mobile/MobileHostService+PhonePushKeyExchange.swift"
    source = original[path].decode()
    marker = "            let result = MobilePhonePushKeyExchangeResponse(\n"
    source = once(source, marker, "            await MainActor.run { PhonePushClient.shared.refreshAndroidPushTrust() }\n\n" + marker)
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
    source = once(source, "    private(set) var auth: MacAuthComposition?\n", "    private(set) var auth: MacAuthComposition?\n    private var nativeAndroidPushOwner: NativePushSourceOwner?\n    private var nativeAndroidPushPairing: NativePushPairingPresentation?\n")
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
            nativeAndroidPushPairing = NativePushPairingPresentation(request: { [weak owner] action, value in
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
