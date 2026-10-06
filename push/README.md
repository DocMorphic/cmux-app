# Android push sender components

These are event preparation, Mac CryptoKit encryption, FCM HTTP v1 transport,
encrypted outbox/registration storage and an explicit scheduler for a trusted Mac
forwarder or backend.
It is **not a running forwarding service**. HTTPS factories remain unbound until
a host explicitly starts them; no Firebase project or production deployment is
provisioned here. Importing the modules makes no network calls. Tests use synthetic
credentials with injected transports or local TLS servers, never the live provider.

```sh
node --test push/*.test.mjs
```

## Host forwarding pipeline

`PushForwarder` in `forwarder.mjs` composes recipient lookup, `preparePushBatch`,
`PushDispatcher`, the encrypted outbox and the sender. Construct it with the
existing stores, sender and sealer plus a **trusted local** `readHost()` callback.
It does not implement that source, discover credentials, bind a listener or create
Firebase resources. Never use an incoming event or phone request as `readHost`.

The callback supplies a freshly authenticated snapshot:

```js
{
  state: 'ready', epoch: hostAccountAndKeyEpoch,
  observedAt: verifiedAtMillis, validUntil: verifiedAtMillis + 30000,
  authority: { accountID, teamID, macDeviceID, macInstanceTag, macBuildID,
    senderKeyID, macInstallationID, publicKey },
  settings: { forwardingEnabled, mode, admission, hideContent }
}
```

Use a new epoch on account/session/key retirement; do not reuse a prior epoch on
sign-in. `mode` is `always` or `onlyWhenAway`; `admission` uses native status values.
A freshly observed `state: 'signed-out'` retires old work. Missing, malformed,
future-dated or expired observations are temporary unavailability, preserving
queued work within its original expiry. A source must revoke/replace its snapshot
on changes; the 30-second maximum lifetime is a backstop, not a substitute for
observing account and policy transitions.

```js
const forwarder = new PushForwarder({ registrations, outbox, sender, seal, readHost });
forwarder.start();
const prepared = await forwarder.prepare({
  event, expiresAt: originalExpiry, sourceEpoch: capturedHostEpoch,
  phoneEligible: true // Only a verified phone-forwarding producer can establish this.
});
if (prepared.kind === 'prepared') forwarder.enqueue(prepared);
await forwarder.stop(); // Await encryption and provider work before closing keys/stores.
```

The owning source must preserve the original correlation/expiry, durably retain
prepared ciphertext for admission recovery and manage replay/cursors. Retry the
**same** prepared batch after storage/capacity errors, never fresh encryption with
the same event ID. Preparation reports suppressed/retired/expired/no-recipients,
busy or stopped without queueing a prefix. Complete phone/part fanout is atomically
admitted. New events apply native away policy; dismissals and already-admitted
retries do not wait for away presence. Current forwarding, identity, helper key,
registration and privacy remain checked at delivery, including after OAuth.

Encrypted queue metadata retains the event kind, actual content-redaction choice,
host epoch and sender identity. Legacy metadata-free jobs are rejected by this
host policy. Tightening privacy prevents an older unredacted queued alert from
being sent; it is not re-encrypted or given a new expiry. Metadata is not part of
the FCM message. Split batches check cancellation/policy between encrypted parts.

