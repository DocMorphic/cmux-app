# Private notice extension recovery — 2026-10-04

This follows [launch preloading](NOTICE_LAUNCH.md) and the
[production renderer](NOTICE_RENDERER.md). It replaces the old permanent
process-level failure state after an extension disconnect.

## Recovery contract

A connection loss invalidates its generation, fails pending native commands and
closes all pages attached to that generation. A late exchange/response cannot
resume those pages. Retry constructs a fresh renderer and session, preserving the
existing archive's explicit Retry behavior. It does not restore an old document's
cookies or replay a retired account exchange.

The engine keeps a non-secret cleanup receipt in native memory after each
successful acquisition of an exact owned private blank. It contains the captured
private origin attributes and the native context name, not cookie values or
account credentials. Closing a page attempts normal lease cleanup; a disconnect
retains any unconfirmed receipt for recovery. No receipt is persisted to disk.

The next preparation restarts only the bundled extension by cycling its private
access permission, then installs a new generation-scoped native delegate. The
single GeckoRuntime stays alive. Before admitting a new page, the extension
clears each retired receipt's private cookie context and native code requests
public per-context web-storage cleanup. Gecko's public cleanup alone is known to
leave private cookies behind; see the earlier runtime evidence.

The recovery operation validates a nonempty, bounded context plus explicit
privateBrowsingId=1 and a nonnegative integer userContextId. The bundled background
queries the tab inventory itself; retirement rejects a matching live context,
even if it has a different tab ID. Cookie clearing omits the host and partition
key so that every partition in that exact private context is covered. It cannot
be requested by page JavaScript. Only receipts obtained over the checked native
background port enter the native cleanup map.

A failed restart or cleanup retains the receipts and fails the new load. A later
Retry may try again; no default-profile/global clear or whole-runtime restart is
used. Deadlines remain bounded by the full page-load owner. A canceled presentation
does not deliberately invalidate a healthy shared connection. `awaitRetired()`
reports the immediate retirement attempt, so it can return false after connection
loss even though a later recovery completes that retained cleanup.

## Verification

Evidence is retained under ignored `captures/runtime/notice-recovery/`. The new
Node cases recreate the experiment API (discarding its lease map) and verify
receipt cleanup, live-context rejection under a changed tab ID, idempotent
retirement, partition-pattern matching, and preservation of other/private-mode
contexts. These are modeled storage checks, not browser partitioned-state proof.

The Android case disables private access on the real bundled extension while one
page is loaded and another account exchange deliberately ignores cancellation.
It verifies old pages close, Retry uses the same runtime with a fresh session,
retired cookies/storage disappear from the exact old context, and a late exchange
cannot navigate. Exact results are recorded below after execution.

## Remaining scope

Recovery is initiated by a new page preparation (including archive Retry); it
does not silently reload already-visible content or acknowledge failed launch
pages. Whole-Gecko-process crash recovery, partitioned browser storage runtime,
real HTTPS/native-account acceptance and physical Pixel acceptance remain
separate requirements. No Android notice feed or new signed milestone is implied.

### Retained integration failure

The first Android run passed account cancellation and the existing private-storage
case, but recovery failed before exchange; subsequent archive/launch flows also
stalled (`runtime.txt`, 2 passes / 3 failures, 93.742 s). The old native delegate
was still registered during permission-driven extension restart and could reject
the new background connection as an old generation. Invalidation now clears that
delegate before restarting. Fixed-category setup diagnostics distinguish install,
permission, port and cleanup failures without recording URLs or credentials.

### Final verified checkpoint

- `build-3.log`: final debug/test build and **52 focused JVM tests passed** in
  31 s, with zero skips/failures. `node-tests.txt`: **11 Node tests passed**
  (seven extension cases and four helper cases).
- `runtime-2.txt`: **all five Android tests passed in 76.339 s** on the existing
  API 37 / 16 KB arm64 AVD, including forced extension loss, existing cookie and
  account-lifetime cases, archive Retry and preloaded launch rendering. The two UI
  flows include actual rendered-pixel checks. Clearing the old native delegate
  resolved the failed recovery and the following flow failures in the same process.
- `device/report.json`: the old context initially sent its synthetic HttpOnly
  cookie. A fresh context sent only its new cookie and had empty storage; reopening
  the exact retired context sent no cookie and had empty localStorage. The fresh
  context retained its cookie and storage afterward. The pending noncooperative
  exchange never navigated. These are local HTTP fixtures, not real account tests.
- The final debug package's pinned engine, license and extension checks pass.
  The earlier unsigned release build/package check in `build-2.log` predates the
  delegate fix; it is **not** final-source release verification. Final release/ART
  verification remains a batched milestone gate. No new signed APK was published.
- The existing emulator was stopped and reaped. No additional AVD was created;
  the Pixel was absent from ADB and its data was not touched. Build 494 remains the
  last verified signed artifact. Current debug/test hashes are in `packages.json`.
