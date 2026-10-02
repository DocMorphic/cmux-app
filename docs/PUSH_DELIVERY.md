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
