# Android push sender components

These are the FCM HTTP v1 transport and encrypted durable outbox for a trusted Mac forwarder or backend.
It is **not a running forwarding service**. There is no listener, device registration,
Firebase project or deployment in this directory. Importing the module makes no
network calls. All tests inject an in-memory HTTP transport.

```sh
node --test push/*.test.mjs
```

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
  account/team authorization, Mac forwarding/privacy/away policy and opt-in.
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
  permits: binding => registrationsAndPolicyStillPermit(binding),
  retire: binding => compareAndRetireExactRegistration(binding)
});
const { nextDueAt, counts } = outbox.status();
```

Import `PushOutbox` from `./outbox.mjs`; the other values above are host integration
points, not existing enrollment APIs. `eventID` is stable for the original event;
generation is a nonempty opaque string changed on registration/token replacement.
`recipient` and the registration must come from independently authenticated state,
not from the event's claimed identity. Enqueueing does not establish authorization.
`permits(binding)` is synchronous and must return exactly `true` for the retained
token, registration ID/generation, recipient, account/team and current forwarding
policy. Missing/false permission retires the event. Throw for temporarily
unavailable policy state to retry instead. The frozen binding contains
`{registration, token, recipient}`; check all fields. The transport must honor the
supplied admission predicate immediately before sending, as `FcmSender` does.

`retire(binding)` must durably and idempotently **compare every binding field**
against current registration state before deleting/retiring it. Return `retired`
or `superseded`; never delete a newer token/generation by installation ID alone.
The queue persists the exact UNREGISTERED retirement intent before invoking this
callback. A crash or callback failure retries retirement, without sending again.
The intent expires with the event, so failed enrollment storage cannot create an
unbounded retirement backlog. This callback contract still needs the real
authenticated registration store.

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

## Remaining end-to-end work

1. Choose/provision the dedicated Firebase setup or establish official backend
   deployment access. The Mac's currently selected Google Cloud project is not
   implicitly authorized for this app.
2. Add explicit Android Firebase configuration and token enrollment, rotation,
   opt-out and unregister tied to the existing account/installation lifecycle.
3. Implement the admitted Mac/backend notification subscription and encrypted
   sender identity binding. A private helper needs its own reviewed trust/enrollment
   design; do not extract or replace the official Mac's private key silently.
4. Integrate `PushOutbox` and `FcmSender` into that host with its credential-store
   key, scheduler, current membership/hide-content/away policy, admission
   backpressure and atomic exact-token retirement. Local queue tests do not
   establish that a running host has implemented these contracts.
5. Verify real Pixel delivery, Doze/process death, dismissal, replies, logout,
   token changes and revocation before describing push as functional.

References: [FCM HTTP v1](https://firebase.google.com/docs/cloud-messaging/send/v1-api),
[FCM errors](https://firebase.google.com/docs/cloud-messaging/error-codes),
[service-account OAuth](https://developers.google.com/identity/protocols/oauth2/service-account).
