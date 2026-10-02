# Phone-to-Mac notification dismissal

## Behavior (2026-10-02)

Android system-alert swipes now enqueue `notification.dismiss` for the exact
saved Mac that posted the alert. This matches the audited iOS
[`PendingNotificationDismissQueue`](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileShell/Sources/CmuxMobileShell/PendingNotificationDismissQueue.swift)
and [dismiss synchronization](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileShell/Sources/CmuxMobileShell/MobileShellComposite+NotificationDismissSync.swift):
store before sending and remove only after the host confirms the RPC. This is
separate from marking notification feed entries read or unread.

The alert uses Android's
[`setDeleteIntent`](https://developer.android.com/reference/android/app/Notification.Builder#setDeleteIntent(android.app.PendingIntent))
with an immutable, explicit PendingIntent targeting a non-exported receiver.
Only the random route UUID crosses the Intent boundary. The encrypted route holds
its posting login; an old action cannot adopt the currently signed-in account.
Staging the same notification after a login change generates a new route UUID.

The receiver persists the action synchronously and does not open a network
connection or launch a foreground service. The encrypted account store owns a
bounded, deduplicated 128-entry queue containing only login incarnation, pairing
origin and opaque notification ID. Credential mutations prune retired accounts,
forgotten Macs and ambiguous aliases atomically; authorized pairing repairs move
entries to the canonical origin. The queue is erased with account data.

While the existing Activity or notification service owns `NativeAppConnections`,
a worker checks the queue every five seconds. It uses the normal saved-Mac
connector and independent scoped leases, with at most four Mac groups in flight
and a ten-second limit per group. Before sending it verifies host device/instance
and current login, saved pairing and team admission; it rechecks admission before
acknowledging. A failed, revoked or uncertain attempt leaves its rows queued.
One unavailable Mac does not prevent another from receiving its dismissal.
Once the app/service is running again, persisted work is retried. This does not
promise execution while Android has suspended or stopped the process.

## Evidence

**16 JVM checks passed, no skips:** six dismissal tests, nine ledger regressions
and the notification-feed runtime test. Coverage includes:

- Duplicate IDs on different Macs, capacity, persistence reconstruction and
  acknowledgement removing only the confirmed owner's entries.
- Login changes, forgotten/re-added records, canonical-origin repair, ambiguous
  aliases and fresh action IDs after login changes.
- A real loopback mobile-RPC peer receives `mobile.host.status`, then exactly
  `notification.dismiss` with the intended IDs and client ID. An offline sibling
  remains queued while the successful owner's row is acknowledged.
- Wrong host, admission lost before or after sending, unknown send outcome and
  unadmitted account: entries remain and leases close; no unadmitted dismiss RPC
  is sent.

**Android: OK (6 tests), 34.3 seconds, no skips**, API 37 / 16 KB. A real gesture
in the system notification shade removed the generated cmux banner and invoked
the receiver. A newly constructed credential store read the encrypted queued
entry. Changing the login pruned it; invoking the old immutable PendingIntent did
not enqueue it again. Five existing delivery/service tests also passed, including
per-Mac alert/read cleanup, stale-feed rejection, duplicate pairing repair,
installation identity checks and opt-out. Before/after screenshots were inspected;
the receipt records `pending=1; alerts=0` immediately after the swipe.

The initial Android gesture moved only within the title label's narrow bounds and
failed to dismiss the card. Swiping across the card/display resolved it. Two JVM
fixture mistakes (missing ownership metadata on an alias and an unframed fake RPC
reply) were corrected; original failed output/XML remains in ignored captures.
No production admission checks were weakened to make these checks pass.

Both APK builds passed. Five native libraries passed LOAD/RELRO alignment and both
APKs passed 16 KB ZIP alignment. Final APK SHA-256:

- Debug: `cbf15c816bbf5b2f049a65d249b2f7f9e9f14411146c57f661f6ee96ab92db4e`
- Test: `a4d8273ac57af44819e432465bafa73a2bba21896f1d2085d25868e8999b8417`

Evidence: ignored `captures/runtime/notification-dismiss-final-{build.txt,jvm,android}`.
The Android suite is `io.github.docmorphic.cmuxapp.NativeNotificationDeliveryTest`;
JVM suites are `NativeNotificationDismissTest`, `NativeNotificationLedgerTest`
and `NativeNotificationFeedTest`.

## Remaining acceptance

The Android system interaction and the RPC sender were verified with isolated
fixtures, not together against the user's actual Mac. Real reconnect, reboot,
process-kill recovery, battery behavior and physical Pixel acceptance remain open.
Existing read-feed cleanup does not establish all Mac-originated dismiss-event
behavior. Inline reply, server push and its registration/transport lifecycle are
separate remaining work; see [Android push delivery](PUSH_DELIVERY.md). No signed
release changed. The one existing emulator was stopped after verification.
