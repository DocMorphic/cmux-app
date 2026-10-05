# Android background push

## Durable sender outbox checkpoint — 2026-10-06

`push/outbox.mjs` adds a local encrypted queue around the existing sender contract.
SQLite transactions retain accepted queue entries and original expiry across a
restart; timed claims coordinate separate processes. AES-256-GCM encrypts token,
registration, recipient and envelope data using a host-supplied credential-store
key. Queue IDs are keyed hashes. Retry timing, attempts, ownership and coarse
states are persisted. A lost claim cannot complete a newer worker's entry.

Unavailable responses use exponential backoff/jitter and provider Retry-After;
credentials/payload rejection park for explicit recovery. Exact UNREGISTERED
retirement intent is durable before the host's idempotent compare-and-retire
callback runs, so a retry preserves a replacement token/generation and does not
send again. Completed records erase payloads and keep bounded deduplication
receipts until expiry. The scheduling hint includes expiry cleanup even when all
entries are blocked/completed. Wrong keys/corrupt records fail closed; capacity
errors do not silently evict fresh messages. Leases cannot guarantee exactly-once
provider delivery after an ambiguous send/crash; original ciphertext is reused
for Android's existing deduplication.

Focused verification covers encrypted disk contents, reopen, identity conflicts,
capacity/expiry, retry deadlines, authorization revoked during OAuth, parked
recovery, exact-generation retirement, stale-worker completion, a real SIGKILL
during send and two subprocesses concurrently draining one local database.
All **23 Node checks passed** on both Node26.8.2 and the minimum Node22.16.0,
including 12 outbox cases and the existing 11 transport cases. Evidence is in
`captures/runtime/push-outbox/`. No Android APK/emulator or live cloud request is
part of this checkpoint. The push package now requires Node22.16+ for built-in SQLite.

This is a host component, not a deployed service. Provider choice/provisioning,
authenticated helper enrollment, sender-key binding, notification subscription,
the policy/registration store and scheduler/key lifecycle still need integration.
Android token lifecycle and real Pixel/Doze/provider acceptance remain open.
See [push/README.md](../push/README.md) for the executable API and host contract.

## FCM sender transport checkpoint — 2026-10-05

`push/fcm.mjs` now implements the sender-side HTTP v1 transport usable by either a
private Mac forwarder or an authorized backend. It sends only a single recipient's
existing authenticated-HPKE envelope in the data-only format the Android receiver
expects. It requires an independently admitted registration/recipient and a current
boolean admission predicate; it does not infer authority from the payload or
perform registration itself. Identity fields, base64, byte size and original event
expiry are checked before OAuth and immediately before FCM. The optional
service-account adapter uses RS256, the messaging scope, fixed Google endpoints,
no redirects, one-hour assertions and a shared in-memory token cache.

The transport distinguishes provider acceptance from device delivery. It refreshes
OAuth once on HTTP401, preserves delivery ambiguity on network/HTTP5xx/malformed
success, honors Retry-After and a one-minute quota minimum, and only classifies an
exact FCM UNREGISTERED detail on HTTP404 as token retirement. The caller must bind
that retirement to the attempted registration generation. Retries retain ciphertext,
correlation ID and original expiry. The module does not start any process, read
credentials, register a device or send on import.

**11 Node tests passed**, using only injected responses and an ephemeral synthetic
RSA key. Tests verify signatures, claims/cache behavior, concurrent token requests,
identity mismatches, size/expiry, retirement during authentication and refresh,
fixed request destinations, response classification and redacted failures. The
existing Android milestone CI now includes these tests. No Android build or cloud
request was performed. Evidence: `captures/runtime/fcm-sender/`; integration
contract and exact remaining steps: [push/README.md](../push/README.md).

Configuration remains absent: GitHub contains the signing secrets, but no Firebase
client/sender configuration. The local Google Cloud CLI has an unrelated selected
project; this checkpoint made no changes to it. The recommended private Firebase
plus Mac helper choice has been presented again and remains pending. That helper's
authenticated enrollment, notification subscription, sender-key binding and durable
outbox still need implementation, along with Android token lifecycle and actual
provider acceptance. This component does not make production push operational.

