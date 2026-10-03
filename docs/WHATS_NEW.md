# Android What's New implementation

Source reference: `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`.
[WHATS_NEW_AUDIT.md](WHATS_NEW_AUDIT.md) records the complete iOS contract. This
now includes the model, persistence, native archive and launch-sheet integration.
Web rendering/session exchange and live feed delivery remain incomplete. The
global parity pin is unchanged.

## Native archive and launch UI (2026-10-04)

`NativeScreen` now exposes **What's New** in Settings only when the eligible archive
is nonempty. `NativeWhatsNewViewModel` retains the center and presentation across
Activity recreation, initializes private-file persistence off the main thread,
and completes the first attempt without a remote feed. Build metadata explicitly
uses notice version 0.2.0, dev for debug and beta for signed development APKs;
it is independent of the host protocol's prod admission identity.

`NativeWhatsNewUi.kt` supplies archive/detail navigation, announcement labels,
feature rows, Mac pairing imagery and minimum-version copy, horizontal paging,
page indicators, Done and Continue. Native pages scroll independently of the
fixed controls. Landscape and large text use the available height. Continue
changes pages without an animated transition. Archive detail selection survives
saved-state restoration and does not acknowledge launch notices.

`NativeWhatsNewPresentation` owns a frozen page array, account-session identity,
presentation token and current page. A launch waits for completed onboarding and
an idle root without incoming routes or other known modal owners. It requires no
discovered computer or fresh account/team network response. A resumed Activity,
laid-out dialog, window focus and a subsequent frame are required before writing
acknowledgement. All pages in the shown snapshot are recorded even if dismissed
early. Staging, covered windows, replaced owners and competing modals cannot mark
unseen content. An already appeared snapshot survives backgrounding/recreation;
remote refresh cannot mutate it. A failed save reports an error without repeatedly
reopening the sheet during that process lifetime.

### Verification

- **29 JVM tests passed** (previous 24 plus 5 presentation tests); initial debug/test
  APK assembly and JVM run passed in 2m 19s.
- The existing API 37 / 16 KB arm64 AVD ran **3 portrait UI tests** (18.910 s),
  **1 landscape navigation test** (6.728 s) and **3 tests at 150% portrait text**
  (15.258 s). Each run reported `OK`, with no skipped tests. Screenshots of the
  launch and archive at normal/large text and the landscape launch were reviewed.
- The first attempt had two appearance timeouts because a separate System UI
  cold-boot ANR dialog held focus. Its XML/window evidence was saved, the observed
  Wait button cleared it, and the **unchanged tests** passed. No appearance guard
  was weakened to make that run pass.
- The real debug MainActivity cold-launched to sign-in with an empty crash buffer.
  These component tests do not establish authenticated Settings entry or physical
  Pixel launch ownership. No account credentials were changed.
- Source review then removed an unnecessarily strict live team/account-refresh
  requirement from the root gate, preserving offline notice presentation after
  onboarding. Final debug/test assembly passed in **53 s**; the tested component
  code was unchanged. `runtime-verification.json` records this final source delta
  and distinguishes the runtime-tested APK from the final rebuilt APK.
- Final debug SHA-256:
  `7687b7cecb9854356c7af2213ee5fd5bcd7659d2fed8fe85f67e5b3689f8a571`.
  Evidence, logs, hashes and screenshots: `captures/runtime/whats-new-ui/` (ignored).
  Original font/rotation settings were restored (1.0/1/0); the single existing AVD
  was stopped/reaped before the final build. No additional AVD was created.

The Pixel remains absent. This work is not in signed build 494. A new signed
milestone was not dispatched for this feature commit.

### Remaining What’s New work

Web pages are excluded from launch until the isolated preload owner is implemented.
A web archive body currently states that web announcements are unavailable in this
development build. **This is a temporary incomplete path, not a parity substitute.**
The compiled Android catalog has no web pages, and no remote feed is configured.

Implement the nonpersistent renderer, optional account-to-web cookie exchange,
per-navigation allowlist, theme-before-load/live updates, concurrent ten-second
launch preload, twenty-second archive deadline, retained Back navigation and
fresh-exchange Retry. Connect only an explicit reviewed Android announcement feed;
never send Android notice IDs to the production iOS endpoint. Add debug-only
replay/suppression without marker writes, measured native-sheet fitting beyond the
current height cap, broader locale negotiation coverage,
physical modal/lifecycle/TalkBack acceptance, and a signed-upgrade/offline archive
check. Verify release ART again at the next signed milestone.

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

## Continuation

Use the remaining-work list above and the full source contract in
[WHATS_NEW_AUDIT.md](WHATS_NEW_AUDIT.md). Preserve native offline behavior,
appearance-only acknowledgement and Android-owned identity while completing web
and physical acceptance. No feed/provider configuration is implied by the UI work.
