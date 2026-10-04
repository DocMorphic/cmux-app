# Workspace row presentation

Scoped iOS reference: `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`,
`WorkspaceRow.swift`, `WorkspaceUnreadDot.swift`,
`MobileWorkspacePreview+Display.swift`, `MobileMacConnectionStatus+Display.swift`
and `MobileSyncWorkspaceListResponse.swift`.

## Row content

The scoped iOS row consists of the retained unread gutter, workspace color rail
and text column. It does not render a Mac avatar or Mac-name caption inside the
row. Android now uses that structure; the owning Mac remains in the row's
accessibility context and the computer-selection/status surfaces. The earlier
customization checkpoint's avatar-plus-rail layout is superseded.

A pinned workspace shows the existing filled pin asset at the title baseline.
The pin is decorative because the row already exposes its pinned accessibility
state. The activity/status label now shares the title line. Headline/subheadline
sizes are 17sp/15sp with explicit 22sp/20sp line heights, text blocks have
4dp spacing, and row vertical padding is 8dp. This avoids inheriting Material
body text's taller line height when overriding only font size.
Descriptions reserve two lines; the Preview Lines and Wrap Workspace Titles
preferences still control their respective content. The 3dp color rail, 5dp
vertical inset, 10dp text gap and retained unread gutter align read/unread rows.

## Static activity and connection status

The owning feed's state determines the trailing label: `Reconnecting` or
`Disconnected` when the link is not connected. A healthy connection adds no
connection-status label. This describes the link rather than claiming the Mac
itself is offline.

For connected rows, `last_activity_at` takes precedence over legacy `preview_at`.
The parser now retains that fallback field. No real timestamp means no trailing
label. Epoch sentinels at or before one second, nonfinite values and out-of-range
values do not produce a date. An explicitly present sentinel does not fall back
to the preview time, matching the scoped iOS derivation.

Activity on the current local calendar day shows a time; older activity shows
numeric month/day without a year. Android's locale pattern generator formats
these values. The day comparison uses the local timezone, including dates near
midnight. Rows do not run a live relative-time timer.

## Verification — 2026-10-04

**17 focused JVM tests pass**: four row timestamp/status policies and 13 existing
customization checks. **Four Android checks pass in 56.636s**: row pin/status/rail
alignment, US/German date and time formatting, display preference wrapping and
reserved line counts, and the real Activity/local-peer offline customization
recovery flow. After inspecting screenshots, explicit line heights were added;
the two affected layout/preference checks passed again in **17.153s**. The final
form and wrapped-row screenshots were inspected, crash buffers were empty, and
the sole existing AVD was stopped/reaped.

The first test compilation used an unavailable DpRect width accessor, and the
first runtime layout assertion omitted density rounding. Both diagnostics are
retained. The corrected test checks the exact expected physical-pixel rail width.
The final APK build passed in 56s. Main debug SHA-256:
`c525ca1bc1cd803ab1d025e9ea2e6b40a3731409d12605171c8c2cccd1a53fc1`.
Local receipt: `captures/runtime/workspace-row-presentation/verification.json`.
No Pixel was connected; signed build 517 is unchanged.

## Remaining row parity

The visible Android action button is still present. Matching iOS context/swipe
menus requires coordinating them with the existing long-press drag-reorder
surface and TalkBack actions. The iOS changes-summary chip and its refresh policy
remain to be implemented in the list; the full changes viewer already has its
own path. Selected/sidebar styling, wider-screen layouts, dynamic type/TalkBack,
production multi-Mac behavior and physical Pixel comparison still need checks.
These are remaining work, not unavoidable Android differences.

## Changes-chip implementation reference — 2026-10-04

The chip is still **unimplemented in the Android row**. At the scoped revision
above, the source path is `MobileShellComposite+WorkspaceChanges.swift`, supported
by `WorkspaceChangesSummaryFetchPolicy`, `WorkspaceChangesSummaryRefreshSchedulePolicy`,
`WorkspaceChangesSummaryRefreshScope` and the pruning extension. The list requests
`mobile.workspace.changes.summary` with `workspace_ids` (up to 64 unique IDs);
`force: true` is sent only for a forced pass. Successful results are reused for
15 seconds. Scheduling debounces real events/list changes by 250 ms, coalesces
full snapshots or changed workspace IDs, ignores group-only changes, and permits
one fetch at a time with an accumulated trailing pass. Trailing expiry waits at
least five seconds and is armed only while real events are recent; it must not
become continuous Git polling on idle Macs.

Connection/capability admission precedes fetching. Foreground workspace membership
prunes cached chips and pending requests; a connection reset cancels jobs and
clears their state. Failed requests retain the last published chip. Successful
summaries remove chips for non-repositories or zero changed files. The decoder
requires typed workspace identity and `is_repo`, drops malformed entries separately,
and tolerates missing or malformed count fields. The Android adaptation should
also restrict response IDs to the requested owning-Mac batch and reject late
responses after owner/client retirement.

`WorkspaceRow.swift` puts the chip beside the preview with an eight-point minimum
gap; it is visible only with a positive changed-file count. A tappable chip has a
44-point minimum target and opens Changes without opening the terminal row.
`WorkspaceChangesChipLabel.swift` uses caption2 semibold monospaced digits, a
secondary capsule at 12% opacity, horizontal/vertical padding 6/3 and a three-point
count gap. `WorkspaceChangesChipTextPolicy` uses green additions and red deletions;
binary-only changes (both line counts zero) show singular/plural file count text.
The accessibility label includes files, additions and deletions.

Android currently has the full Changes viewer and owner-aware feed coordinator,
but no list-summary fetch/cache. Integrate a separate per-owner summary state
with the coordinator's workspace refresh/event and lifecycle boundaries. Reusing
an arbitrarily old full-viewer snapshot would not implement this source policy.
Verification must include batching/expiry/coalescing, owner retirement and late
responses, multiple Macs sharing workspace IDs, binary/zero counts, chip-versus-row
click behavior and accessible labels, before live Mac/Pixel acceptance.
