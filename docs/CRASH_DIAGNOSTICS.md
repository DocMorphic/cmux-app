# Local crash diagnostics

## Java/Kotlin exceptions

Application startup now wraps the existing default uncaught-exception handler in
both production processes. It publishes one local record and then delegates the
original thread and throwable to the previous handler, even if capture fails.
It does not replace native signal handlers, restart the app, upload a report, or
wait for the ordinary diagnostic queue or its file locks. This follows the
exception-stack intent of upstream
[MobileDebugLogCrashCapture.swift](https://github.com/manaflow-ai/cmux/blob/0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc/Packages/iOS/CmuxMobileDiagnostics/Sources/CmuxMobileDiagnostics/MobileDebugLogCrashCapture.swift).
The upstream logger is DEBUG-only; Android includes these local records in both
build variants so exported release diagnostics can explain Java/Kotlin failures.

Records contain process role, PID, numeric app version, wall/boot/monotonic time,
exception class and stack class/method/line metadata. Only known app/runtime/library
namespaces and bounded ASCII symbols are accepted; other symbols are omitted.
Exception messages, source filenames/paths, thread names, suppressed exception
text, terminal data and arbitrary `toString()` output are not copied. A record
contains at most four causes, 64 total frames and 32 KiB. Cause cycles and
truncation are explicitly handled. Local development APKs retain their ordinary
version code 2; this does not invent a Git revision for them.

Crash publication writes a private partial file, syncs and atomically renames it.
The reader ignores incomplete records and validates the entire binary schema
before producing text. Storage is under `noBackupFilesDir`, outside FileProvider
sharing and Android backup. Startup/export maintenance keeps the newest 32
complete records and removes abandoned partials after 24 hours; a fresh partial
is never exported or deleted as if it were an abandoned writer. This is retention
on maintenance, not a promise that repeated deaths before maintenance cannot
leave additional files temporarily.

The existing ZIP still has exactly two members. Local stack records appear in
`cmux-diagnostics/app-events.log`, alongside the independent Android exit-history
summaries. One crash may have one stack record and one OS summary. Repeat exports
do not duplicate a stored record. Clear Logs persists its shared boot/monotonic
cutoff before pruning complete crash records. A pre-clear write published late is
rejected on the next read; a post-cutoff crash remains eligible. Reboot ordering
prevents an old-boot record from reappearing after clearing in a newer boot.

## Platform and evidence boundaries

[Android's default handler](https://developer.android.com/reference/java/lang/Thread.UncaughtExceptionHandler)
participates only when a thread or its thread group has not provided a replacement.
A later SDK handler must retain this handler to preserve capture. No handler is
installed if there is no previous handler to delegate to. Capture is best effort:
VM/resource failure, unavailable storage, a native signal or forced process kill
may prevent a local record. File I/O is synchronous at the crash boundary; it does
not have a hard real-time deadline. Android's existing handler remains responsible
for system reporting, crash dialogs and process termination.

Existing Android 11+ reason/PID/timestamp summaries still recover through
`ApplicationExitInfo`. Native/ANR **stack** recovery remains unimplemented.
[Android documents native tombstone streams from API 31](https://developer.android.com/ndk/guides/debug),
so this is remaining implementation work on supported devices, not an asserted
platform impossibility. No raw tombstone, memory dump or trace stream is exported.

## Verification

Eight new JVM checks and fifteen existing history/storage checks passed, with
zero failures/skips. They cover codec validation, bounded cyclic/deep causes,
message/path omission, handler delegation after logging failure, publication while
the ordinary export lock is held, reopen/export, retention, malformed/partial
files, and late writes across clear/reboot cutoffs. Debug/test APK assembly and
release Kotlin compilation passed in 1m 6s.

The emulator suite exercises a real uncaught exception in the disposable browser
process, checks that Android retained its crash report, and verifies the exported
class/method/cause metadata while deliberately private fixture messages and thread
name are absent. It also covers repeat export and clearing, existing Java/native
OS history, and the Settings export/clear controls. The first combined run passed
five checks, but its third process launch timed out: the preceding crash dialog
kept a dying fixture alive after its history record appeared. Test cleanup now
checks the known fixture PID's actual process name and waits for termination;
production crash handling was not changed to bypass the dialog.

The corrected suite passed **OK (6 tests), 34.262 seconds**, with zero failures or
skips on API 37 / 16 KB. The test-only rebuild passed in 27s. Production crash
handling was unchanged after the initial combined run. Tested debug SHA-256:
`4d62708bb1be14a5702e68f7e0cb8ef7fbcad25a32815d66d123f71809eeb7ec`;
test SHA-256: `ba6a50bb59d29d4bd39b38d71bc6f49633388ee30ae0e79770fa14af28fab1eb`.

Evidence is retained
in ignored `captures/runtime/crash-stacks/`. No physical phone or user process is
crashed by these tests. Signed build 441 predates this addition. Native stack
recovery, live ANR acceptance and physical Pixel acceptance remain open.
