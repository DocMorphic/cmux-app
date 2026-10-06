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
  Height caches now also reset when the available window height changes.
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
batch. Full notice parity still includes debug launch suppression, animation and
the remaining authenticated feed/release acceptance in `REMAINING_WORK.md`.
