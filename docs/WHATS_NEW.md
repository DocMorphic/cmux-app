# Android What's New implementation

Source reference: `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`.
[WHATS_NEW_AUDIT.md](WHATS_NEW_AUDIT.md) records the complete iOS contract. This
checkpoint implements the model and persistence; it does **not** yet expose an
archive or launch sheet in the app. The global parity pin is unchanged.

## Model and persistence (2026-10-03)

- `NativeWhatsNewModel.kt` defines namespaced binary/announcement identities,
  feature/pairing/web bodies, development/beta/internal versus prod/demo targeting,
  the iOS dotted-numeric comparator, typed remote decoding and content localization.
  One malformed announcement is skipped; malformed authoritative visibility keeps
  the previous list. Remote channel lists replace compiled declarations.
- `NativeWhatsNewCatalog.kt` contains permanent **Android-owned** IDs for the
  introduction and Iroh pairing guide, targeting Android 0.2.0. Future entries
  prepend; existing IDs must not be repurposed. It copies no iOS release claims.
- `NativeWhatsNewCenter.kt` exposes immutable state through StateFlow. The cache
  is scoped to the configured scheme/host/port. An explicit empty list retracts
  binary pages; a wholly retired nonempty list falls back to the eligible compiled
  catalog. Required pairing pages survive omission only when channel/version
  eligible and the list is not explicitly empty.
- Announcements precede binary entries, and a reference to visible native content
  is deduplicated. A hidden native body cannot return through an announcement;
  only its own web or inline content may render. Web pages remain in the offline
  archive but are excluded from launch candidates until a successful fetch this
  launch. URLs share one cmux/API-host policy; credential-bearing URLs are also
  rejected. HTTP is restricted to an explicitly configured localhost/127.0.0.1.
- Initial refresh failure permits cached native content; cancellation does not
  complete the attempt. Superseded refreshes and closed owners cannot publish late
  results. `preparePresentation` rechecks both identity and content after preload:
  a changed URL or retracted page cannot reuse an earlier successful load.
- Staging writes no acknowledgement. The UI must call `acknowledgeAppearance`
  **only after the frozen sheet appears**, for all presented pages. The marker uses
  the full compiled order, advances across skipped releases and never regresses.
  An unknown marker remains quiet on downgrade. Announcement IDs are separate and
  pruned only after a successfully decoded and persisted authoritative response.
- `NativeWhatsNewFileStore.kt` writes a complete JSON ledger to a unique temporary
  file, syncs it and atomically replaces the previous generation. The future UI
  owner must place this in `noBackupFilesDir`; it contains no account credentials.
  Failed replacement keeps the entire previous ledger and cleans the temporary
  file. Cache and acknowledgement updates commit together. Failed saves leave
  model state unchanged and return an actionable error rather than claiming success.

The file store is for the main UI process. A shared path lock serializes its
instances within that process; it is not a cross-process database. No Android
announcement endpoint, provider credentials, HTTP client or account-to-web cookie
exchange has been configured. The center accepts an injected loader; a null
loader explicitly completes the first attempt without fetching a feed.

## Verified scope

Command:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/dharmaydave/Library/Android/sdk \
  ./gradlew :app:testDebugUnitTest --tests '*NativeWhatsNew*Test' --max-workers=1 --no-daemon
```

**24 JVM tests passed, none skipped** (21 model, 3 real file-store tests). Gradle
completed in 16 seconds. The checks include channel/version boundaries, empty
versus stale visibility, origin isolation, malformed-entry isolation, duplicate
namespaces, native retraction, regional/English localization fallback, rejected
web URLs, offline archive retention, cancellation, superseded/closed refreshes,
full-catalog acknowledgement, downgrade markers, failed persistence, late
retraction and changed web content during staging. File tests reopen the ledger
and inject an atomic-replacement failure to verify the previous generation survives.
Evidence: `captures/runtime/whats-new-model/` (ignored).

These tests do not exercise Android UI, WebView isolation, cookie exchange, live
remote service delivery or the physical Pixel. No emulator or signed build was
started for this checkpoint. Signed build 494 predates this work.

## Next integration

1. Define Android notice build metadata independently of the host protocol profile:
   short version 0.2.0, debug dev and signed development beta. Do not derive notice
   channel from `AndroidMacProtocolProfile.BUILD_KIND` (host admission uses prod).
2. Instantiate a retained center/file store in the UI owner; complete its initial
   attempt with no remote feed configured. Add Settings archive/detail navigation,
   native feature/pairing pages and announcement badges. Reuse the existing Mac
   guide assets and compatibility policy.
3. Add launch staging/appearance acknowledgement after onboarding/account recovery
   and explicit incoming routes. Restored competing modals, backgrounding, account
   replacement and debug replay must not acknowledge unseen pages. Do not require
   any discovered Mac. Freeze the visible page list throughout the presentation.
4. Implement nonpersistent web rendering and the reviewed optional session exchange,
   allowlist every navigation, ten-second concurrent launch preloads and twenty-second
   archive retry. Retain a loaded page on Back, refresh its theme, and make Retry
   perform a fresh exchange. Only connect a reviewed Android feed with its own schema;
   never send Android IDs to the production iOS endpoint.
5. Verify portrait/landscape/large-text/swipe/Continue behavior and modal ownership,
   then actual signed upgrade/appearance/offline archive on Pixel. Broader locale
   negotiation, web deadlines and lifecycle acceptance still need runtime coverage.
