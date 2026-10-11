# Mac notification pairing window — 2026-10-11

## Product flow

The configured custom Mac build now has **Pair Android Notifications** in its cmux
application menu. It opens one auxiliary SwiftUI window owned by AppDelegate.
Choose the release or debug Android app and request a new code. On Android, connect
the same Mac, enable Push Alerts in Settings, and scan the code from that Mac's
notification-helper row. Compare the displayed HTTPS address and helper key
fingerprint before confirming. Copy/paste is available when scanning is unsuitable.

This is integrated source for the custom build, not an option added to the running
official cmux app. The full tagged build and live Pixel enrollment remain pending.
APK 650 already contains the matching Android scanner/review flow; this batch needs
no Android protocol change or APK rebuild.

## Ownership and lifecycle

- The native source owner derives the control socket from its explicit private
  configuration. No socket path, account identity or helper key comes from the UI.
- The actor client checks private directory/socket ownership and the connected
  peer UID. It uses bounded nonblocking Unix IO, a four-second deadline,
  cancellation polling and a 32 KiB response limit. No new network listener exists.
- The window requests the existing short-lived, authenticated native-epoch offer;
  the helper retains all native account/phone/Mac authorization checks. UI parsing
  validates the bounded offer, HTTPS endpoint, requested package, expiry and
  distinct canonical public descriptors. SHA-256 fingerprint formatting matches
  the Android confirmation screen.
- Offer bodies stay in memory. They are never placed in saved view state,
  preferences, files, diagnostics or a process argument. Their diagnostic
  description is redacted. Core Image encodes the exact JSON as a QR with a quiet
  zone; oversized QR payloads offer copy/paste instead.
- New codes, expiry, app selection, window closure and native authority invalidation
  clear the old display and cancel its offer. A late response after closure is
  cancelled without replacing current state. The helper independently enforces
  epoch/expiry, including while the app is suspended.
- Clipboard copy requires an explicit click. Cleanup clears only the pasteboard
  revision written by this window, preserving a later copy from any app. The model
  owns cleanup; it does not depend solely on SwiftUI disappearance callbacks.
- Menu installation is idempotent and reapplied through the host's existing menu
  maintenance hooks. AppKit owns only that menu target, pasteboard and auxiliary
  window lifecycle; SwiftUI owns the screen and observable model.

The installer now changes **22 files / eight audited originals** at
`f4b1509054949eaad5d695569ad443c4c18ed68d`. Added original pin:
`Resources/Localizable.xcstrings`, SHA-256
`d942497a9ff1a2970e2e3f6941b4e239a24d172dbe9d02971fe33776578dd35b`.
Four new Swift files have explicit Xcode app target membership. Localization
insertion preserves all existing entries and adds 13 keys in all nine Mac locales.

## Executed checks

```sh
python3 scripts/check-native-push-pairing.py --node /absolute/path/to/node \
  --patched-app-delegate /absolute/path/to/check/patched/Sources/AppDelegate.swift
python3 scripts/check-native-push-owner.py --node /absolute/path/to/node
```

The actual four Swift files compile against installed macOS SwiftUI/AppKit/Core
Image/Vision in Swift language mode 6. The exact injected AppDelegate composition
fragment also typechecks with the actual native owner/controller/client/UI types;
this does not typecheck the rest of AppDelegate or build the whole app.

Executed checks cover offer/fingerprint validation, seven invalid offer mutations,
expiry, held-result cancellation and retry errors; Vision decodes the actual QR
pixels to the exact offer at 248 and 496 pixels. An isolated named pasteboard
verifies owned-copy cleanup and preservation of a newer copy. The user's general
clipboard was not changed. Menu tests cover repeated installation and reconstruction.
Real synthetic Unix sockets verify cancellation while the main actor is available,
oversized replies, error envelopes and refusal of public socket permissions.

The actual Swift source owner requests and cancels an offer through the production
Node helper on Node 22/26. Current native invalidation callbacks and nine durable
recovery cases pass. Current Swift/project/Bash parsing, auth/payload/receipt
execution, four installer refusal/rollback cases and eight tagged-launch cases pass.
No real account/Keychain/provider/device was used. Evidence is ignored under
`captures/runtime/push-native-pairing/`.

Localization audit: all 13 UI keys match the addition file, all nine locale entries
exist, and the pinned upstream `localization_catalog.py check` reports zero parity
errors for both the additions and complete patched catalog. No new bare English
UI labels exist. The upstream `localize-changes` Git-diff workflow was not run: the
private full Mac checkout is not materialized. Run it when that checkout is ready.

## Acceptance still required

Build the distinct tagged custom cmux app after Xcode and trusted TLS setup, verify
the application menu/window with the actual user's account, pair signed/debug
Android with the displayed QR, and exercise clipboard/window/auth transitions.
Then complete actual FCM/Doze/token/tap/reply/revocation and full iOS parity gates.
Standalone UI compilation, QR pixels and helper control do not establish live push.
