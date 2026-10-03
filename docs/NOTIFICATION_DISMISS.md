# Notification dismissal synchronization

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
Mac-originated dismissal/reconciliation now has the separate fixture checkpoint below.
Inline reply, server push and its registration/transport lifecycle are separate remaining work; see [Android push delivery](PUSH_DELIVERY.md). No signed
release changed. The one existing emulator was stopped after verification.

## Mac-to-Android live dismissal and reconnect catch-up (2026-10-02)

The notification service now subscribes to `notification.dismissed` as well as
feed invalidations. Live `ids` clear only this admitted Mac's currently posted
alerts. On every successful subscription, and every 30 seconds while the service
is connected, it asks `notification.reconcile` about actual delivered identifiers.
IDs are split into batches of at most 256, matching the host's scan limit; only
`handled_ids` from that request's batch may clear alerts. An absent feed row is
never treated as a dismissal: the host can truncate feed history.

Primary upstream contract at candidate audit `204a11dfcc76280205e50406ab94270a1c152155`:

- [iOS dismissal/reconcile flow](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileShell/Sources/CmuxMobileShell/MobileShellComposite+NotificationDismissSync.swift)
- [Live event decoding](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileRPC/Sources/CmuxMobileRPC/MobileNotificationDismissedEvent.swift)
- [Host reconcile and dismissal semantics](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Sources/TerminalController+MobileNotificationSync.swift)

The worker captures its login before connecting, verifies the host, and fences
callbacks against cancellation, login, pairing, team admission and opt-out.
Delivery rechecks eligibility inside the credential storage transaction. Routes
must match the posting login and Mac; a response cannot clear a sibling Mac's
same-ID banner. Actual delivered-ID collection is checked again before returning.
Programmatic cancellation does not enqueue the user-swipe RPC.

Handled identifiers are remembered durably so a stale unread snapshot cannot
repost them. Events before the first feed are saved separately until the quiet
baseline is established; pairing repair merges them and forgetting a Mac prunes
them. This preserves quiet first connection even if the dismiss event wins the
initial snapshot race. History remains bounded to 4,096 identifiers per origin;
routes retain the existing 512-entry bound.

Reconcile has a ten-second budget for both possible batches. Unsupported RPCs,
malformed responses and transient failures leave ordinary feed delivery running;
parent cancellation still terminates the worker. No request is needed without
actual banners. The parser accepts only bounded string arrays, trims blanks and
deduplicates IDs; it does not stringify numbers or objects.

This adds banner synchronization while the notification service is active. It
does not implement iOS's absolute numeric app-icon badge, suspended/process-dead
push delivery or inline reply. Reconciliation does not wait for the independent
phone-to-Mac outbox worker: a late local dismissal remains queued, and subsequent
host events/reconciliation converge. Physical Mac/Pixel acceptance remains open.

### Verification of Mac-to-Android synchronization

**21 focused JVM tests passed, zero skips:** four sync tests, ten ledger cases,
six phone-dismiss regressions and the feed revision/disconnect test. A framed RPC
transport exercises the real mobile client and monitor twice, verifying live
subscription topics/events, exact reconcile params/client ID and renewed catch-up
on the second connection while feed delivery continues. Other cases cover two
256-ID batches, foreign returned IDs, malformed strings/arrays, unsupported
requests, cancellation and a pre-baseline event across persistence/alias repair.

**Android: OK (7 tests), 37.971 seconds**, API 37, 16,384-byte pages, zero skips.
The new test covers same-ID alerts on two Macs, an event before the first feed,
read-state regression, lost admission while entering the transaction, durable
suppression of not-yet-posted IDs, account replacement and zero queued local
swipes after remote cancellation. Six delivery/service/swipe regressions also
passed. This is isolated component/transport coverage, not a live Pixel/Mac run.

The first Android run counted three entries where two alerts were expected:
Android had added its `Aggregate_AlertingSection` group summary. The final receipt
shows two owned alerts, zero pending dismissals and that third system summary
(flags 1808). Tests now count individual alerts, and orphan cleanup leaves group
summaries to Android. The original failed run/logs are retained. This rerun also
includes the transaction admission recheck and its rejection assertion.

