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
a notification receiver. Sender enrollment/key storage, registration/token
rotation, current account/team/forgotten-computer admission, payload freshness,
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