References: [FCM HTTP v1](https://firebase.google.com/docs/cloud-messaging/send/v1-api),
[FCM error handling](https://firebase.google.com/docs/cloud-messaging/error-codes),
[service-account OAuth](https://developers.google.com/identity/protocols/oauth2/service-account).

## Scoped upstream recheck (2026-10-05)

Current upstream HEAD was `186cec79781256867ad4516f0802118738bd2393`
when checked. Its `web/services/apns/pushDeliveryService.ts` blob is still
`2b67eeba385e8905fa660afa047976eb03785bea`, identical to the October 2 reviewed
`f204ade` source. The delegated `deviceDeliveryLease.ts` blob is likewise unchanged
at `220745b95e9f19a916b69773f47ff5620f18c1b2`: both target claiming and retained
recipient authorization explicitly require `platform == "ios"`. The delivery
service selects `sendApnsNotificationReliably` by default.

This is a scoped source comparison, not a probe of the deployed service or a
complete upstream review. It provides no new Android FCM delivery integration.
The project/provider choice, token lifecycle and real Doze/process-death delivery
remain open. The broad parity and reviewed references are unchanged.

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
device registration has been created. The app now has a locally tested, disabled
until configured FCM receive path, described below; live provider delivery remains
unverified.
The [Firebase client setup](https://firebase.google.com/docs/cloud-messaging/android/get-started)
requires app/project configuration and a messaging service for data payloads.
Notification permission and explicit user opt-in must precede registration.

## Push scheduling follow-up (2026-10-02)

A new WorkManager regression exposed an urgent-delivery delay: the receive path
used `APPEND_OR_REPLACE`, so a new high-priority message became a child of an
older retrying worker. The baseline test failed in 0.297 s with two pending
workers where one independent worker was required. The scheduling relationship
matches Android's [unique-work policy contract](https://developer.android.com/reference/androidx/work/ExistingWorkPolicy).

New ciphertext and a normal-to-high priority upgrade now replace pending receive
work. The encrypted inbox survives cancellation; the existing authenticated,
idempotent delivery path drains it. Ordinary duplicates and startup recovery keep
an existing worker, creating one if absent, rather than adding another child.
Duplicate receipt and priority upgrades do not renew the 15-minute retention.
Priority is retained in the encrypted queue, including reconstruction, so a later
normal message cannot downgrade an already queued urgent message. Older queue
records without that field default to normal priority. Scheduling priority never
establishes message authenticity or account authorization.

Verification on the single existing API 37 / 16 KB emulator:

- **15 JVM tests passed**, zero failures/skips: eight queue/admission/priority
  cases and seven message regressions.
- **1 real WorkManager scheduling test passed in 0.749 s**. Its local worker
  deliberately retries. The check requires new work to be independent of that
  retry, 20 duplicate deliveries and three recovery calls to retain one pending
  job and unchanged ciphertext/expiry, and missing-job recovery to retain both
  queued messages. This uses a test scheduler and synthetic worker, not FCM.
- **5 Android ingress checks passed in 35.222 s**, zero skips: encrypted delivery
  and dismissal, membership failure/revocation, expiry/forgotten Macs, opt-out/
  account replacement and plaintext SDK-banner suppression. Membership HTTP and
  push payloads are local fixtures; no cloud token or provider was created.
- A final combined run passed **all six cases in 38.477 s**, zero skips, with
  the scheduling test first. It closes its test WorkManager and restores the
  normal manager before the delivery cases run in the same process.
- Debug/test APK builds and both 16 KB ZIP gates passed. All six packaged native
  libraries passed LOAD/RELRO alignment. The emulator was stopped after testing.

Debug APK SHA-256:
`f72f05838b1e441c43bdc154840b3796b4d696f28ec6eaf2297c3435b969e1cb`.
Test APK SHA-256:
`f86df702928d6a0e9cc6b52032a8c1740bed2b0ab70c4351552f22f13ab821c1`.
Ignored evidence: `captures/runtime/fcm-scheduling/`, including the failing
baseline, final build/JVM/runtime results and receipt. Signed build 376 and the
physical Pixel installation are unchanged. Live provider/token registration,
Doze/process-death delivery and physical push acceptance remain open; the private
Firebase/helper recommendation still awaits the user's delivery choice.

## FCM receive path checkpoint (2026-10-02)

Both proposed senders can use the same receiver. `PhoneFcmService` accepts only
data messages with the single `cmux` key containing the existing encrypted
`encryptedPayloads` object. Firebase automatic initialization, analytics collection
and notification delegation are disabled. No Firebase client configuration,
token request or registration is included; token rotation, unregister and sender
deployment remain required before live push can work.

The pinned Firebase Messaging 25.0.1 SDK displays notification payloads before
calling `onMessageReceived`. The service therefore sanitizes both SDK notification
key prefixes at `handleIntent`, retaining only its message ID for acknowledgement.
That public SDK method is marked `@hide` in source: upgrades must recheck this
behavior and the guard tests. The manifest removes the SDK fallback service and
registers the app service as nonexported, without direct-boot access. Plaintext
provider banners cannot bypass the app's encrypted-payload checks through either
of the reviewed SDK notification prefixes.

`PhoneFcmQueue` stores ciphertext inside the existing Keystore-encrypted account
record, with a 64-item limit, 4096-byte local input bound and 15-minute retention.
Duplicate receipt does not extend retention. Senders must also respect FCM's total
message size limit, including the data key and other provider overhead. WorkManager
holds no message or credential in its input. Connected work uses bounded-retention
retries and requests expedited execution for effective high-priority messages.
App, service and boot/package-replacement startup recover persisted work.

Before displaying an alert, the worker refreshes account membership over HTTPS,
requires the current login and a saved, cryptographically pinned Mac, and uses the
existing expiry, replay, opt-out and notification-delivery checks. A Mac belonging
to another verified member team can still notify when a different team is selected.
Membership outages retain the queue for retry; fresh revocation cannot post.
Sign-out/login replacement and opt-out discard pending ciphertext. The worker does
not open Mac, Iroh or SSH connections.

### Receiver verification

- **13 focused JVM tests passed**, zero failures or skips: six queue/admission
  cases and seven existing message cases.
- **12 Android checks passed in 72.806 seconds**, zero skips, on the existing
  API 37 emulator with a 16384-byte page size: five ingress cases and seven
  notification-delivery regressions. Tests exercise actual system notifications,
  duplicate suppression, dismissal, local membership HTTP failure/revocation,
  expiry, forgotten Macs, opt-out and login replacement. SDK `RemoteMessage`
  parsing confirms sanitized notification prefixes cannot become notification
  content; runtime inspection confirms no Firebase app is initialized.
- Both debug and test APK ZIP alignment checks and all **six** packaged native
  library LOAD/RELRO checks passed. Firebase initially resolved DataStore 1.1.7,
  whose added native counter library failed RELRO alignment. The explicit,
  unmodified DataStore 1.2.1 dependency fixes that failure. The incompatible APK
  was not installed.

The membership endpoint and worker construction are injected for these tests.
They do not establish Google transport, actual cloud-to-service handoff, token
lifecycle, Doze/process-death delivery or physical Pixel/Mac acceptance.

Ignored evidence: `captures/runtime/fcm-ingress/`, including the original alignment
failure, corrected gates, build logs, JVM XML, instrumentation output and
`receipt.json`. Debug APK SHA-256:
`ec50578ae9de0385c429b091e96a5de9a3dce2a455005e40f899b779471c3fe7`;
test APK SHA-256:
`7adadbc0fffb1ee6a85ecec4378e5eddc44c88ad160e63d9443752f1c091a364`.
The emulator is stopped. Signed build 369 is unchanged.

The following dated sections retain the implementation history; statements about
features not yet integrated describe the checkpoint in that section.

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

## Encrypted reply relay sender (2026-10-02)

`PhoneReplyRelay.kt` adds the sender for the iOS companion's existing
`POST /v1/replies/e2e` contract. It is not yet wired to Android notification actions.
The caller must supply the trusted configured service origin, live account token
and an admission check for the prepared reply's login, team, saved-Mac origin and
current pinned key. Relay URLs must never come from incoming notification data.

The implementation follows the pinned
[iOS relay client](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/ReplyRelayClient.swift),
[relay parser/inbox](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/workers/presence/src/replies.ts)
and [Mac reply consumer](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Sources/Cloud/PhoneReplyInboxCoordinator.swift).

- A prepared reply binds its account and phone identity to the pinned peer. Its
  encrypted payload contains reply ID, physical Mac ID, exact surface, optional
  workspace, explicit retarget permission, literal text and a 15-minute lifetime.
  Workspace-confined replies require a workspace. The body exposes only the
  routing/idempotency metadata and encrypted envelope to the relay.
- Reply encryption addresses the **Mac installation** from authenticated key
  exchange. The canonical tuple still includes the sending phone's installation.
  The existing phone-only push decrypt check and 16 KiB incoming-push limit stay
  intact. Reply encryption permits the relay's larger envelope; final HTTP body
  bytes must fit 64 KiB. Text is bounded to 8,192 UTF-16 characters and is never
  silently truncated, including when JSON escaping exceeds the body limit.
- `PreparedPhoneReply` creates one immutable HTTP body for an intentional reply.
  Retry callers must reuse it: the relay rejects changed ciphertext under the
  same reply ID. Each `send` performs one attempt; automatic HTTP retries and
  redirects are disabled, with a 15-second request deadline and no plaintext
  endpoint fallback.
- New attempts stop after the phone's 120-second retry window. A shared sender
  cooldown honors numeric and HTTP-date `Retry-After` on 429 responses, defaulting
  to 60 seconds when absent/invalid. The caller receives explicit accepted,
  retry, expired, retired, sign-in-required or rejected outcomes. HTTP acceptance
  means the inbox accepted the message, not that the terminal displayed it.
- Admission is checked before token retrieval, again before sending, and after
  responses. Lost account/Mac/key admission cannot acknowledge a late success.
  Closing the sender cancels its active requests; coroutine cancellation cancels
  the corresponding HTTP call. Once the server accepts an in-flight request,
  local cancellation cannot recall it; host-side account/target checks still apply.

### Integration still required

The sender is exercised through fixtures only. No request was sent to cmux's
production relay. It has no notification action/receiver, persistent reply outbox,
background execution owner, failure notice or production `NativeAppConnections`
call site yet. Those must keep the same prepared envelope across retries, obtain
keys from the admitted account store, retire work on account/owner changes, and
wire the result to actual reply-capable push metadata. The existing feed cannot
prove reply capability. Delivery-provider selection remains pending; encrypted
transport compatibility alone does not enable push or inline Reply.

### Reproduction

```sh
CMUX_REPLY_CROSS_OUTPUT="$PWD/captures/runtime/phone-reply-relay/requests.json" \
  ./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest \
  --tests '*PhoneReplyRelayTest' --tests '*PhonePushCryptoTest' --tests '*PhonePushKeysTest'
python3 scripts/check-phone-replies.py --upstream /path/to/cmux-checkout \
  --requests captures/runtime/phone-reply-relay/requests.json
```

The cross-checker extracts pinned upstream Swift crypto and TypeScript parser/
inbox source into ignored captures. CryptoKit runs only the crypto prefix, without
Keychain/storage dependencies. The relay runs locally under Node's TypeScript
support with an in-memory fixture store. Every key/message is fixed or generated
public test material; no actual account, cloud API or terminal is accessed.

### Relay sender verification

**19 focused JVM tests passed, zero skips:** eight `PhoneReplyRelayTest` cases,
five crypto regressions and six key-exchange/storage cases. Real loopback HTTP
checks cover a 503, loss of the response after the server receives the request,
exact-body retries and eventual 202 acceptance; global 429 cooldown, HTTP-date/
large retry deadlines, revoked/expired submissions, revocation during token
retrieval and after sending, in-flight close, coroutine cancellation, 307 without
credential forwarding, and 409 without an implicit resend. Payload checks include
wrong identities, missing confined workspace, blank/oversize text, and preserving
the smaller incoming-push limit.

The pinned **Apple CryptoKit implementation opened both Android reply fixtures**
at the Mac installation and checked exact text, reply ID, workspace/surface,
retarget flag and 15-minute expiry. One fixture contains 8,192 Chinese characters,
exercising a ciphertext larger than the phone-push limit. Supplying the phone as
recipient was rejected. The pinned **TypeScript relay parser/inbox accepted both
requests**, deduplicated exact retries, rejected a different authenticated account,
and rejected changed envelopes under the same reply ID.

The first test compilation used `Files.writeString`, which is unavailable through
this project's Android compile API. Replacing that fixture writer with Kotlin
`File.writeText` resolved compilation; the original log remains. Final evidence:
`captures/runtime/phone-reply-relay-unknown-outcome-build.txt` and
`captures/runtime/phone-reply-relay/{verified-jvm,interop,requests.json}`.

This checkpoint compiled production Kotlin and ran JVM/Swift/TypeScript checks.
It did not rebuild APKs, start an emulator, change a signed release, contact a
production relay or test physical Pixel/Mac reply delivery.

## Persistent encrypted reply queue (2026-10-02)

`PhoneReplyOutbox.kt` now owns prepared relay requests inside the existing
Keystore-encrypted account transaction. Restoration validates versions, bounded
IDs/timestamps, exact routing and key/tuple metadata, ciphertext/encapsulation
sizes and complete JSON. It preserves the original HTTP body string byte for
byte; reconstruction never encrypts again. The plaintext message is not stored.

The queue allows 20 pending replies, matching the server inbox capacity. A full
queue returns `FULL` instead of evicting an earlier user action. Exact duplicates
return `DUPLICATE`; a reused reply ID with a different packet returns `CONFLICT`.
An expired packet cannot be newly queued. Same-login identity/key and pinned peer
checks are required before admitting work; runtime account/team/route admission
remains an additional requirement before any send.

Server retry deadlines are stored with the login and apply to all pending replies.
They survive state reconstruction and the expiry of individual reply receipts.
The queue's two-minute send window is unchanged. Expired or key-retired requests
become content-free `unconfirmed` receipts: an unknown HTTP outcome cannot prove
that the Mac did not receive the reply. Accepted, rejected and sign-in-required
outcomes also receive explicit receipts. Up to 128 receipts are retained for at
most 15 minutes; they contain scope, ID, request digest and status, not message
text or the encrypted request body.

`NativeCredentialStore.update` now prunes the queue atomically with credential
and peer-key changes. Forgotten/re-added Macs cannot resurrect removed work;
login changes/sign-out remove pending packets, receipts and cooldowns. Safe pairing
alias repair preserves the original packet and its trusted owner association.
Key rotation retires the old encrypted packet without re-encrypting its reply ID.

A bounded serial drain function selects currently admitted work, persists each
result before proceeding, and stops on retry/retirement. An unavailable owner
cannot prevent another admitted reply from being selected. Post-response admission
checks prevent a late callback from acknowledging replacement work. If an in-flight
request confirms acceptance just after local expiry, only its matching retained
receipt can advance to accepted; a different packet or retired owner cannot.

This is storage and drain infrastructure. Notification reply actions, the production
background execution owner/scheduler, user-visible failure notices and incoming
reply-capable push metadata are still required. The queue does not start network
work by itself, and object/store reconstruction tests do not establish full app
process-kill or Doze recovery. No production reply endpoint was contacted.

### Reply-queue verification

**21 focused JVM tests passed, zero skips:** seven queue tests, eight relay tests
and six key tests. Coverage includes exact-body reconstruction (including the
large Unicode envelope), strict local metadata/body validation, duplicates and ID
conflicts, capacity without eviction, expiry/unknown-outcome receipts, cooldown
persistence beyond receipt expiry, account/forget/re-add/key retirement, alias
repair, bounded draining across unavailable owners and post-response revocation.
A final race check expires the pending row before a late 2xx: the original packet
can confirm its receipt, while altered local metadata cannot.

**Android: OK (9 tests), 40.118 seconds**, API 37 / 16,384-byte pages, zero skips.
Two `PhonePushKeyStorageTest` cases verify actual encrypted identity/peer storage
and reply storage; seven notification delivery/service/swipe regressions also
passed. A fresh credential-store instance recovered identical HTTP bodies, a
confirmed reply was replaced with its receipt, and account replacement removed
both queued replies and keys in one transaction. Reusing the old login label did
not restore deleted packets. Preferences did not expose fixture text, HTTP body,
login or physical Mac ID. This is storage reconstruction, not a process-kill or
physical Pixel/Mac workflow claim.

Both APK builds passed, all five native libraries passed LOAD/RELRO checks, and
both APKs passed 16 KB ZIP checks. SHA-256:

- Debug: `ba88768ab39fb5acb7ab6e3d984214cce4029318fc980a2b9757a3b2c11b271b`
- Test: `0b644fac4e8d85df3b99a6c4b1dc49977c10e0ebb623bef1944121c3e9d26739`

Evidence is in ignored `captures/runtime/phone-reply-outbox-{final-build.txt,jvm,android,alignment.txt}`.
The existing emulator was reused and stopped. No signed release, production relay
call, physical-device installation or upstream parity pin changed.


## Persistent background reply work and private notices (2026-10-02)

The active iOS [AppCompositionRoot](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/ios/cmux/AppCompositionRoot.swift)
constructs `SystemReplyRelayClient` with the authenticated account's access token
and the resolved presence service. [PresenceServiceConfiguration](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileShell/Sources/CmuxMobileShell/PresenceServiceConfiguration.swift)
selects `https://presence.cmux.dev` before overrides for release or production
auth. Android uses production auth and the same fixed origin for its background
reply worker. No incoming payload can select the service URL. This is separate
from the Iroh broker endpoint and from APNs/FCM notification delivery.

`PhoneReplyWork` durably schedules each prepared packet's failure check before
its send job. The serial send chain uses `APPEND_OR_REPLACE`, so a reply queued
while another drain is finishing gets a subsequent pass. WorkManager input data
contains no message, credentials, saved pairing or encrypted packet. Packets
remain in the existing account-encrypted store. Job names contain only fixed
labels or SHA-256-derived opaque identifiers. Application startup, boot and app
upgrade recover pending work; a user-action producer must await enqueue before
finishing its broadcast.

`NativePhoneReplyBackground` refreshes account membership, verifies the current
login/user and the saved Mac's team/route ownership, and checks pinned keys before
and after sending. It can send for another still-authorized team without changing
the UI's selected team. It starts no Iroh, SSH or Mac terminal connection. Each
pass reloads persisted cooldowns and exact request bytes; cancellation closes
its HTTP calls and leaves uncertain packets available for the next pass. Relay
acceptance means the server inbox accepted the packet, not proof that a command
ran on the Mac.

The send request needs a connected network and requests expedited execution,
falling back to regular work when quota is exhausted. Retries use linear backoff
with a ten-second minimum, in addition to the persisted server cooldown. The
pre-Android-12 foreground fallback has a generic private notification and a
`dataSync` service declaration. These choices follow Android's
[WorkManager request guidance](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).
No exact-alarm permission was added. Android may defer execution under quotas,
Doze, resource pressure or force-stop; this does not guarantee timely delivery.

Like iOS's [ReplyFailureNotifier](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/ReplyFailureNotifier.swift),
a separate failure check is scheduled for the two-minute pending lifetime plus
ten seconds. It has **no network constraint**. This is an earliest requested
check time, not an exact Android alarm. Expired, rejected or sign-in-required
receipts produce “Reply delivery unconfirmed” with generic guidance to check the
Mac before resending. Text, workspace names and pairing data are excluded. An
immutable explicit tap opens the app; it cannot execute or requeue a reply.

Notices are posted only with notification permission and an enabled channel.
A bounded encrypted marker prevents a dismissed notice from returning. Account
replacement/forgetting removes obsolete notices, and a matching late acceptance
cancels its notice. In-app credential observation also reconciles notices after
account/key mutations. Notification permission being disabled does not prevent
queue expiry or change an uncertain result into success.

Delayed execution must not silently erase a failure before its notice runs.
Unconfirmed/rejected/sign-in-required receipts now survive for up to **seven days**,
still capped at 128 and scoped to the current login/owned Mac. Accepted receipts
retain the prior 15-minute bound. Neither the two-minute send window nor the
15-minute matching late-acceptance window is extended. Receipt storage contains
no typed reply or request body. This supersedes the uniform 15-minute retention
at the earlier outbox checkpoint; notices are not promised after seven days or
when the user has disabled notifications.

The notification action/RemoteInput producer and authenticated reply-capable
push payload admission still need integration. Ordinary feed notifications do
not yet advertise the upstream reply capability and must not receive invented
Reply actions. The push delivery choice remains pending. No production relay
request or physical Pixel/Mac reply test was performed in this checkpoint.

### Background-work verification

**22 focused JVM tests passed, zero failures/errors/skips:** eight outbox cases,
eight relay cases and six key cases. The new delayed-first-execution check retires
an hour-old packet without discarding its failure receipt, rejects late acceptance
outside the existing window, and expires the content-free failure after seven
days. The cooldown test still verifies preservation beyond receipt expiry.

**Android: OK (16 tests), 72.271 seconds**, API 37 / 16,384-byte pages, zero skips:
seven `PhoneReplyWorkTest` cases, two encrypted-storage cases and seven existing
notification delivery/service/swipe regressions. Worker cases cover:

- actual loopback account verification and reply HTTP, including a 429 cooldown
  across fresh worker instances and byte-identical retry followed by 202;
- an authorized Mac in a different selected team, and revoked membership that
  never submits a relay request;
- login replacement while HTTP is in flight, and cancellation preserving the
  uncertain request without claiming acceptance;
- actual WorkManager execution of the no-network notice worker, generic private
  content and immutable tap, and cancellation after matching late acceptance;
- dismissed notices staying dismissed, account replacement clearing others,
  and a scheduled notice successfully reporting an hour-old failure receipt.

The HTTP worker uses a test constructor with loopback account/relay origins and
fixture credentials. WorkManager itself executes the notice jobs with its normal
initializer/database; those jobs have no production account or relay calls.
No external service was contacted. The age test sets fixture timestamps; it does
not simulate an hour of Doze. Actual process-kill, reboot, Android job quotas,
force-stop behavior, the pre-Android-12 foreground fallback, production endpoint
acceptance and physical Pixel/Mac delivery remain unverified. Incoming push and
notification Reply actions remain separate integration work.

The first Android test build found a nullable `WorkInfo` access in the test; the
explicit non-null check fixed it. The first 15-case Android run passed in 64.949
seconds. Subsequent review found the 15-minute failure-retention gap described
above; the final 16-case run includes the fix and added delayed test. Original
logs remain in ignored `captures/runtime/phone-reply-work*`.

Both APK builds and all five native LOAD/RELRO checks passed; both APKs passed
16 KB ZIP alignment. Final SHA-256:

- Debug: `0fb0139043988b47032236d463d1b09f2906d65da54e1c745a2c72657be2a9e5`
- Test: `17c78011160f624cb10bb00125311445dc723806815163ac6e735bda5787f252`

Final evidence: `captures/runtime/phone-reply-work-delayed-build.txt` and
`captures/runtime/phone-reply-work/{final-jvm,final-android.txt,final-alignment.txt,device.txt}`.
The existing emulator was reused and stopped. No signed release, physical-device
installation, push registration, native listener or upstream parity pin changed.


## Authenticated notify/dismiss ingress (2026-10-02)

`PhonePushMessage` opens the `cmux` dictionary's `encryptedPayloads` using the
current account's existing phone key and the independently pinned Mac tuple/key.
The caller supplies an independently admitted team and saved Mac; ciphertext
cannot enroll a key, select credentials or authorize its own source. Recipient
build, account, installation, sender and saved route are checked. Ambiguous
matching envelopes, plaintext-only messages, invalid UTF-8/JSON, expired payloads,
wrong types and oversize fields fail before delivery. Unencrypted outer content,
identity, expiry and Reply metadata are ignored.

The typed notify/dismiss fields follow the pinned Mac
[PhonePushRequestEnvelope](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/macOS/CmuxPhonePush/Sources/CmuxPhonePush/PhonePushRequestEnvelope.swift).
The active [Mac encoder](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Sources/Cloud/PhonePushClient.swift)
uses a 120-second event lifetime and places the Mac's installation/build/public
key in the encrypted plaintext. Android rejects any provided plaintext identity
that contradicts the pinned envelope. The [iOS notification extension](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/ios/NotificationService/NotificationService.swift)
opens notify content, while the [app delegate](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/ios/cmux/CmuxAppDelegate.swift)
opens silent dismissal content and refuses to establish a peer pin from push data.
Android applies expiry checks to both operations.

Only authenticated `category == cmux.terminal.reply` plus `replyShape == text`
and a usable surface/workspace-or-retarget destination classify as reply-capable.
This flag does **not yet add a RemoteInput action**. Hidden-content messages are
redacted before creating the native notification model. A missing Mac notification
ID gets a local correlation-based identifier for presentation, with dismissal
explicitly disabled; that synthetic identifier is never sent to the Mac.

`PhonePushInbox` stores bounded, content-free correlation records and dismissal
tombstones in the account-encrypted transaction. It retains unexpired protection
when full and returns `FULL` rather than evicting an older protected event. Limits
are 4,096 correlations and 4,096 dismissed IDs across the login. Duplicate events
and a late notify following its dismissal cannot repost within the retained
window. Expiry, login replacement, forgotten Macs and safe pairing alias repair
are reconciled on credential updates. Key rotation does not let an already opened
message adopt the new peer key.

`NativeNotificationDelivery.receivePush` requires explicit current account/team
admission and the existing notification opt-in. Under the credential transaction,
it rechecks ownership, admits replay state, then stores the opaque tap route and
posts/cancels the system banner. Push notifications can arrive before the first
feed baseline; later feed observation stays quiet. Feed/push duplication is
suppressed by the shared notification ledger. Programmatic remote dismissal never
queues an Android-to-Mac swipe. Disabled system channels prevent notify delivery;
authenticated dismissals can still clean up.

This entry point is independent of a push provider. **No FCM registration/service,
Mac forwarding helper or production payload delivery is wired yet.** The pending
provider decision still applies. Its future receiver must refresh/verify account
membership and supply the current-admission callback, constrain the provider's
wire size, and handle lifecycle/cancellation. Numeric badge updates, foreground
selection suppression, RemoteInput actions and physical push/reply acceptance
remain open. Ordinary feed banners still have no Reply action.

### Notify/dismiss verification

**28 focused JVM tests passed, zero failures/errors/skips:** seven message/inbox
cases, five HPKE cases, ten notification-ledger cases and six dismissal cases.
The new cases open actual pinned-Mac payloads encrypted with Apple CryptoKit and
cover trusted notify/dismiss fields, outer-metadata injection, no plaintext
fallback, expiry/types/bounds, reply confinement, redaction, absent IDs,
replay/dismiss ordering, saturated-cache behavior and key/account/forget fences.

`scripts/check-phone-push-messages.py` compiles the pinned Mac payload encoder
and CryptoKit implementation. The crypto source is copied unchanged up to its
existing Keychain boundary. The payload encoder only loses its package import;
an inert fixture `AuthenticatedSessionSnapshot` satisfies its unused `belongs`
method. Payload construction/encryption code is unchanged. The fixture uses
fixed public test keys and accesses no Keychain, account or network service.
The committed vectors make ordinary JVM tests independent of macOS/Swift.

Regenerate intentionally with:

```sh
python3 scripts/check-phone-push-messages.py --upstream /path/to/cmux-checkout \
  --write-vectors app/src/test/resources/push/apple-push-messages.json
```

**Android initial regression run: OK (13 tests), 64.427 seconds**, API 37 /
16,384-byte pages, zero skips. Four new push-delivery cases, two encrypted-key/
reply-storage cases and seven notification service/delivery/swipe cases passed.
The push cases use actual encrypted storage, local HPKE and NotificationManager;
they do not register with or contact a remote push provider.

Review then found that the new delivery method posted its banner inside the
route-store transaction, before that transaction committed. Posting now occurs
only after route persistence completes, while account mutation is still fenced.
The final **four affected Android cases passed in 18.5 seconds**, zero skips.
The strengthened test reconstructs the route from another store/delivery object
at the final admission gate, asserts no banner is visible yet, then verifies
normal delivery. Other checks cover duplicate/feed-baseline behavior, remote
cancellation without a swipe outbox entry, dismiss-before-notify, missing-ID
banners without a DeleteIntent, and expiry/opt-out/account retirement.
This is not a real process-kill, Doze, physical Pixel/Mac or provider-delivery test.

Final APK builds, all five native LOAD/RELRO checks and both 16 KB ZIP checks
passed. Final SHA-256:

- Debug: `7b4e67ab34ed49d3f22fed7d3109b9cb04bd22b8774aedf3fb070ad3a7b673de`
- Test: `6a13936aae0d8750c34fd4ab434971585cc1b3406d3d09d22cda20cf41be3944`

Evidence: ignored `captures/runtime/push-messages/`, including upstream source
hashes/Swift output, JVM XML, both Android runs, final alignment and device receipts.
The existing emulator was reused and stopped. No signed release, production
registration, listener, physical installation or broad parity reference changed.


## Android notification Reply actions (2026-10-02)

Authenticated reply-capable push banners now contain Android's native **Reply**
action with the iOS “Message the agent…” prompt. The action targets an explicit,
non-exported broadcast receiver. Its mutable PendingIntent is required for
RemoteInput; fixed component/action/data contain only independent opaque route
and action UUIDs. Input extras cannot select a different Mac, workspace, surface,
account or pairing. Generated replies/contextual actions are disabled. This uses
Android's [direct-reply API](https://developer.android.com/develop/ui/compose/notifications/create-notification#reply-action),
including a notification update to finish the system input UI.

The [iOS category setup](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/MobilePushCoordinator.swift)
uses a text Reply action, and [PendingReplyState](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/PendingReplyState.swift)
starts the 120-second reply lifetime at user submission. Android likewise permits
replying to an older, still-displayed banner; the short incoming-push expiry does
not shorten that interaction. A cancelled/dismissed banner cannot submit through
a cached PendingIntent. Invalid, unavailable or full-queue actions show generic
feedback without claiming delivery; an accepted local submission first shows
“Reply queued”, then removes the original banner after durable job scheduling.
The existing private failure-notice worker handles unconfirmed delivery.

`PhoneReplyActions` saves bounded ready/consumed grants with the authenticated
route and peer metadata in the **same account-encrypted transaction** as the
outbox. There are at most 512 grants. Consumption and prepared-packet enqueue
commit together. The first successful submission chooses the packet once; a
repeated broadcast cannot encrypt/send a different message under the same action
ID, even after the old delivery receipt expires. A full queue retains older
replies and leaves the action retryable. Literal text is preserved without
trimming or adding a newline, with the existing text/encrypted-size bounds.
The action store contains routing/public-key metadata, never typed text.

Each peer pin now carries a local enrollment UUID. Repeating the same authenticated
exchange preserves it; changing a key or forgetting/re-adding a Mac creates a new
one. Actions, already opened messages and action-created queued packets retain
that incarnation, preventing old work from reviving when the same Mac key is
paired again. This field stays local and does not alter the cmux wire protocol.
Pre-existing pins without an incarnation can still receive notifications; Reply
becomes available after their next authenticated exchange. Workspace/surface IDs
now accept the Mac payload encoder's 200-character bound.

Push delivery has two persistence phases: it previews replay admission, saves the
tap route/action grant, commits both stores, then rechecks ownership and posts.
The replay record is committed after posting. Thus death before posting leaves
the event retryable, while any exposed action already has a durable grant. The
receiver checks the actual active notification action and saved grant, atomically
queues/consumes it, and awaits WorkManager scheduling within a bounded background
broadcast. It creates no new Mac terminal connection.

A feed banner that appeared first can gain Reply when its matching authenticated
push arrives. This upgrade requires the original banner still to be displayed.
A marker prevents later duplicate pushes from re-arming a reply/status banner;
cleared banners stay cleared. Ordinary feed-only banners remain without Reply.
Push-only delivery now also applies the existing 512-route notification bound.

The receiver currently uses the encrypted HTTPS relay lane even when the app is
foregrounded. iOS's direct send through an already-ready foreground terminal is
still an integration task. FCM/provider registration and incoming message delivery,
foreground-selection suppression, badge behavior, physical Pixel/Mac submission,
locked-screen behavior and background lifecycle fault acceptance remain open.
No production reply endpoint was contacted by these tests.

### Reply-action verification

**35 focused JVM tests passed, zero failures/errors/skips:** six new action-state
cases, seven message/inbox cases, six peer-key cases, eight outbox cases and eight
relay cases. The new coverage includes submission after the original push expires,
200-character destinations, exact packet restoration with enrollment binding,
repeated/changed broadcasts after receipt expiry, invalid/oversize input,
full-queue behavior, no action without authenticated capability, wrong routes,
key refresh versus rotation, forget/re-add, login replacement and bounded-grant
eviction. Existing transport/crypto/replay regressions also passed.

**Android: OK (24 tests), 111.978 seconds**, API 37 / 16,384-byte pages, zero skips:
four action cases, four push-delivery cases, seven background reply/notice cases,
two encrypted-storage cases and seven notification delivery/service/swipe cases.
The new action cases exercise the actual notification-shade **Reply → type Unicode
text → Send** flow through the manifest receiver and encrypted outbox into a
persisted WorkManager job. The Mac fixture key opens the queued ciphertext and
checks exact text, workspace/surface and confinement. The original banner is
removed after scheduling; replaying its PendingIntent keeps the same one packet.

The action fixture asserts it is running on an emulator and disables Wi-Fi/mobile
data before installing fixture credentials. It confirms no active network and
an enqueued send job. Cleanup erases the fixture account/outbox and cancels the
jobs before restoring the emulator's prior radio settings. No fixture token was
sent to the production service. Separate worker tests retain their loopback-only
account and relay servers. The user's physical phone was not modified.

A second case submits RemoteInput through the real PendingIntent while attempting
to replace its component, data and destination extras with another notification's
values: only the original surface is present in the resulting Mac plaintext.
Other cases verify invalid input leaves the action available, account replacement
retires it, plain notifications have no Reply, a visible feed banner gains an
authenticated Reply, and queued/cleared banners are not reactivated by later push.

The first test compilation used an unavailable `By.textMatches` API; using the
supported `By.text(Pattern)` overload fixed it. The initial three UI cases passed
in 30.258 seconds. Review then added the feed-before-push upgrade and its fourth
case, followed by the full final run above. Screenshot capture now waits for the
input accessibility value and UI idle; the final input image was visually checked
and displays the complete fixture text “literal reply λ 中” with the Send button.
These are emulator/component fixtures, not physical delivery, locked-screen,
process-death or Doze acceptance.

Both final APK builds, all five native LOAD/RELRO checks and both 16 KB ZIP checks
passed. Final SHA-256:

- Debug: `7e7b63bbeba1ddd0a48a05ae2c8b040837fe33377a7f24c35a48c2bbd96ec0d8`
- Test: `ae5a3e56a74281266ceca924f8f80b394fa9b50f24e14976b1060a92b482e7df`

Evidence: ignored `captures/runtime/phone-reply-actions*`, including JVM XML,
original/final build logs, both Android runs, final alignment/device receipts and
notification-shade images/XML. The existing emulator was reused and stopped.
No signed release, production push registration, listener, physical installation
or broad upstream parity pin changed.


## Foreground notification presentation (2026-10-02)

The native screen now publishes an ephemeral selection to the notification
presenter. A resumed screen suppresses a new banner only for its current login,
paired Mac/installation and workspace, and, when supplied, the exact displayed
terminal ID. A workspace-only notification is quiet in that workspace. A browser
tab never borrows the previously selected terminal ID. Other Macs, build variants,
workspaces and terminals still alert. Authorized origin aliases follow the saved
Mac; arbitrary matching workspace IDs on different Macs do not.

The presentation rule follows `MobilePushCoordinator.shouldPresentInForeground`
at audited candidate `204a11dfcc76280205e50406ab94270a1c152155` (lines 963–1004).
Android additionally requires a resumed lifecycle, an interactive screen and an
unlocked keyguard. Settings, SSH navigation, licenses, pairing/restore transitions
and leaving the native screen clear the published selection. Lifecycle pause
clears it immediately without waiting for recomposition. Closing one screen does
not clear another window's selection. Nothing is persisted or restored after
process death; a saved workspace checkpoint cannot suppress a background alert.

Both the live-feed and authenticated-push presenter apply the rule. Suppression
records local handling so a later feed or a new correlation for the same Mac
notification cannot resurrect the banner. It does not enqueue `notification.dismiss`,
mark the Mac notification read, or remove the notification from the in-app feed.
Incoming dismiss messages still cancel previously posted banners. Previously
posted banners are not removed merely because the user opens their terminal.

### Direct Reply path audit

The same pinned iOS coordinator resolves the reply's explicit Mac/workspace/surface
against local topology, sends through an already-ready terminal channel without
changing UI selection, and otherwise uses the HTTPS relay (lines 1427–1609).
After a failed direct send it also falls back to the relay. Android currently
retains the relay-only path; adding direct delivery remains required.

The audit found an acknowledgement ambiguity that must be handled during that
integration. `PhoneReplyInboxCoordinator` deduplicates relay sweeps by `replyId`
(lines 135–139, 174–194), but injects only `surface_id`, optional `workspace_id`,
`text` and `submit_key=return`. It does not pass the relay ID to the terminal input
ledger. `MobileHostTerminalInputApplier` deduplicates direct input with a separate
surface/stream/sequence identity. The coordinator's direct call does not carry
`replyId`. Consequently, a direct write whose acknowledgement is lost cannot be
assumed safe to resend through the relay. This is a source-based risk assessment,
not a reproduction on the user's Mac or a claim about later upstream versions.

Before enabling the direct path, persist a send-state fence together with the
existing outbox transaction, use only an admitted existing foreground connection,
preserve ordering and workspace confinement, and prevent a concurrent worker or
process restart from relaying a possibly applied direct write. A confirmed send
can consume its packet; a provably unwritten action can fall back; an ambiguous
write must surface unconfirmed delivery unless the host supplies shared duplicate
tracking. Tests must cover lost acknowledgements, account/key changes, process
death and the worker/receiver race. No Mac/backend protocol change was made here.


### Foreground-presentation verification

**14 JVM checks passed**, zero failures/errors/skips: four presentation-policy
cases plus ten ledger regressions. They cover exact-terminal and workspace-only
matching; different logins, Macs, builds, workspaces and tabs; authorized aliases;
missing workspace IDs; non-inferred retargeting; multiple owners and empty
process-local state after reconstruction.

**Android: OK (19 tests), 103.39 seconds**, API 37 / 16,384-byte pages, zero
skips. Eight notification delivery cases, seven encrypted-push cases and four
Reply-action cases passed. The new delivery case uses the real LifecycleRegistry
and NotificationManager to verify resume/pause/dispose, cross-Mac delivery and
no queued Mac dismissal. Push cases verify quiet delivery while the terminal is
selected, no resurrection after leaving it, alerts for a different selected
terminal, and incoming dismiss processing while selected. A screen-off case
uses the emulator's real power state and deliberately leaves a stale selection
registered: the banner still posts. Existing actual notification-shade Reply and
system-swipe tests passed in the same run.

Both final APK builds, all five native LOAD/RELRO checks and both 16 KB ZIP checks
passed. SHA-256:

- Debug: `eeb0c7c192bd4c88879c27a3bea9d43a2170f9423e2d99baf830ffdf600d4398`
- Test: `53b10b00d962ce3e4dd7175c0ebf42be4158878fb690187a003a3c8ca011c3c5`

Evidence is in ignored `captures/runtime/notification-visibility/` and the
adjacent build logs. The existing AVD was reused and stopped. This verifies the
presenter, lifecycle binding and fixture delivery; full native-screen navigation,
secure-keyguard/multi-window behavior and physical Pixel/Mac acceptance remain
open. The Pixel was absent from ADB. No signed release, push provider, production
backend, Mac listener or broad parity pin changed.


## Direct notification Reply delivery (2026-10-02)

Reply now prefers an admitted connection already held by the foreground native
screen. The current Mac uses its existing terminal input dispatcher and ordered
queue; another connected Mac uses its verified feed channel. It does not dial,
refresh topology, select a Mac, change the selected workspace/tab, or start the
foreground application runtime from a background broadcast. With no ready
foreground target, the previously implemented encrypted relay path remains the
normal background lane.

The authenticated action captures login/team, Mac origin, peer enrollment/key,
workspace, surface and retarget policy. Local resolution requires a ready terminal
in the original workspace; only a retargetable notification may choose the unique
live surface owner. Selection and account ownership are rechecked immediately
before delivery and again after ordered input waits. The direct RPC uses literal
`text` and `submit_key=return`, leaving agent-aware Return/Ctrl+Enter choice to the
Mac. Workspace confinement was checked against upstream `TerminalController.swift`
at `204a11dfcc76280205e50406ab94270a1c152155`: `v2ResolveWorkspace` (5194–5213),
`v2MobileTerminalPaste` (15906–16036), and `mobileResolveWorkspaceAndSurface`
(16325 onward), plus `TerminalController+ControlTerminalBinding.swift` (164–180).
An explicit workspace must own the explicit surface; there is no selected-tab
fallback when a supplied workspace is missing.

### Durable separation between direct and relay attempts

Before any direct write, action consumption and its encrypted packet are saved
in one account transaction with `direct_only=true` and local record version 2.
Older builds only accept version 1 and therefore cannot reinterpret a direct
packet as a relay retry after a downgrade. Releasing a provably unwritten packet
to the relay restores version 1 without re-encrypting. These local fields are never
sent over the wire. Relay candidate enumeration, background worker admission and
the HTTP transport all reject this packet. Expiry reporting is durably scheduled
before writing; if the process dies, restart/boot recovery keeps the fence and
reports an unconfirmed result after the send window. It cannot blindly relay a
possibly applied write. No plaintext reply is persisted for direct recovery.

A live attempt is single-use and bounded to four seconds within the receiver's
eight-second work window. If its ready connection retires before entering the
write, it can prove that no text was sent and atomically release the original
ciphertext to the relay. If writing starts but the acknowledgement is lost,
delivery becomes unconfirmed, the packet is erased into a content-free receipt,
and no automatic resend crosses into the relay lane. A successful direct response
consumes the packet. Cancellation invalidates the permission closure of queued
work, so an ordered action waiting behind earlier typing cannot begin a new write
after its caller has timed out. An input already admitted to the existing exact
input sender retains that sender's duplicate protection; the relay stays fenced.

The upstream paste result can contain `submitted=false` after text was accepted
but its submit key failed. Android preserves a `submit_required` receipt and shows
**Reply needs submission**, advising the user to submit the pasted text from the
terminal. It never pastes that text again. Missing or malformed submission
confirmation, including a duplicate acknowledgement without submit evidence,
remains unconfirmed. This avoids falsely claiming that an agent received a prompt.

This deliberately tightens the pinned iOS fallback behavior described in the
preceding audit: direct and relay delivery have separate host duplicate tracking.
An ambiguous direct write requires checking the terminal unless upstream provides
shared tracking. It is not an Android networking restriction. Live push-provider
integration and physical end-to-end acceptance remain separate open requirements.


### Direct-reply verification

**61 focused JVM checks passed**, zero failures/errors/skips: five direct policy/
ordering cases, twelve outbox cases, seven action cases, nine relay cases,
eighteen foreground feed coordinator cases and ten terminal input session cases.
New coverage includes confinement and moved-surface resolution, readiness/account
retirement, exact Unicode/newline preservation, one-use attempts, queue ordering,
lost acknowledgements, cancellation of future writes, partial submission, strict
record-version restoration, restart fencing, unchanged-ciphertext fallback and
late acceptance. A two-Mac socket fixture verifies only the requested existing
connection receives `terminal.paste`, with no extra connection or selection RPC.
The HTTP fixture verifies direct-only packets cannot even request a token.

**Final Android run: OK (23 tests), 118.321 seconds**, API 37 / 16,384-byte pages,
zero failures/skips: eight Reply action cases, eight worker/notice cases and seven
encrypted push cases. The actual manifest receiver consumes RemoteInput through
the notification PendingIntent and selects a Compose-registered foreground owner.
A framed MobileRpcClient transport fixture captures exact paste text, workspace,
surface and Return intent, confirms the encrypted fence exists before the write,
and returns or deliberately loses the host acknowledgement. Assertions verify no
relay job for applied/uncertain/partial replies, exactly one write, specific
submission advice, and an unchanged encrypted fallback for an unwritten attempt.
The reconstructed real worker makes zero account/relay HTTP requests for fenced
work, but still reports expiry. Existing shade Reply, push suppression/dismissal,
key/account retirement and background retry tests pass in the same final run.

The direct transport fixture uses no socket or production server. Its action
suite keeps emulator radios off before fixture credentials exist and cancels work
and erases credentials before restoring connectivity. Worker HTTP tests remain
loopback-only. Real native-screen navigation, host terminal effects, secure
keyguard/Doze, process-kill timing and physical Pixel/Mac delivery remain unverified.

The first JVM compile failed on a malformed test fixture expression; it was fixed.
The initial 23-case Android run had four failures in new-test cleanup because the
Compose rule only permits `setContent` once. Cleanup now removes the registered
owner through Compose state, and all eight action cases passed in 60.844 seconds.
The later record-version compatibility change was then rebuilt and checked by
all 61 JVM and all 23 Android cases above. Original logs are retained; the failed
run is not counted as a pass.

Both final APK builds, all five native LOAD/RELRO checks and both 16 KB ZIP checks
passed. SHA-256:

- Debug: `2bbd854a4ea1c5f5a6d0030df11e985c82140ef904a15adb35827718206065eb`
- Test: `5bfa56c587bff23fc1bf68feb22048bd57970a5db29852d1c6cb3ff9b781c20c`

Evidence: ignored `captures/runtime/phone-reply-direct/` (`final-android.txt`,
`final-jvm/`, `final-alignment.txt`, device receipts, earlier runs) and adjacent
build logs. The single existing AVD was reused and stopped. No signed release,
physical installation, provider registration, production request, host listener
or broad parity pin changed.


## Android launcher badges and notification settings (2026-10-02)

The pinned iOS coordinator requests badge authorization and lets APNs apply
`aps.badge`, including on silent dismiss pushes. On the Mac,
`TerminalNotificationStore` passes `indexes.unreadCount` to both notification and
dismiss forwarding. That is the emitting store's absolute count, not a number to
multiply across individual Android banners. Sources at the audited candidate:
[MobilePushCoordinator](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/MobilePushCoordinator.swift)
and [TerminalNotificationStore](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Sources/TerminalNotificationStore.swift).

Android's supported launcher badge API is based on active notifications on
supporting launchers. `setNumber` describes how many messages **one** notification
represents; it is not an app-wide unread-counter API. Each cmux alert continues to
represent its own event with Android's default count. Existing authenticated
Mac-read/dismiss, phone-dismiss, account-retirement and opt-out paths remove the
corresponding active notifications. The authenticated push `badgeCount` stays
validated but is not projected onto every banner. No invisible badge-carrier
notification or launcher-specific broadcast is used. The in-app Notifications
feed retains its unread count independently of Android's shade/launcher state.

Both ongoing service channels (`cmux_connection` and `cmux_reply_send`) now set
`showBadge=false` before first creation. Agent alerts and actionable reply-result
notices keep their existing channel defaults. Android recommends excluding
ongoing activity from badges; no launcher defect has been reproduced on the
physical Pixel. Platform sources reviewed October 2:
[notification badges](https://developer.android.com/develop/ui/views/notifications/badges),
[channel ownership](https://developer.android.com/develop/ui/compose/notifications/channels)
and [setShowBadge](https://developer.android.com/reference/android/app/NotificationChannel#setShowBadge(boolean)).

An already-created Android channel's badge setting cannot be changed by the app.
Its ID is therefore preserved, with no deletion or replacement that could reset
user mute/sound preferences. **Settings → Android notification settings** opens
this installation's system page. If an existing ongoing channel allows badges,
its category shortcut and explanation let the user disable those badges there.
The screen re-reads channels after returning from Android Settings. If the system
activity is unavailable, an accessible inline message gives the manual path.
These links contain only the app package/channel, never account or pairing data.

This is an explicit platform difference: stock supported APIs do not reproduce
an independent iOS numeric launcher badge after all banners have been cleared.
A launcher dot/count's final appearance remains launcher- and user-controlled.
Live provider wake-up/delivery and physical launcher acceptance remain open.


### Badge/settings verification

Both APKs built. On the existing API 37 / 16 KB AVD, all sixteen unchanged
notification-delivery and reply-worker cases passed. The initial nineteen-case
run had one failure in the new system-settings test: the test searched for a text
label, whereas Android exposes the collapsing app header as an accessibility
description. Screenshot and UI hierarchy inspection confirmed the correct cmux
system page. After correcting only that selector, all three new settings cases
passed in **18.53 seconds**, zero failures/skips. The production APK digest stayed
unchanged between runs. The new checks exercise real Android channel immutability,
fresh-channel badge defaults, existing mute/sound/badge preservation, actual
system-page navigation, intent scope and the unavailable-settings recovery UI.
The isolated settings layout and system-page screenshots were inspected; these
are not full native-screen or physical launcher acceptance.

Both final APKs passed 16 KB ZIP checks; all five native LOAD/RELRO checks passed.
SHA-256:

- Debug: `faa58413974cbfaebf3a992f05bbcb8d2335cd826e8f2a11ef1d39fc7bffc8ef`
- Test: `f9a29d0074e8bd07b8a9ccc4773fcfd1582ecc81870325568e1378435320c908`

Evidence: ignored `captures/runtime/notification-badges/`, including the original
nineteen-case log, repaired three-case log, build/alignment logs, API/page-size
receipt, system hierarchy and inspected captures. The single AVD was reused and
stopped. No physical phone installation, signed release, push-provider setup or
broad parity reference changed.


### NIGHTLY detach semantics for direct replies — 2026-10-03

Audited the installed NIGHTLY source at `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`:
`MobileShellComposite.sendRemoteTerminalPaste` gates direct traffic on the retained
terminal attachment state, while `MobilePushCoordinator.applyPendingReplyIfReady`
may use the encrypted relay when direct paste returns false. Consequently, detach
is not treated as revocation of a fresh notification-reply action.

Android now retains detach per account/team/Mac/build/surface across navigation and
applies it to both selected-terminal and secondary-feed direct attempts. Secondary
leases also check the owner's gate at the RPC write boundary. Already-prepared
attempts that become detached before delivery are unavailable without writing.
The existing encrypted relay remains usable for provably unwritten actions; the
durable fence still prohibits relaying an uncertain direct write.

The combined sizing/feed/direct/outbox regression run passed **68 JVM tests** with
no failures/errors/skips. This is deterministic/local-socket evidence, not live push
provider acceptance. No provider setup, cloud deployment or phone installation was
performed. See `TERMINAL_SHARED_SIZING.md` for exact scope and remaining physical gates.