Both APK builds passed; all five native libraries passed LOAD/RELRO alignment and
both APKs passed 16 KB ZIP alignment. Final SHA-256:

- Debug: `bbf46fd7fb3166f365448a4eb0c7ca49a554e7dec2b546b9b5754056da95886a`
- Test: `103c919380d8075cae4f4e14a7300ce85cbe0b4f78aaeab75171504d057360cb`

Final evidence is in ignored `captures/runtime/notification-reconcile-summary-*`;
the initial run is `captures/runtime/notification-reconcile-android`. No signed
release or upstream parity pin changed. The same existing emulator was reused and
stopped after the run; no physical device was connected.


## Foreground catch-up on retained connections (2026-10-03)

The `0fc35d6` iOS connection-recovery path calls `scheduleNotificationReconcile`
even when retaining a healthy terminal subscription. Android's service already
reconciled after subscription and every 30 seconds, but the production screen
had no corresponding immediate foreground check. It now reconciles on entering
the foreground with a verified active client, including when that client and
terminal were retained across backgrounding. No connection is created by this
check and terminal subscriptions are not restarted for it.

The screen captures the login, paired Mac and client, then fences delivered-ID
collection and result application against current foreground state, cancellation,
login, saved pairing, team admission and exact connection identity. Backgrounding
or switching owners retires the attempt. Disk/banner operations run on IO. The
existing ten-second/two-batch reconciler tolerates unsupported methods or temporary
failures, clears only requested IDs returned as handled, and makes no request
without actual banners. Foreground return retries an interrupted check. It does
not enable the background service, infer dismissal from a truncated feed, send a
local-swipe mutation or change notification permissions.


The first runtime check exposed a lifecycle issue in the initial implementation:
keying a Compose effect only on a foreground Boolean did not start reconciliation
after the stopped activity resumed. The first test failed waiting for the RPC;
the run was stopped during the second test to correct this. Production now uses
`repeatOnLifecycle(STARTED)`, so stop cancels the attempt and start creates a fresh
one independently of Compose merging state updates while paused. Original logs
are retained alongside the rerun.

The lifecycle rerun passed the late-response case but exposed a test setup error
in the sibling-Mac case: the sibling had not been saved, so normal orphan pruning
removed its banner before reconciliation. The fixture now saves that Mac while
keeping its connection unadmitted. No production cleanup or assertions were
relaxed to accommodate the fixture.


Final verification: **14 JVM tests passed** (four sync and ten ledger tests,
zero failures/skips). Debug/test assembly and release Kotlin compilation passed;
the lifecycle-corrected build took 1m 4s and the final test-only rebuild took 22s.
The first run of the corrected fixture was killed by Android during instrumentation
startup before any tests ran: the OS reported startup ANR amid slow AndroidJUnitRunner
class verification and emulator boot activity. The same installed APKs were rerun
after startup settled, without changing production code or disabling ANR checks.

The final run passed **OK (2 tests), 33.1 seconds**, API 37 / 16 KB. It verifies
production NativeScreen lifecycle and framed RPC against actual OS banners:
no reconcile without banners; resume a retained terminal; exactly the posted
active-Mac IDs in the request; clear handled while retaining unhandled and
same-ID sibling banners; no extra terminal replay; no local dismissal outbox;
background service remains disabled; late response after stop leaves the banner;
and the next resume sends a fresh request and clears it.

Evidence: ignored `captures/runtime/foreground-notification/`, including original
failures, startup-ANR context and the final `instrumentation-settled.txt` receipt.
Debug SHA-256: `606a8a9ae81ec4a3a4e349f9d6dc0da1352d290e7c87e2b5b3a2648eafbb5bbe`.
Test SHA-256: `18d8efc6864f965f7b2dd2b42c617b1599dd7951a9ff5cc97483299eee09feb9`.
This is emulator/fixture evidence; physical Pixel/Mac foreground acceptance and
configured background push remain open. Signed build 441 predates this change.