**Source integration is still required.** At scoped upstream commit
`186cec79781256867ad4516f0802118738bd2393`, `TerminalNotificationStore` can call phone
forwarding even with `effects.record == false`, while `notification.created` is
published from store changes and its content is redacted. History insertions also
do not encode the exact focused-pane eligibility decision. Therefore neither a
feed diff nor an unconditional `phoneEligible: true` on general events reproduces
the native producer. Integrate at an authenticated source exposing the actual
phone-forward decision and complete payload; include explicit dismissal and
account/policy changes. Key/TLS provisioning, that source and real delivery remain
open. See [verification](../docs/PUSH_DELIVERY.md#forwarding-pipeline-and-durable-privacy-policy--2026-10-06).

## Integration contract

`FcmSender` requires a Firebase project ID and an OAuth token provider with async
`get()` and `invalidate(token)`. `serviceAccountTokens(credentials)` implements
the latter using a provisioned service account's RS256 JWT exchange, the messaging
scope, a pinned Google token endpoint, a bounded token lifetime and an in-memory
cache. ADC/workload credentials can instead be supplied through the same provider
interface. Keys and access tokens must never enter the Android APK, logs or Git.
The module does not read files or environment credentials automatically.

`send({token, envelope, recipient, expiresAt}, {permits})` takes:

- An FCM token from an independently authenticated, current Android registration.
- One already-encrypted cmux authenticated-HPKE v2 envelope. Encryption is a
  separate caller responsibility; use the existing reviewed crypto implementation.
- A separately admitted recipient: `installationID`, `keyID`, `senderKeyID`, and
  `tuple` with account, optional team, phone build/installation and Mac
  device/instance/build. Its shape uses the upstream iOS wire field spelling.
  **Do not derive this authority from the incoming envelope.**
- The expiry of the encrypted event, never a newly extended retry deadline.
- A synchronous `permits()` predicate checking the current registration generation,
  account/team authorization, Mac forwarding/privacy policy and opt-in. Away
  presence is evaluated when the host admits a new notify event.
  Missing or non-boolean predicates deny delivery. Check this state again for every
  attempt; registration rotation/revocation must invalidate retained work.

Only encrypted `{cmux: "{encryptedPayloads:[...]}"}` data is sent. There is no
notification banner, topic, condition or collapse key. Identity binding, canonical
base64, ciphertext size, UTF-8 provider size and a maximum 15-minute transport TTL
are checked before OAuth and again after it. The caller must ensure the provided
expiry actually matches the authenticated plaintext; this transport cannot decrypt
it. High-priority messages must produce a timely visible notification through the
existing Android ingress path; don't use this sender for background analytics.

The input is cloned before asynchronous work. Google endpoints are pinned and
redirects are rejected. Responses/errors expose only coarse outcomes and retry
timing, never provider diagnostic bodies, credentials or device tokens.

## Outcomes and durable queue behavior

| `kind` | Caller action |
| --- | --- |
| `accepted` | Google accepted the message; this is not proof the phone displayed it. Complete the queue entry. |
| `expired` | Drop the expired event without extending its lifetime. |
| `retired` | Drop work no longer authorized by the retained registration/account. |
| `unregistered` | Retire only the exact token/registration generation that was attempted; a newer token must survive. Only an FCM `UNREGISTERED` detail on HTTP404 produces this outcome. |
| `unavailable` | Retry with exponential backoff/jitter, at least `retryAfterMs`, only while the original event remains fresh and authorized. Network errors, malformed success and HTTP5xx may be ambiguous. Reuse the exact ciphertext/correlation ID so Android deduplicates. |
| `credentials` | Fix sender IAM/project/credentials; don't delete the phone registration. |
| `rejected` | Inspect local payload/configuration; generic HTTP404/INVALID_ARGUMENT is not proof a token is unregistered. |

Only an explicit HTTP401 gets one immediate OAuth refresh/retry, with admission
and expiry rechecked. Other retries belong to the caller's durable queue. OAuth
acquisition failures have not sent a push. Quota responses impose at least one
minute of delay and honor a longer Retry-After.

## Encrypted durable outbox

`outbox.mjs` uses the built-in [Node SQLite API](https://nodejs.org/download/release/v22.16.0/docs/api/sqlite.html)
and requires Node **22.16 or newer** (the transport alone still works on Node20).
It uses SQLite WAL transactions with FULL synchronous writes and 60-second claims.
Separate local processes can share the database; use a local disk, not a network
filesystem. A killed worker's claim becomes available when its lease expires.
An old worker cannot complete a replacement claim, and its send predicate checks
ownership and freshness again after OAuth. This is **at-least-once delivery**:
a crash after Google accepts a request but before the completion transaction can
cause another send. The exact original HPKE ciphertext, correlation ID and expiry
must survive every retry so the Android receiver can deduplicate it.

The host supplies a dedicated private directory and a stable 32-byte secret from
its credential store. **The queue never provisions, writes or logs that key.**
Losing it prevents recovery; a mismatched key fails without erasing existing work.
On Unix, nonprivate or differently owned directories/files and a symlink at the
queue directory/database are refused. A Windows host must enforce its own private
ACLs; this is not a Windows forwarding implementation. Tokens, event IDs,
registration generations, recipient identities and envelopes are AES-256-GCM
encrypted in the database. Queue identities use keyed hashes; only scheduling,
lease and coarse outcome metadata remain unencrypted. Corrupt records stop a pass
with a coarse error instead of being sent or silently removed.

```js
const outbox = new PushOutbox({ directory: privateDirectory, key: keyFromCredentialStore });
outbox.enqueue({
  eventID: stableEventID,
  registration: { id: admittedRegistration.id, generation: admittedRegistration.generation },
  delivery: { token, envelope, recipient, expiresAt }
});
await outbox.drain({
  sender: fcmSender,
  permits: (binding, admission) => registrationsAndPolicyStillPermit(binding, admission),
  retire: binding => compareAndRetireExactRegistration(binding)
});
const { nextDueAt, counts } = outbox.status();
```

Import `PushOutbox` from `./outbox.mjs`; the other values above are host integration
points, not existing enrollment APIs. `eventID` is stable for the original event;
generation is a nonempty opaque string changed on registration/token replacement.
`recipient` and the registration must come from independently authenticated state,
not from the event's claimed identity. Enqueueing does not establish authorization.
`permits(binding, admission)` is synchronous and must return exactly `true` for the retained
token, registration ID/generation, recipient, account/team and current forwarding
policy. Missing/false permission retires the event. Throw for temporarily
unavailable policy state to retry instead. The frozen binding contains
`{registration, token, recipient}`; check all fields. The second frozen argument
is persisted admission metadata, or null for legacy jobs. The transport must honor the
supplied admission predicate immediately before sending, as `FcmSender` does.

`retire(binding)` must durably and idempotently **compare every binding field**
against current registration state before deleting/retiring it. Return `retired`
or `superseded`; never delete a newer token/generation by installation ID alone.
The queue persists the exact UNREGISTERED retirement intent before invoking this
callback. A crash or callback failure retries retirement, without sending again.
The intent expires with the event, so failed enrollment storage cannot create an
unbounded retirement backlog. `PushRegistrations.retire` implements exact durable
comparison; authenticated enrollment into that store still needs host integration.

The host must schedule a bounded `drain()` pass on enqueue/startup and at
`status().nextDueAt`. That time includes cleanup of blocked/completed entries at
their original expiry; null means the queue is empty. There is no daemon/timer on
import. The default pass limit is eight, maximum 64. Retry delay uses exponential
backoff plus jitter (1–120 seconds), never earlier than provider Retry-After.
Errors report only coarse outcomes; operational code must not log bindings.
Credentials/rejected outcomes park until explicit `resumeBlocked(kind)` after an
operator fixes the cause. Neither can retire a device registration. Completed
entries erase their payload and retain deduplication receipts until expiry.
Duplicate enqueue never replaces live ciphertext or extends its deadline.

Capacity is 128 entries by default (configurable up to 512), including receipts;
SQLite main-database pages are capped at approximately 8 MiB. WAL/checkpoint files
are additional. Capacity/storage failures are reported to the caller, not handled
by evicting fresh notifications. The host must handle admission backpressure and
storage errors. The directory/key lifecycle, scheduler, enrollment and policy
store still need integration into the chosen running helper/backend.

## Registration storage and dispatcher

`PushRegistrations` in `registrations.mjs` stores **already authenticated**
enrollments in a separate SQLite file with the same private-directory requirement.
The host supplies a credential-store key; all tokens, registration IDs/generations,
public keys and recipient tuples are encrypted. Slot IDs use a keyed hash of the
full account/team, phone installation/build and Mac instance/build tuple. The
constructor/import does not discover credentials or initiate enrollment.

```js
const registrations = new PushRegistrations({ directory, key });
// authenticatedEnrollment = { token, recipient, publicKey }; the host verifies
// account/team, opt-in, both keys and proof of possession BEFORE calling this.
const current = registrations.replace(authenticatedEnrollment, {
  expectedGeneration: previousGeneration ?? null
});
```

`null` is create-only. Token/key changes require the exact old generation, retain
the registration ID, and assign a fresh opaque generation. Retrying an unchanged
value with the current generation is idempotent. Stale writers fail with
`superseded`; a network enrollment API must reconcile that result through its own
authenticated request/response contract. This module is not that API and does not
validate proof of possession. Public-key validation here is format checking;
the authenticated exchange must also validate the cryptographic key.

`recipients({accountID, teamID})` explicitly selects one account/team (`null` means
personal, not all teams). `revoke` durably removes exactly that scope. Build slots
remain separate. `matches(binding)` checks all outbox binding fields against the
current record. `retire(binding)` returns `retired` or `superseded` atomically and
cannot delete a newer token/key generation. Wrong keys/ciphertext corruption do
not yield partial records, and corrupt scope revocation rolls back. Storage
errors are coarse; do not log returned records or bindings. A Windows host would
still need its own directory ACL enforcement.

`PushDispatcher` in `dispatcher.mjs` connects the store, outbox and sender:

```js
const dispatcher = new PushDispatcher({
  outbox, registrations, sender,
  policy: (binding, admission) => liveMembershipAndForwardingPolicyPermit(binding, admission),
  onState: coarseStatus => updateLocalStatus(coarseStatus)
});
dispatcher.start();                 // recovers pending jobs and schedules cleanup
const result = dispatcher.enqueue(alreadyEncryptedEvent);
// After a committed account/policy/enrollment change:
dispatcher.changed();
// After repairing provider credentials/configuration explicitly:
dispatcher.resumeBlocked('credentials');
await dispatcher.stop();           // then the host may close stores/key handles
```

The policy callback must return exactly `true`, based on current account/team
membership, opt-in and forwarding/privacy settings. Apply away presence at new-event
admission; `PushForwarder` implements this distinction. It defaults to denial.
Storage matching alone never grants send authority. Policy must be checked again
while sending; it is not copied into a permanently admitted job. Throw for
transiently unavailable policy state to preserve work for retry. Successful
queueing/provider acceptance still does not prove phone delivery.

Startup and enqueue schedule bounded eight-entry drain passes. Subsequent timers
follow durable retry/lease/expiry deadlines; an empty queue stops polling. New
work wakes a later timer, including work arriving during a pass. Storage failures
report coarse status and back off. Shutdown clears timers and makes pending
admission checks temporarily unavailable, retaining unsent work for restart;
it awaits in-flight work before allowing storage closure. A request already
submitted to the provider cannot be recalled by stopping. Parked credential or
payload errors require explicit recovery and never extend event expiry.

These components passed 38 local Node checks on 22.16.0 and 26.8.2 (9 registration,
6 dispatcher, 23 existing sender/outbox). They do not start a Mac helper, provide
an authenticated enrollment endpoint, initialize Android Firebase, or establish
real cloud/Pixel/Doze delivery. Those remain the integration work below.

## Event preparation and Mac encryption

`preparePushJob` in `events.mjs` converts an **already admitted** notify/dismiss
event into the existing Android receiver's plaintext contract and seals it for
one independently registered phone. Host-owned `authority` supplies account,
team, Mac device/instance/build, sender key ID/public key and Mac installation ID.
It must match the registered tuple; event fields cannot select another account,
team or sender. This matching is not authentication of the source event.

The caller provides the original lowercase UUID `correlationID` and absolute
`expiresAt` in milliseconds, aligned to seconds and at most 15 minutes ahead.
Queue ID and authenticated expiration use those same values. Notify fields are
`title`, `subtitle`, `body`, `replyShape` (`text` or `none`), optional `workspaceId`,
`surfaceId`, `notificationId`, and boolean `retargetsToLiveSurfaceOwner`.
Dismiss uses a nonempty `notificationIds` array. Both require integer `badgeCount`
and boolean `hideContent`. Text is trimmed and limited to upstream UTF-16 budgets
without splitting grapheme clusters. Hidden content is removed before sealing.
Identifiers are never silently truncated. The encrypted result must fit the FCM
budget. Use `preparePushBatch` for dismissal batching, described below. Oversized
notify content still fails explicitly; a large-content retrieval path remains
host/Android integration work.

```js
const seal = cryptoKitSealer({
  executable: absolutePathToBuiltAdapter,
  senderKeyID: provisionedSender.keyID,
  senderPublicKey: provisionedSender.publicKey,
  privateKey: () => readProvisionedSenderKey() // Host credential-store integration.
});
const result = await preparePushJob({
  event: admittedEvent, registration: admittedRegistration,
  authority: currentHostAuthority, expiresAt: originalExpiry
}, { seal });
if (result.kind === 'prepared') dispatcher.enqueue(result.job);
```

Import these functions from `crypto.mjs` and `events.mjs`. `dispatcher.enqueue`
rechecks registration generation/live policy after asynchronous encryption;
preparation by itself does not grant send permission. Retain the entire prepared
job, including original event ID and ciphertext, when retrying admission. Fresh
HPKE encryption of the same event is intentionally an identity conflict with a
pending job, since admission cannot prove that different ciphertext has identical
content. After durable admission, retries reuse the stored ciphertext.
The registration's public key and the sender key must already have
been authenticated and enrolled. **Do not extract the official cmux private key
or replace its Android peer pin to make a private helper appear trusted.** A
separate helper trust lane remains to be designed and explicitly enrolled.

Build the one-shot native adapter on macOS:

```sh
sh scripts/build-push-seal.sh
```

This compiles `PushSeal.swift` to ignored `build/push/cmux-push-seal`. It invokes
CryptoKit's authenticated X25519/SHA256/ChaChaPoly HPKE using the upstream v2
context and sorted JSON tuple encoding. The parent passes the explicit private
key, public keys and plaintext over a private stdin pipe; nothing sensitive is
put in argv, environment, logs or temporary files. The child has bounded input,
output and runtime, verifies the sender key pair and emits only ciphertext or a
coarse failure. Mutable temporary Node buffers are cleared; this is **not** a
guarantee that Swift/JavaScript runtimes erase every internal allocation. No key
is discovered or provisioned automatically. This is a Mac adapter, not a Windows
host implementation, and requires macOS CryptoKit plus Swift tools to build.

The push suite passes **61 checks** on Node22.16.0 and Node26.8.2 on the Mac.
Linux runs skip the four native CryptoKit checks. For the independent pinned
upstream decryptor check, build the existing public-key fixture and set its path:

```sh
python3 scripts/check-phone-push-crypto.py --upstream /absolute/path/to/cmux-source --output captures/runtime/push-event-sealing/reference
CMUX_PUSH_REFERENCE_FIXTURE="$PWD/captures/runtime/push-event-sealing/reference/fixture" node --test push/*.test.mjs
```

The reference is scoped to commit `204a11dfcc76280205e50406ab94270a1c152155`;
this does not advance the global parity pin. Tests cover upstream decryption,
Unicode/omitted tuple fields, low-order key rejection, fresh encapsulation,
redaction, expiry, identity mismatch, provider bounds and a registration rotated
during encryption. No actual cloud/Pixel delivery is established by these tests.
CryptoKit references: [authenticated sender](https://developer.apple.com/documentation/cryptokit/hpke/sender/init(recipientkey:ciphersuite:info:authenticatedby:)),
[cipher suite](https://developer.apple.com/documentation/cryptokit/hpke/ciphersuite/curve25519_sha256_chachapoly).

### Large dismissals and atomic admission

`preparePushBatch(input, {seal, now, maxParts})` accepts the same input as
`preparePushJob` and returns `{kind: 'prepared', jobs}` or `{kind: 'expired'}`.
An event that fits retains its original correlation ID. Oversized dismissals are
split into provider-sized parts, preserving every distinct trimmed notification
ID in source order. Their correlation IDs are deterministic UUIDs derived from
the original event ID and part index. Each part preserves the original expiry,
privacy state, authority, recipient generation and authoritative badge count.

Planning accounts for UTF-8 JSON, HPKE's 16-byte tag, base64, tuple/envelope fields
and the nested FCM data serialization. Every part is planned before any private
key operation. A single identifier that cannot fit, or a batch over `maxParts`
(default128, maximum512), fails before encryption. Each actual encrypted output
is checked again against the provider budget. Expiry or a crypto failure returns
no partially prepared batch. Notify text is not silently shortened to fit FCM.

```js
const batch = await preparePushBatch({
  event: admittedEvent, registration: admittedRegistration,
  authority: currentHostAuthority, expiresAt: originalExpiry
}, { seal });
if (batch.kind === 'prepared') dispatcher.enqueueBatch(batch.jobs);
```

`PushDispatcher.enqueueBatch` checks every retained binding/policy before calling
`PushOutbox.enqueueBatch`. SQLite then admits all jobs in one transaction. A late
identity conflict, queue capacity failure or storage error rolls back earlier
inserts. Invalid/expired batches add nothing. Exact already-queued jobs and
completed deduplication receipts may coexist with new entries; the result reports
`{kind: 'queued', queued, duplicates}`. Existing capacity limits still apply.
Storage errors are coarse, without raw SQLite diagnostics. Source admission must
handle backpressure, retain sealed jobs for retry, and never extend their expiry.

This guarantees atomic **local queue admission**, not atomic provider delivery.
Each part is independently delivered/retried while current authorization permits
and the original event is fresh. Process death after provider acceptance still
has the existing at-least-once semantics. A helper must durably retain or replay
source events not yet admitted; that source subscription lifecycle remains open.

## Initial helper enrollment

`PushEnrollment` in `enrollment.mjs` supplies `issue`, `begin`, `finish`, host-local
`cancel` and `close`. It requires the existing encrypted `PushRegistrations`,
CryptoKit sealer, and a synchronous `permits(offerWithoutSecret) === true` check
against current independently verified host authority. No listener is installed.
`issue` receives the fixed HTTPS endpoint `/v1/push/enroll`, Firebase project/client
identity, expected Android package ID, account/Mac fields, native Mac descriptor
and independent helper descriptor. It returns the private short-lived offer.

`begin` verifies the pairing-secret proof and returns an authenticated HPKE
challenge for the requested phone. `finish` verifies phone-key possession, commits
with the captured registration generation, and returns an authenticated receipt.
Only exact retries reuse the request. Android's `PhoneHelperEnrollment` checks
current local identity/token state and performs the matching proof/confirmation
before saving helper trust. See [the protocol checkpoint](../docs/PUSH_DELIVERY.md#helper-enrollment-handshake--source-checkpoint-2026-10-06)
for verification and the remaining transport/UI/recovery/renewal work. Never expose
`issue` or `cancel` as unauthenticated routes; never use a request-supplied account
or key as its own authority. The future TLS handler must cap bodies before parsing,
disable redirects on Android, and return only coarse errors. This component does
not register with Firebase or supply a running forwarding service.

## HTTPS enrollment transport

`createPushEnrollmentServer({key, cert, enrollment, endpoint})` returns an unbound
TLS server; `endpoint()` must return the configured public URL with path
`/v1/push/enroll`. The caller provisions certificates, chooses the bind address and
port, starts/stops the server and owns live authority/key policy. No shell command
in this module starts it automatically. The route accepts only `{step: "begin" |
"finish", request: ...}`. It exposes no offer generation, cancellation, provider
credentials or terminal API. Do not place an unreviewed plaintext proxy in front
of it or derive the expected endpoint from request headers.

Android `PhoneHelperHttp` uses the exact HTTPS offer URL with certificate and host
validation, bounded JSON, redirect/retry refusal and live admission checks. Pass
successful response bodies through `PhoneHelperEnrollment.finish/confirm`; an
HTTP 200 itself is not proof of enrollment. Retain identical requests for uncertain
outcomes. HTTP409 requires reconciling a superseded enrollment, and retryable
responses carry Retry-After. See [transport evidence and remaining integration](../docs/PUSH_DELIVERY.md#helper-https-transport--2026-10-06).

## Remaining end-to-end work

1. Choose/provision the dedicated Firebase setup or establish official backend
   deployment access. The Mac's currently selected Google Cloud project is not
   implicitly authorized for this app.
2. Add explicit Android Firebase configuration and authenticated helper enrollment.
   Android token acquisition/rotation/opt-out/deletion now has a durable lifecycle
   and WorkManager hooks; its consent and helper-pairing UI now exist. Provider
   configuration and SDK/physical acceptance remain open. See [the checkpoint](../docs/PUSH_DELIVERY.md#android-token-lifecycle--source-checkpoint-2026-10-06).
3. Implement the admitted Mac/backend notification subscription and encrypted
   sender identity binding. Android now has an independent helper sender pin and
   routes replies to the native Mac key, retaining both revocation fences. The
   authenticated helper handshake and confirmation UI now exist; host source/key
   provisioning remain open; never pass unverified network descriptors straight to the pin API. See
   [helper trust](../docs/PUSH_DELIVERY.md#independent-helper-sender-trust--source-checkpoint-2026-10-06).
4. Integrate event preparation/encryption, `PushRegistrations`, `PushDispatcher`, `PushOutbox` and `FcmSender`
   into that host with its credential-store key, current membership/hide-content/
   away policy and admission backpressure. `PushForwarder` composes these parts
   with live snapshot policy; the trusted host snapshot/event provider is still missing;
   local tests do not establish that a running host has implemented these contracts.
5. Verify real Pixel delivery, Doze/process death, dismissal, replies, logout,
   token changes and revocation before describing push as functional.

References: [FCM HTTP v1](https://firebase.google.com/docs/cloud-messaging/send/v1-api),
[FCM errors](https://firebase.google.com/docs/cloud-messaging/error-codes),
[service-account OAuth](https://developers.google.com/identity/protocols/oauth2/service-account).

## Existing registration renewal and removal

`PushMaintenance({registrations, seal, permits, endpoint, senderKeyID,
senderPublicKey})` authenticates `renew`/`revoke` using the existing pinned phone
key. `begin(request)` returns an HPKE challenge; `finish(proof)` synchronously
commits an atomic registration mutation and encrypted retry receipt. Supply this
optional service to `createPushEnrollmentServer` to enable `maintain.begin` and
`maintain.finish` on the same exact HTTPS endpoint. No caller-supplied replacement
public key is accepted. Requests/challenges/proofs and provider tokens must not be
logged.

`permits(binding, action)` must synchronously return `true` from fresh host/account
and key authority. It must support authenticated retirement separately from
sending permission. Do not use the delivery `registrations.matches` gate here:
provider-retired tokens cannot receive alerts but may renew with their pinned
phone key. Use `maintenanceRegistration`/`maintenanceMatches` only inside the
phone-proof service; they are not delivery admission.

Receipt retry requires the same request ID/proof. Durable receipts survive host
restart for 24 hours, and replacement generations invalidate old acknowledgments.
A missing/expired uncommitted challenge returns HTTP428; renew its challenge using
the same request, then persist the replacement before sending finish. A conflict
returns HTTP409. The Android durable coordinator and token/account lifecycle hooks are implemented;
Android OS/Keystore/WorkManager and live provider acceptance remain open. The
coordinator is verified over real local Kotlin-to-Node TLS/CryptoKit/SQLite with
lost responses, token replacement and logout.

`maintain.abort` takes the same request ID/proof as finish. It cancels an existing
uncommitted challenge, or returns `{requestID, ack}` for an already-committed
operation. Missing/expired challenges return a null ack; this is a cancellation
outcome, not proof of any new generation. Finish and abort are synchronous with
the SQLite mutation, so cancellation cannot be followed by a late successful
finish for that challenge. The phone verifies any non-null acknowledgment before
advancing its recorded generation. Fresh initial enrollment uses
`replace(..., {expectedGeneration, forceGeneration: true})` so identical new
pairings still fence old cleanup; ordinary store callers retain idempotent replace.
