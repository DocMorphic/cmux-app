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
`ApplicationExitInfo`. Native stack recovery on API 31+ is described below.
ANR stack recovery and live ANR acceptance remain open. No raw tombstone,
memory dump or trace stream is exported.

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
crashed by these tests. Signed build 441 predates this addition. Live ANR acceptance and physical
Pixel acceptance remain open.


## Native crash stacks (Android 12+)

For the app and browser processes, recovery now reads available native-crash
[`ApplicationExitInfo` trace streams](https://developer.android.com/reference/android/app/ApplicationExitInfo#getTraceInputStream()).
A selective reader follows the [AOSP tombstone schema](https://android.googlesource.com/platform/system/core/+/refs/heads/main/debuggerd/proto/tombstone.proto)
(blob `9deeeec9e185f79747acf5fb6a7e71586eb7da16`). It matches the OS record's PID,
selects the crashing TID, and retains architecture plus at most 64 code frames:
relative PC, hexadecimal build ID, recognized module basename and bounded function
symbol. Unknown modules become `other` and lose their function symbol. Libraries
mapped inside APKs support AOSP's `!soname` form. Full paths, absolute PCs, stack
pointers, registers, memory, abort messages, thread names, command lines, open files
and log buffers are excluded. The raw stream is neither saved nor exported.

Each query considers at most 32 recent OS records and attempts at most four native
streams, newest first. Each parser is bounded to 8 MiB consumed, 256 thread entries,
200,000 fields and a two-second processing deadline checked between reads. This is
not a hard deadline on a blocked OS read. Missing, malformed or oversized traces
leave the reason summary available; native entries without frames explicitly say
`NATIVE_STACK unavailable`. API 30 retains summaries only; API 26–29 have no OS
history recovery through this API. System retention determines trace availability.

Filtered frames are stored with the existing atomic exit snapshot (at most 64
records), and survive later OS eviction of the raw trace. New code accepts the
previous summary-only encoding. Clear Logs rejects late imports at or before its
persisted wall-clock cutoff. Native frames appear in the existing app-events ZIP
member; the ZIP still has exactly two members. No native signal handler is replaced.


### Native verification

Nine new parser/history JVM tests and 23 existing Java/history/storage checks
passed: **32 tests, zero failures/errors/skips**. Coverage includes arbitrary
protobuf field order, crashing-thread selection, APK-mapped modules, excluded
private fields, unsigned relative PCs, truncated real-file fields, invalid wire
values, PID mismatch, frame/thread/input bounds, old summary decoding, enrichment,
OS trace eviction and late imports after clearing. Debug/test APK assembly and
release Kotlin compilation passed in 27s.

On the existing API 37 / 16 KB emulator, all three focused Android checks passed:
**OK (3 tests), 13.328 seconds**. These triggered Java and native failures in a
disposable browser process and verified native PID/TID, SIGABRT status, libc
frames and build IDs; Java handler delegation, filtered exports, repeated export
and clearing also passed. The pulled two-member ZIP contained the current native
failure's six frames, including libc, libbinder and libandroid_runtime metadata,
without `/data/` or `/apex/` paths. No raw tombstone was retained.

Evidence: ignored `captures/runtime/native-crash-stacks/`. Tested debug SHA-256:
`7631c62904bf1d1060c663a514e0cc0798a991448b5b182e6436df9cbe08270d`;
test SHA-256: `9e704aef822c2cf99fb9f2f37d15c7a5e639847eadfb7f15eec694c4c2e7b771`.
This verifies OS trace recovery on the emulator, not physical Pixel acceptance or
an actual Ghostty/Iroh fault. Signed build 441 predates both stack additions.
