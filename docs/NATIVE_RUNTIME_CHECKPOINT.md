# Native transport and keyboard runtime checkpoint — 2026-09-28

This is Android 17 **emulator fixture evidence**, not physical Pixel or authenticated
Mac acceptance. The emulator was shut down after the run. The Pixel was absent
from ADB; a reconnect request is pending. Published signed build 157 is unchanged.

## Failure found and fixed

The first combined UI run failed three terminal cases. Additional connection
failure diagnostics captured `LeftCompositionCancellationException` and no
fixture-server failures. A viewport/editor coroutine cancelled during an RPC
write was retiring the shared connection, so keyboard resizing or navigation
could leave typing and composer sends disconnected.

A deterministic JVM regression failed before the fix. `MobileRpcClient` now
checks cancellation before starting a frame, then completes that frame in an
independent IO cancellation context within its original deadline. Waiting writers
remain cancellable. A deadline watchdog closes the transport to interrupt legacy
Java socket writes, which coroutine cancellation cannot interrupt by itself.
Actual write failures still retire the connection; no uncertain input is replayed.

Three regressions cover cancellation during a started frame, cancellation while
waiting for the writer, and a truly blocking Java write released by its deadline.
The UI fixture now includes connection failure stacks, peer failures, method names
and screenshots on failure; it does not print request tokens or payloads.

## Verified runs

| Run | Result | Scope |
| --- | --- | --- |
| Full JVM suite | 441 passed, 70 suites | Includes the three cancellation/deadline regressions |
| Native module on emulator | 15 passed, 16.167 s | Identity/signing, key storage, QUIC, admission, event streams, malformed optional stream isolation, control replacement, keepalive and revocation |
| Rebuilt app on emulator | 16 passed, 156.236 s | Native terminal input/output, native artifact transfer, clipboard classification, stale IME rejection, Compose selection preservation, direct image ordering, resume/terminal switching, composer image send, task image upload, encrypted storage, picker, preview/removal and keyboard layout |

These are separate 15-test and 16-test instrumentation runs. The failed initial UI
run and diagnostic reruns remain retained; the final app run passed all 16 in one
process. No tests were disabled or assertions weakened to obtain the pass.

Inspected screenshots confirm the task prompt/dock fits above the keyboard and
terminal keyboard controls remain visible. The terminal capture occurs during
viewport resizing and shows a blank output frame; it does **not** establish terminal
rendering fidelity or absence of resize flicker. That needs a settled-frame check
and remains part of the full rendering work.

## Prepared APKs

| Artifact | SHA-256 |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | `a1c5ce51ff787333d6b634e07f23d9d66d15a4a30dd27f6471484b8d6aee386a` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `88bd07d025a7a77eaba8ca433fae4ebe156fa6127b04005eb39b45bf8d330b94` |
| `iroh/build/outputs/apk/androidTest/debug/iroh-debug-androidTest.apk` | `17a1ff7b5d8c7effcd8938d679f65fff306439601b720f541c7456ee52cfb02c` |

The main APK passes signature verification and 16 KiB page alignment. All 14
packaged viewer assets match their manifests, and the arm64 Iroh library matches
the pinned native build receipt. This local debug APK includes control push
recovery and both direct/composer keyboard-image changes. It has not been installed
on the physical Pixel or published as a new signed release.

Evidence on this Mac: `captures/runtime/6568068-emulator/fixed-receipt.json`,
`fixed-app-runtime.txt`, `iroh-instrumentation.txt`, `fixed-jvm/`, build logs and
screenshots. The directory is intentionally excluded from Git. The receipt records
the source base, exact RPC source hash, APK hashes and emulator fingerprint.

## Next acceptance work

1. Install the prepared debug/native test packages on the unlocked Pixel and run
   the native transport and keyboard checks before account enrollment. UI fixtures
   in `NativeFlowTest` and `NativeTaskAttachmentsTest` clear debug credentials;
   do not run those over an authenticated session without arranging restoration.
2. Verify actual account enrollment/discovery against the already enabled cmux
   mobile listener; then open workspaces, type, paste images/files, resize, navigate
   and reconnect with the actual Mac.
3. Check real Gboard temporary URI grants and settled terminal rendering. Continue
   the remaining parity requirements in `PARITY.md`; this milestone does not close
   the full-app goal.
