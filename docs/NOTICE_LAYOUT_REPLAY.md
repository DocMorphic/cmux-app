# Native notice layouts and debug replay

Scoped iOS source: `186cec79781256867ad4516f0802118738bd2393`,
`MobileWhatsNewContent.swift`, `MobileWhatsNewListView.swift`,
`MobileWhatsNewSheet.swift` and `Debug/MobileWhatsNewDebugView.swift` under
`Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/`.
Source snapshots/hashes are in `captures/runtime/notice-layout-replay/`.
The global implemented/reviewed pins remain unchanged.

## Implementation — 2026-10-06

- Archive native details measure regular content, then compact content, and use
  a compact scroll viewport only when neither fits. Measurement probes are not
  placed and clear their accessibility semantics. Rotation, width and font scale
  changes remeasure content.
- Launch sheets follow the separate iOS sheet implementation: compact natural
  content inside a scroll viewport, with the selected page driving sheet height.
  Natural measurements remain independent of viewport height; the available
  height caps the sheet without discarding otherwise unchanged content sizes.
  Existing fixed Done/Continue controls and appearance acknowledgement remain.
- Centered headings, announcement badges, decorative accent symbols and denser
  feature rows replace the logo/left-aligned text stack. Known SF Symbol strings
  map to existing Android vectors; unknown strings use a neutral update symbol.
  Duplicate feature titles retain positional identity. Release labels remain in
  the archive list, as in the reviewed iOS source.
- Pairing content has a centered instruction block, theme-aware Mac screenshot,
  account/team instructions and a tinted stable/nightly compatibility section.
  Version values come from Android's actual host admission profile. Long nightly
  versions stack under their labels to remain readable on narrow Android screens.
- Debug Settings > What's New > Replay selects an inclusive range by namespaced
  page key, accepts reversed endpoints and freezes the selected page content.
  Replay state and page selection survive Activity recreation through the notice
  ViewModel; account changes and leaving the archive retire the replay. It has no
  center/storage dependency and no appearance acknowledgement callback.
- Replay web pages use the existing authenticated archive renderer and its
  loading/error/retry behavior. Dismissing replay closes those sessions. The
  production launch preload/acknowledgement path is unchanged. Release builds
  neither instantiate replay state nor expose its entry point.

## Verification and remaining acceptance

Main and instrumentation Kotlin compilation passed in **73 s**, together with
**31 JVM cases**: four replay, five presentation and 22 center cases. Replay
coverage checks namespaced/reversed ranges, frozen feature content, stale
account/page callbacks and archive exit. The raw logs and test XMLs are in
`captures/runtime/notice-layout-replay/`. Gradle was stopped afterward.
The first compile caught a positional debug-host call displaced by the new
optional parameter; placing that parameter last preserved existing call sites.

Two new Android cases are queued for the combined notice integration milestone:

1. Archive regular/compact/scroll selection, a visible final row, announcement
   badge and no duplicate accessible feature rows.
2. Debug replay navigation/dismissal with real update markers and unseen pages
   unchanged.

Also rerun the existing short/long/back sheet sizing, archive restoration,
appearance/owner retirement and large-text/landscape cases; inspect screenshots
and TalkBack traversal. Test configured-feed cold start/offline recovery alongside
this batch. Authenticated replay web loading and physical Pixel acceptance remain
open. No new APK, emulator, Pixel run or signed release is claimed by this feature
batch. Debug suppression and transitions were subsequently added below.
Authenticated feed/release acceptance remains in `REMAINING_WORK.md`.

## Launch transitions and debug suppression — 2026-10-06

The follow-up adds 300 ms sheet-height and Continue transitions using Compose's
animation clock and Android animator-duration scale (zero disables animation).
Native archive detail fitting and manual pager gestures remain independent.

Debug MainActivity launches can supply Boolean Intent extra
`CMUX_UITEST_SUPPRESS_WHATS_NEW=true`. The composition root gates it with
`BuildConfig.DEBUG`; the host gates it again and suppresses only automatic launch
presentation. Archive/replay remain available and no marker is written. The
catalog does not inspect Intents or persist this option. This matches the scope
of the iOS `UITestConfig.suppressWhatsNewLaunch` input at the scoped source pin.
A fresh Activity launch without the extra uses the normal presentation policy.

The combined feed/UI integration also exercises the default public endpoint
through the real `NativeWhatsNewViewModel` using an isolated app-private ledger.
A second ViewModel loads that ledger with a deliberately failing fetch callback:
this checks lifecycle/persistence recovery, not an OS network transition or
physical process death. The production factory always supplies the default
anonymous feed; callback injection exists to exercise the error path accurately.

## Combined feed/UI integration — 2026-10-06

**All 11 distinct runtime cases have passing evidence** on the existing API 37 /
16 KB arm64 emulator, across the initial and focused follow-up runs:

| Run | Result | Duration |
| --- | --- | --- |
| Initial native/feed/web batch | 9 passed, 2 failed | 92.309 s |
| Corrected native/feed batch | 7 passed, 1 failed | 52.734 s |
| Final short/long/back, launch and replay | 3 passed | 27.920 s |
| 150% text with animator scale zero | 2 passed | 13.029 s |
| Landscape launch/pairing navigation | 1 passed | 5.700 s |

The failures exposed two real issues:

- Retiring the feed on Main could synchronously write TLS `close_notify` while
  evicting idle connections. Cleanup now runs on the client's owned worker and
  shuts that worker down afterward. The real public-feed load, clear, new
  ViewModel and failing-fetch recovery case now passes; stored bytes and the
  unacknowledged marker survive unchanged.
- Clearing natural-height measurements on a window/inset height change could
  leave the first short page at full height because unchanged content did not
  emit another size callback. Compact natural height no longer depends on that
  cache key, and its unbounded measurement wrapper is explicit. The sizing case
  now waits for the fitted result and verifies expansion plus return to the
  original height. Its final screenshot shows the short sheet correctly fitted.

The archive component fixture also now has a themed Surface; its bounded test
viewport is not a full-app screenshot. Native fitting checks cover all three
layout tiers and nonduplicated accessible rows. Replay and suppression preserve
real markers; archive recreation and owner retirement pass. Local synthetic web
checks verify visible green content, preload-before-acknowledgement, retry and
recovery after an intentionally injected Gecko content-process crash. That
expected crash is retained in the initial logs; the final run has no crash/ANR
markers.

**22 distinct JVM tests passed**, with the six feed cases repeated successfully
following the cleanup fix. Initial debug/test assembly took 74 s; the corrective
app builds took 24 s and 23 s, and the test-only rebuild took 4 s. DEX measurement:
3,511 methods, largest 13,156 code units, none above the 16,383 exclusive limit.
Final debug APK SHA-256:
`fac558f50082836cd15d6740e126cb59590bd3eeb2c9278eeaaf3cf29182f291`.

Logs, per-case results, source receipts, APK hashes and screenshots are under
`captures/runtime/notice-feed-ui-milestone/`; `verification.json` records which
screens were inspected. Font scale, rotation and animator settings were restored;
Gradle and the sole emulator were stopped/reaped. No additional AVD was created.

The live feed result proves actual Android HTTP initialization and private-ledger
recovery through a fresh ViewModel. It does not prove OS-level offline restart,
process death, real account cookie exchange, physical Pixel behavior or a signed
upgrade. Those acceptance gates remain open; no signed release was promoted.
