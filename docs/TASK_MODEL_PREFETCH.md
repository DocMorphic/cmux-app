# Task model prefetch

Scoped iOS audit: `f4b1509054949eaad5d695569ad443c4c18ed68d`, particularly
`MobileShellComposite+TaskModelPrefetch.swift`, `MobileTaskModelRefreshRequest.swift`,
`MobileTaskModelPrefetchCatalog.swift` and the task-model composer selection flow.
This audit does not advance the global parity pin.

## Implemented

- Foreground warming for Claude, Codex and OpenCode on each visible paired Mac,
  keyed by pairing/provider and the actual RPC wire identity. Connection topology
  changes trigger reconciliation; Feed payload updates do not trigger it.
- Four concurrent warming slots, unchanged-target retention, and completed/failed
  attempt suppression for the current desired wave. Retired work holds its slot
  until its producer exits, including cooperative cancellation cleanup.
- One full backend catalog download shared across the wave, retained for five
  minutes including failures. Parsing runs away from the UI thread. Requests
  contain no pairing token, account credentials or task prompt.
- Shared host refreshes between warming and an open composer. Dismissing one
  consumer preserves other consumers; the last consumer cancels and joins the
  producer. The composer still revalidates rather than trusting a warm TTL.
- Host results are bound to the current connection; account retirement and
  connection replacement fence late publications. Optional model discovery uses
  the verified Feed connection and does not classify catalog/provider errors as
  a broken computer connection. Leaving the foreground cancels warming.

## Verification — 2026-10-10

Final focused run: **26 JVM cases passed**, zero failures/errors/skips:
12 `TaskModelPrefetchTest`, 12 `TaskModelsTest`, and two
`NativeFeedCoordinatorTest.taskModelDiscovery*` cases. Main and Android test Kotlin
compiled successfully in the same **14-second** run.

The tests cover the four-slot bound across 18 Mac/provider probes, a single shared
catalog including failure, connection replacement, cancellation and rapid resume,
composer joining, the cache expiry boundary, stale callbacks and real framed TCP
RPC borrowing/revocation. They use local fixtures, not the user's account or Mac.

Earlier attempts caught a missing import, an escaping catalog exception and a
syntax error in the topology reconciliation edit. All were corrected before the
final run. Original logs and final XML are retained under ignored
`captures/runtime/task-model-prefetch/`.

No Android runtime, physical Pixel/Mac, production catalog or signed-APK acceptance
was performed for this batch. Multi-Mac discovery, actual composer behavior and
foreground/reconnect acceptance remain open. Signed milestone 641 is unchanged.
