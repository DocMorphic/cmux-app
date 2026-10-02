# Android background push

## Current state and delivery decision

The existing Android foreground service watches independently admitted saved Macs
and creates system notifications. It has passed a real Pixel background-alert
check, but this does not establish delivery during Doze, after process death, or
while the service cannot run. Android's [Doze documentation](https://developer.android.com/training/monitoring-device-state/doze-standby)
recommends FCM for messages that need timely delivery during idle periods.

The upstream delivery service at the October 2 reviewed head
[`f204ade`](https://github.com/manaflow-ai/cmux/blob/f204ade4df352cd4c214f8a21f792cfc50e7dba8/web/services/apns/pushDeliveryService.ts)
filters device registrations to `platform == "ios"` and sends via APNs. Adding an
Android FCM receiver alone cannot make that service send to Android. Its deployed
behavior was not probed with invented registrations or device tokens.

A delivery choice has been requested from the user and is pending:

1. **Private Firebase project and a Mac forwarding helper.** A separately paired
   helper would subscribe to permitted local cmux notifications, encrypt for the
   phone and send through that project's FCM service account. The app contains
   only Firebase client configuration, never the service-account private key.
   This requires helper installation and project setup; it is an additional Mac
   component compared with the official iOS workflow.
2. **Official cmux backend extension.** cmux would add Android registration,
   token rotation/revocation and FCM delivery to its authenticated push service,
   preserving its account/team, idempotency, recipient and encryption checks.
   This needs cooperation and deployment access from cmux; neither has been
   established by permission to reuse the source repository.

No Firebase project, service account, cloud deployment, new Mac listener or push
device registration has been created. The app currently has no FCM integration.
The [Firebase client setup](https://firebase.google.com/docs/cloud-messaging/android/get-started)
requires app/project configuration and a messaging service for data payloads.
Notification permission and explicit user opt-in must precede registration.

## Encrypted envelope compatibility (2026-10-02)

`PhonePushCrypto.kt` implements the upstream authenticated HPKE v2 envelope:
X25519 / HKDF-SHA256 / ChaCha20-Poly1305, using the existing Bouncy Castle 1.86
lightweight API. There is no new crypto dependency or custom HPKE implementation.
The exact upstream
[`PhonePushCrypto.swift`](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/macOS/CmuxPhonePush/Sources/CmuxPhonePush/PhonePushCrypto.swift)
blob is unchanged at reviewed head `f204ade`.

Compatibility includes:

- Account, team, installation, phone build, Mac device/instance/build and both key
  identities are bound to the envelope. The decrypt caller supplies its expected
  identity and already trusted sender key independently of the incoming message.
- Canonical tuple bytes match Swift `JSONEncoder.sortedKeys`, including omitted
  optional fields, slash escaping, Unicode and control characters. Wire fields
  retain the upstream `iosBuildID`/`iosInstallationID` spelling for compatibility;
  this does not impersonate an official iOS registration.
- The authenticated HPKE context is used for both info and associated data, with
  fresh encapsulation for each outgoing message. Altered metadata, wrong keys and
  damaged ciphertext/encapsulation fail without yielding plaintext.
- Version/type/key-length and encoded/decoded size checks bound inputs. The codec's
  16 KiB ciphertext ceiling is an internal bound; delivery must additionally obey
  its provider's smaller message limit.

**This is a crypto foundation, not active push delivery.** It is not yet called by
a notification receiver. Authenticated key exchange and account-encrypted key storage
now have the checkpoint below. Registration/token rotation, delivery-time current
account/team/forgotten-computer admission, payload freshness,
duplicate/dismiss ordering, opt-out/unregister and notification presentation must
be implemented before any decrypted envelope may become an alert. Decryption alone
never establishes freshness, current authorization or freedom from replay.

## Verification and reproduction

Nine focused JVM cases passed with no skips: five crypto tests and four existing
V2 canonical-signing regressions. Two committed vectors were generated using the
pinned upstream Swift crypto implementation and Apple's CryptoKit. Android opens
both and produces exactly the same canonical tuple bytes. In the reverse check,
Apple's implementation opened both Android-generated envelopes and verified the
expected generated plaintext. Tests additionally cover every identity field,
recipient/sender/version mismatches, wrong and low-order keys, ciphertext and
encapsulation corruption, malformed data, oversize rejection and fresh encapsulation.

The keys in `app/src/test/resources/push/apple-hpke-v2.json` are **public test
material**, derived from fixed byte sequences in the fixture script. They are not
installation credentials and must never be used outside tests. Neither fixture
script accesses Keychain, the user's accounts or a production notification feed.
The checker compiles only the unchanged crypto portion of the upstream source;
unrelated Keychain/storage classes are excluded at their documented boundary.
An initial attempt to compile the whole file exposed its package storage
dependencies; narrowing to that unchanged crypto portion resolved compilation.

```sh
python3 scripts/check-phone-push-crypto.py --upstream /path/to/cmux-checkout

CMUX_PUSH_CROSS_OUTPUT="$PWD/captures/runtime/push-crypto/android-envelopes.json" \
  ./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest \
  --tests '*PhonePushCryptoTest' --tests '*IrohV2SigningCodecTest'

python3 scripts/check-phone-push-crypto.py --upstream /path/to/cmux-checkout \
  --verify-android captures/runtime/push-crypto/android-envelopes.json
```

For intentional fixture regeneration, pass
`--write-vectors app/src/test/resources/push/apple-hpke-v2.json` to the first
command. Existing committed vectors allow ordinary Linux CI to run the JVM checks
without Swift or access to the upstream checkout. The reverse CryptoKit check is
an explicit macOS check, not a silently skipped JVM assertion.

Ignored evidence: `captures/runtime/push-crypto/`, including the source digest,
Swift build log, JVM XML, Android envelopes and Apple verification result. No APK
was assembled or published and no emulator was started for this checkpoint.
Android runtime validation belongs in the next push integration build. Reliable
Doze/process-death delivery and physical Pixel/Mac acceptance remain open.

## Authenticated Mac key exchange and encrypted phone key storage (2026-10-02)

The opted-in notification service now performs `phone_push.keys.exchange` on its
existing authenticated saved-Mac connection when host status advertises
`phone_push.keys.exchange.v1`. It runs alongside feed monitoring: notification
feed readiness does not wait for it. Each connection permits at most three
attempts, with three-second RPC deadlines and one/two-second retry delays. The
Android package name is sent in the upstream `ios_build_id` field; the app does
not claim an official iOS bundle ID.

The implementation follows the pinned upstream
[wire DTOs](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/Shared/CMUXMobileCore/Sources/CMUXMobileCore/MobilePhonePushKeyExchange.swift),
[iOS exchange flow](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileShell/Sources/CmuxMobileShell/MobileShellComposite+PhonePushKeyExchange.swift)
and [host implementation](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Sources/Mobile/MobileHostService+PhonePushKeyExchange.swift):

- Descriptor version 1, raw X25519 public key, independent installation/key UUIDs
  and envelope version 2. Only the public descriptor is sent over RPC.
- The service validates the saved computer against host status first. The key
  response must match the current account, host instance and `mac:` namespace
  derived from its build ID; a supplied team must match the active team.
- Host status uses a **directory computer ID**, while the exchange returns its
  **physical push device ID**. These are intentionally not compared directly.
  The trusted connection associates the returned physical ID with this saved
  computer; they are never inferred from incoming push data. The response's Mac
  installation ID is retained separately for encrypted reply addressing.
- Invalid descriptor/version/type/size and low-order public keys are rejected.
  Returned keys are trusted only through this admitted host RPC, not a relay.
- Phone key material and at most 128 saved-Mac peer associations live inside the
  existing Android Keystore AES-GCM account store. Identity survives ordinary
  app reconstruction. Login replacement/sign-out removes keys and peers; a new
  login creates a fresh phone identity. This intentionally scopes key lifetime to
  a login, unlike iOS's installation-level Keychain record.
- Credential transactions prune forgotten owners and stale logins. Safe pairing
  alias repair keeps the peer association; ambiguous aliases drop it and require
  a fresh exchange. Current login/team/pairing/opt-in checks run before and after
  the RPC and again inside the storage transaction before persistence.

No push device/token registration, Firebase configuration, cloud request, Mac
listener or user-account migration is part of this change. Opting out still stops
the service; it does not erase the same-login private key. Future push delivery
must independently enforce opt-out and unregister its token when configured.

### Why inline Reply is not exposed yet

The audited iOS app enables Reply using reply-capable **push** metadata. Its
`notification.feed.list` item DTO has no reply-shape field, so the current Android
feed cannot prove which notifications accept text. iOS's background reply relay
also requires the authenticated Mac peer key and installation/build context. This
checkpoint supplies key exchange/storage first. Inline reply UI, reply envelope
addressing, relay submission/retries/failure feedback and push metadata admission
remain required; no functional Reply control is claimed by this checkpoint.

### Key-exchange verification

**21 JVM tests passed, no skips:** six `PhonePushKeysTest` cases, five push crypto
regressions, six dismissal cases and four reconciliation cases. A framed mobile
RPC fixture checks the actual method, request versions, Android build label,
public-only descriptor and installation/client-ID separation. A valid response
pins the physical identity even when it differs from the saved directory ID.
Contradictory account/team/instance/build/version replies never pin and stop after
three attempts. Other cases cover unsupported hosts, revocation after the reply,
cancellation during retry, malformed/low-order keys, persistence, login rotation,
forgotten owners and alias repair/ambiguity.

**Android: OK (8 tests), 38.042 seconds**, API 37 / 16,384-byte pages, zero skips.
`PhonePushKeyStorageTest` constructs real Keystore-encrypted account storage,
reconstructs both phone identity and peer, checks preferences contain no plaintext
private key or identity, and verifies forget, login replacement and clear. Seven
notification delivery/service/swipe regressions passed in the same run. All
account/key/peer data in these tests is isolated fixture data. This does not prove
an exchange with the user's physical Mac or suspended push/reply delivery.

Both APK builds, five native LOAD/RELRO alignment checks and both 16 KB ZIP checks
passed. SHA-256:

- Debug: `f9b459ab1176922f0a4d91e03aeff21ec71f65ded067d6473bc08679d02c222a`
- Test: `36140313bd0ae197798b9ef5c65ef7c26d22fe2230ba68b290347d051ea119d5`

Ignored evidence: `captures/runtime/push-keys-{build.txt,jvm,android,alignment.txt}`.
The one existing emulator was stopped after verification. No signed release,
physical Pixel installation, production key exchange or parity pin changed.
