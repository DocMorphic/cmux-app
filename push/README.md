# Android push sender transport

This is the FCM HTTP v1 delivery component for a trusted Mac forwarder or backend.
It is **not a running forwarding service**. There is no listener, device registration,
Firebase project or deployment in this directory. Importing the module makes no
network calls. All tests inject an in-memory HTTP transport.

```sh
node --test push/fcm.test.mjs
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

## Remaining end-to-end work

1. Choose/provision the dedicated Firebase setup or establish official backend
   deployment access. The Mac's currently selected Google Cloud project is not
   implicitly authorized for this app.
2. Add explicit Android Firebase configuration and token enrollment, rotation,
   opt-out and unregister tied to the existing account/installation lifecycle.
3. Implement the admitted Mac/backend notification subscription and encrypted
   sender identity binding. A private helper needs its own reviewed trust/enrollment
   design; do not extract or replace the official Mac's private key silently.
4. Connect an encrypted durable outbox to this transport, respecting current
   membership, hide-content, away/always policy, expiry and exact-token retirement.
5. Verify real Pixel delivery, Doze/process death, dismissal, replies, logout,
   token changes and revocation before describing push as functional.

References: [FCM HTTP v1](https://firebase.google.com/docs/cloud-messaging/send/v1-api),
[FCM errors](https://firebase.google.com/docs/cloud-messaging/error-codes),
[service-account OAuth](https://developers.google.com/identity/protocols/oauth2/service-account).
