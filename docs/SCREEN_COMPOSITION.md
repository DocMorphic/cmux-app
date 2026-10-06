# Native screen composition size

Updated 2026-10-06. This is a compiler-size fix; real-device responsiveness and
full UI parity remain separate acceptance gates.

## Why the screen was split

The preceding API37 / 16 KiB Android run logged a `NativeScreen` method-size
warning. ART skipped compiling that method. The matching debug project DEX
archive at `4a2d8df` measured **44,283 16-bit code units and 503 registers**.
The installed, merged APK logged 44,301 code units; archive and merged values
must not be treated as identical measurements.

Current [AOSP ART compiler source](https://android.googlesource.com/platform/art/+/master/compiler/compiler.cc)
rejects methods at or above `UINT16_MAX / 4` (16,383) code units or registers in
`Compiler::IsPathologicalCase`. This is a compilation guard, not a frame-time
benchmark or a guarantee that every Android build uses the same compiler policy.

## Changes and preserved lifetimes

- `NativeScreenTransientState` holds the 44 previously unkeyed snapshot state
  delegates. One composition-local `remember` creates it. It is not retained in
  a ViewModel and does not own client disposal.
- Account, client, terminal and viewport keys remain at their existing state
  call sites. Saveable state and explicit ownership checks are preserved.
- Feed/terminal setup, foreground effects, and route rendering each have their
  own unconditionally invoked composition group. The existing named route
  content groups remain inside the rendering group.
- Effect keys, bodies, captured client identities, disposal and navigation
  statements are unchanged. The pure `pairingLookup` projection moves just
  before the foreground group so both effects and rendering can use it.
- A structural comparison against `4a2d8df` verified statement preservation
  after reversing these extractions. This comparison does not replace runtime
  checks of recomposition and cleanup.

## Measurement

Same SDK build-tools 36.0.0 and debug project DEX archive measurement:

| Stage | Largest method, code units |
| --- | ---: |
| Original `NativeScreen` | 44,283 |
| State holder only | 41,576 |
| Rendering separated | 32,563 |
| Initial effect/setup groups | 17,825 |
| Final groups | **13,147** |

The final entry method has 214 registers. All **3,497 measured methods** from
`NativeScreen*.dex`, including generated callbacks and coroutine bodies, are
below both guards. This removes the observed size obstacle without turning off
Compose lambda memoization or changing effect restart rules. It is not a claim
of a 70% runtime speedup.

For later integration milestones (no APK packaging needed):

```sh
./gradlew :app:dexBuilderDebug --no-daemon --max-workers=2
python3 scripts/check-native-screen-size.py
```

Set `ANDROID_HOME`, put `dexdump` on PATH, or pass `--dexdump`. The checker exits
nonzero for a missing entry method, failed dump, or method exceeding either
guard. `--output path.json` saves the receipt. Its command batches also fit
Windows argument limits. It checks debug project archives, not a signed release
or the final merged APK.

## Acceptance evidence

Local evidence is under `captures/runtime/native-screen-size/`: baseline and
intermediate measurements, source hashes, extraction audit, compilation/build
logs, APK hashes, and the combined Android regression run. The final Kotlin and
instrumentation compile took 38 seconds; subsequent app/test APK packaging took
17 seconds. One existing API37 / 16 KiB AVD is used; no additional AVD is created.

**Ten distinct Android cases have unassisted passing evidence.** The initial
combined run passed nine and failed one in 98.338 seconds: an older terminal
test looked for the removed filter glyph. Its selector now uses the accessible
"Filter workspaces" label, checks the selected unread option, and dismisses the
menu that intentionally stays open. That case passed in 20.468 seconds after a
test-only APK rebuild (25 seconds). The initial failure is retained in the logs.

Coverage includes fresh legacy re-pairing, cancelled late computer switches,
screen disposal, computer-details return navigation, draft retention after a
rejected send, feedback/settings navigation, primary search/navigation,
notification retry, terminal repaint across resize/target change, and keyboard
resize with exact terminal input. No new crash/ANR events or compiler-size
warnings appeared in either run. Workspace, terminal, notification and keyboard
screenshots were visually inspected. All execution handles were reaped and the
sole emulator stopped after testing.

These are emulator fixtures, not physical Pixel/Mac acceptance. No frame-time
benchmark or signed release was produced. Physical workflows, broader UI
coverage and responsiveness profiling remain open.
