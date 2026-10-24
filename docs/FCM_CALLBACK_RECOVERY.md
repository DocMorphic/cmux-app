# Recovering Firebase token callbacks across unavailable storage

The original pinned iOS PushRegistrationService persists token registration
state, preserves old-token cleanup on confirmed rotation, and avoids cancelling
an in-flight registration for the same token. Its whole service/test files match
`a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`; hashes are in
`captures/recovery-20261023/fcm-callback-source-bindings.json`. Expanded original
[Original Xcode reference run 40069199630](https://github.com/DocMorphic/cmux-app/actions/runs/40069199630) passed 198 tests in 19 suites (auth plus push),
including same-token concurrency, rotation ownership and repeated-token cleanup.
This is original library evidence, not an Android or live Firebase result.

Android's callback payload is deliberately not accepted as the SDK's current
token: delivery can be delayed relative to another SDK update. Previously,
PhoneFcmTokens.onTokenChanged first loaded its encrypted lifecycle. An unavailable
read returned null and lost that callback; startup could later reuse an old
snapshot. The old callback invalidation also rotated consent and removed token
revision before a new SDK read, disturbing otherwise unchanged registration.

## Durable ingress and atomic replay

The callback protocol uses two content-free UUID metadata fields in the existing
`phone_fcm_tokens` preference file: latest event and applied event. Ingress commits
a new latest event and removes its previous acknowledgement; replay records the
applied event atomically with the encrypted state. No callback
payload, token, account identity or project is stored in those fields. Actual
app-owned token/project/grant state remains in its existing Keystore-encrypted
record. SDK-owned storage is not reimplemented or covered by that claim.

Ingress creates no credential-store object and takes no crypto monitor. Its
separate signal monitor protects only metadata reads and preference commits,
never Keystore/decryption/encryption. Thus a callback can commit while another
thread is blocked in crypto. Recovery checks the content-free pending event and
enqueues persistent WorkManager work before constructing/reading credentials.
A failed crypto read cannot consume that wakeup. Startup/resume/boot and worker
retry continue to converge through the existing recovery path.

The token document's next readable transaction stamps the event into its
SDK-read generation and commits new ciphertext together with the applied UUID.
The latest event is not removed. A callback arriving during encryption can write
a newer event without waiting for crypto; the final commit checks under the
signal monitor and rejects the older projection. It cannot acknowledge or erase
the newer event. Failed encryption leaves the event and original ciphertext
intact. Malformed metadata is an unavailable verdict with a sanitized exception,
never an empty lifecycle; a valid SDK signal can replace that malformed marker.

An empty lifecycle acknowledges an event without creating a cipher/key, grant
or provider operation. The production worker now checks for an actual lifecycle
before opening a state-mutation baseline, so the queued no-consent wakeup does
not create an encrypted empty document. No callback grants push consent or
chooses a project. Explicit user consent and installed-project/owner checks still
control every SDK fetch/delete.

## Read fencing and registration continuity

A token read ticket combines the existing consent grant with a separate callback
epoch. The epoch remains sticky after a successful read, preventing an older
pre-callback read from matching again after acknowledgement. Begin/accept both
compare the ticket. A callback during an SDK fetch discards that result and makes
the existing bounded pass re-read SDK current state; storms leave a retryable
pass after three attempts. Existing SDK Task serialization/non-cancellable
completion is preserved, so provider deletion cannot overtake a replacement get.

A callback alone preserves the consent grant and last accepted snapshot. Only
an actually different SDK token changes token revision. That keeps unchanged
helper proof, checkpoint and in-flight enrollment work intact. Existing recovery
uses last accepted snapshots while checking; new offer/enrollment admission
requires a validated callback epoch. The readiness flag is encrypted and survives
reconstruction even after metadata acknowledgement. Actual rotation flows into
the existing helper renewal protocol with its retained encrypted key/proof.
No new pairing is inferred from a callback.

The helper HTTP ownership guard now distinguishes a typed unavailable credential
read or pending SDK check from a definite ownership failure. Unknown state returns
a retry before sending or accepting a response, preserving the pending enrollment
checkpoint if storage recovers before its result is handled. Definite lost
ownership still retires the attempt. Real local TLS tests cover both admission
and storage becoming unavailable during a delayed response.

The original APNs callback supplies authoritative device-token bytes. Android
keeps last accepted state while requesting SDK current state instead; this is an
explicit adapter difference. Fresh offer admission waits for that read. Existing
registrations can remain on the prior token until confirmation and normal helper
renewal; duplicate callbacks do not themselves delete those registrations.

## Verification limits

JVM cases cover stable consent/revision for repeated tokens, confirmed rotation,
rejection of pre-callback tickets, no-consent/project boundaries, callback during
fetch, bounded storms and fresh-admission readiness. Android cases use real
Keystore documents and injected SDK results: unavailable/reconstructed records,
blocked crypto ingress, encryption/commit races, failed replay, empty/no-consent
records and malformed metadata. A production ingress/WorkManager case observes
queued work and the no-lifecycle worker path. A helper-ledger case preserves its
key/proof for duplicate tokens and queues renewal on actual rotation.

These cases do not deliver a callback from a real Firebase installation or
verify an actual token rotation/delete on the Pixel. Object reconstruction is
not OS process death, and an injected provider error is not actual protected-data
lock/unlock. Marker durability requires a successful preference commit; hanging
or failing filesystem operations do not acquire a proved bound here. Network
constraints, WorkManager scheduling and serialized SDK completion still control
when the new current token can be obtained. Physical Firebase/helper HTTPS,
complete backend/SDK/metadata equivalence, native UI/accessibility/source adoption
and the broader PARITY.md/REMAINING_WORK.md gates remain open.

No phone/Mac install, live account or frozen signed release is changed here.

## Checkpoint evidence

Final debug app and instrumentation APKs build successfully. The full JVM run
passed 3,269 of 3,273 tests across 443 suites, with four existing SSH skips and
zero failures; `captures/recovery-20261023/fcm-callback-build-v2.log` and
`fcm-callback-jvm-v2/summary.json` in that folder. Two local TLS cases cover the
helper unknown-state retry behavior.

Final owned API 36 cohort: 75/75 in 153.922s, empty crash log, no preflight
ANR, restored settings/window configuration and emulator exit 0. Seven new
callback cases and one helper-ledger rotation case accompany the prior 67-case
auth/logout/storage/helper/terminal/settings/UI cohort. Final evidence is in
`fcm-callback-runtime-v2` in that folder. The earlier 75-case v1 run also passed
but predates the helper HTTP fix; v2 uses the final tested APKs.

Ten source bindings, both APK hashes, original pinned library reference, runtime
receipts, JVM totals and open gates are recorded in `fcm-callback-checkpoint.json`
in that folder. The frozen signed722 SHA remains unchanged.
