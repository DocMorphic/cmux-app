# What's New source audit — 2026-10-03

Reference: `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`. Android currently has no
What's New center, archive or launch presentation in `NativeScreen` or
`NativeSettingsLayout`. The existing upstream watcher and APK release automation
in `UPDATES.md` serve a different purpose. This audit does not claim implementation
or advance the global parity pin.

## Reviewed sources

Under `Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/`:

- `MobileWhatsNewCenter.swift`
- `MobileWhatsNewRemote.swift`
- `MobileWhatsNewCatalog.swift`
- `MobileWhatsNewSheet.swift`
- `MobileWhatsNewListView.swift`
- `MobileWhatsNewWebPageLoad.swift`
- `MobileWhatsNewWebView.swift`
- `MobileSettingsView.swift` (What's New integration)
- `WorkspaceShellView.swift` (refresh, presentation and preload ownership)

Channel policy:
`Packages/iOS/CmuxMobileShellModel/Sources/CmuxMobileShellModel/MobileWhatsNewChannelPolicy.swift`.

## Required behavior

| Area | Observed contract |
| --- | --- |
| Compiled catalog | Permanent IDs; newest entries prepend. Full catalog order, not currently visible order, determines acknowledgement. Page bodies support feature rows, Mac pairing help and web content. List identities namespace binary entries and announcements. |
| Channels | Missing channel lists permit dev/beta/internal only. prod/demo require explicit inclusion. An explicit empty list hides all channels; unknown tokens do not match. Remote per-entry channel declarations replace the compiled declaration. |
| Version targeting | Inclusive dotted-numeric version bounds; missing components compare as zero. Binary bounds may be optional; announcements require min/max. One malformed announcement does not discard the entire list. |
| Visibility | `/api/whats-new` returns authoritative `visibleEntryIds`, optional `entryChannels`, and announcements. Cache is scoped to API scheme/host/port. Failure keeps the last good cache; never-fetched devices fall back to channel/version-eligible compiled entries. |
| Retraction and rollout | Explicit empty visibility hides all binary pages. A nonempty wholly unrecognized list falls back to current compiled entries. When recognized IDs exist, required pairing IDs survive omission, still subject to channel/version bounds. |
| Announcement content | Announcements precede binary entries. If their native ID already has a visible binary page, drop the duplicate. A hidden native entry must not be resurrected through an announcement: only its own allowlisted web fallback or inline rows render. Localized content changes presentation, never identity/targeting. |
| Initial refresh | Launch presentation waits until an initial refresh attempt completes. Offline failure allows cached native pages. Cancellation does not mark the initial attempt complete. The shell owns refresh and paired-computer loading tasks. |
| Launch gate | The actual `WorkspaceShellView.presentWhatsNewIfNeeded` does **not** require a nonempty computer list: pairing notices must appear before discovery. Some sheet comments still describe an older paired-computer gate; do not implement that stale comment. |
| Web pages | Skip web pages from the launch sheet until a fetch succeeds this launch. Preload all staged web pages concurrently with a ten-second whole-load deadline. Failed/late pages are dropped without acknowledgement and can return later. |
| Web isolation | Nonpersistent web data store, optional account-to-web cookie exchange, theme set before preload. Anonymous loading is allowed if no session is available. Shared cmux/API-host allowlist applies to initial and later navigation; HTTPS required except loopback development HTTP. Invalid main-frame initial navigation fails the load, while later rejected links preserve the already rendered page. |
| Presentation snapshot | After preload, recheck that each staged page remains unseen and visible. Freeze the actual presented page array so a remote refresh cannot mutate an open sheet. Candidate identity uses namespaced page IDs rather than count. |
| Acknowledgement | Acknowledge on actual sheet appearance, never while staging: another restored sheet may prevent presentation. All shown pages become acknowledged even on early dismissal. Binary marker advances but never retreats; unknown marker stays quiet on downgrade/catalog replacement. Announcements use an ID set pruned only against a successful authoritative list. |
| Archive | Settings row exists only when eligible content exists. All archive pages remain accessible after launch acknowledgement, newest announcements first. Announcements have their own badge. |
| UI | Swipe pages, Continue advances then dismisses. Native sheets fit content; web/large-accessibility text uses full height. Keep scrolling and reduced-motion behavior. DEBUG-only replay/suppression must not alter real markers or ship as a release bypass. |

## Additional acceptance evidence in upstream tests

The scoped test inventories `MobileWhatsNewChannelGateTests`,
`MobileWhatsNewReplayTests` and `MobileWhatsNewWebPageLoadTests` explicitly cover
never-fetched official versus team channels, failed versus canceled initial
refresh, localization fallback, exact version targeting, stale versus empty
visibility lists, remote channel overrides, acknowledgement/archive retention,
namespaced replay ranges and replay without acknowledgement. The web fixture
checks rejected initial hosts, a deadline during stalled cookie exchange and
immediate results for late outcome waiters. These are upstream test expectations,
not Android test results.

The archive web wrapper also has an in-place **20-second** deadline, distinct
from the launch preload's ten seconds. Try Again creates a fresh load and session
exchange; simply returning to a retained page does not reload it. The failure
state says the page needs internet and exposes Try Again. Live appearance changes
update the existing page's resolved theme.

## Android continuation

Implement a typed center and persistence first, retaining cache failure/empty/stale
semantics, permanent IDs, channel/version targeting and monotonic acknowledgement.
Then add archive/detail UI and an owner-scoped launch presentation, including web
preload/isolation rather than showing a permanently loading web page. Use existing
Mac pairing guide assets and current compatibility policy for native help pages.
Keep first-run onboarding, account recovery, incoming routes and restored sheets
from acknowledging a page that was never visible.

Android has its own version `0.2.0` and development APK release cadence. Do not
copy iOS's `1.0.6` release claims or route Android release IDs into its production
visibility endpoint without an explicit Android schema. Define Android catalog
IDs and channel/version metadata alongside the repository's release configuration;
a reviewed Android notice feed must use the same content/cache contract. Merely
adding a GitHub Releases link would leave the observed archive, visibility,
presentation and acknowledgement behavior unimplemented.

Required checks: table-driven cache/retraction/channel/version cases; malformed
announcement isolation; namespace collisions; skipped-version and downgrade
markers; cancellation without false refresh completion; persistence failures;
late remote retraction during preload; timeout and rejected redirects; account
replacement during cookie exchange; restored competing modal without accidental
acknowledgement; portrait/landscape/large-text navigation; actual signed-upgrade
presentation and offline archive on Pixel. Mocked checks do not establish a live
Android announcement service.
