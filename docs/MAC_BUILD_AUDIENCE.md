# Consumer Mac build compatibility — 2026-10-03

## Explicit Android audience

Both Android debug and release builds select the distributed iOS consumer policy.
Android's debuggable flag does not select upstream's internal development audience.
The reference is cmux `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`, specifically
MobileMacBuildCompatibilityPolicy, MobileMacCompatiblePairedMacStore and
MobileShellComposite+BuildCompatibility. This scoped implementation does not
advance the global parity pin or change Android's package/version identity.

## Admission and discovery

- Accepted instance tags are `default`, `nightly` and `rc`, after trim/lowercase
  normalization. Development and staging tags are excluded.
- Authenticated namespaces may be absent or `legacy`, or must identify the official
  stable bundle, nightly bundle (including dotted variants), or RC bundle
  (including dotted variants). Namespace matching is case-sensitive.
- Only a locally authorized Tailscale connection may use the missing-tag exception
  for numeric Mac versions from 0.64.17 inclusive to 0.64.18 exclusive. An Iroh
  connection or push cannot claim that exception. The independent minimum-version
  floor still applies; the current production floor rejects 0.64.17.
- Admission follows account and expected-host checks, before recording version
  observations. Unsupported authenticated hosts retire the shared wire. Malformed
  scalar status fields are rejected.
- Discovery, presence snapshots/events, saved pairing visibility, diagnostic
  connection checks and power-session access apply the consumer tag rules.
  Stripping the tag from a stale Iroh locator cannot bypass the resolved Mac check.
- Legacy saved rows with a null tag remain visible for authenticated enrichment.
  Their visibility does not itself permit a live connection. Raw storage remains
  available for full sign-out and exact-scope cleanup.
- Pasted, scanned and incoming Iroh pairing links with explicit unsupported tags
  produce build guidance before directory lookup. Android directs users to a
  stable, nightly or RC Mac release.

## Background delivery

The production FCM and reply workers also apply the consumer policy to the pinned,
authenticated push peer tuple. These HTTPS workers do not open normal Mac RPC
connections, so they require their own checks. Unsupported received messages are
removed without posting an alert. Unsupported queued replies are not transmitted;
existing retry/expiry handling remains in effect. A tagless push does not receive
the local Tailscale exception. This change does not sweep pre-existing OS alerts.

## Verification

130 focused JVM tests passed with zero failures/errors/skips. They cover policy
boundaries, authenticated gate retirement, valid empty version policy, independent
version floors, mixed discovery, stale locators, presence snapshots/events,
Tailscale authorization, saved-route isolation and pinned push tuples.
Final debug/test APK assembly completed in 36 seconds.

Two Android worker tests passed in 7.979 seconds on the existing API 37 / 16 KB
AVD: a genuinely encrypted fixture from an unsupported build cannot post an alert,
and a queued reply cannot POST to the relay after membership refresh. The tests
use synthetic account state and a local MockWebServer. The emulator was stopped;
no new AVD was created. Evidence is under captures/runtime/mac-build-audience.

Debug SHA-256: 350933d6533ca7f7dde8117370444ee9966301dfdffb116b39505cbbaa77b02d.
Test SHA-256: 7ae5b17fd4864d6926d2cd9bb1c5d65c2f33c6bd8d7eb0d25dd5f81c53e7eb18.

## Remaining scope

Physical stable/nightly/RC Mac acceptance and the current Pixel/browser flow
remain unverified at this checkpoint; ADB did not list the Pixel. Configured
Firebase delivery and sender registration remain pending. Signed build 474
predates this feature; no signed workflow was dispatched for this single feature.

Upstream's internal development profile, explicit expected tag, persisted sibling
grants and owner-only grant updates are not implemented. They require a separate
explicit Android development identity. The consumer policy is immutable for the
process; no runtime development grant update is claimed. Fully offline cached
account UI, presence version metadata and the broader source/visual audit remain
separate work.
