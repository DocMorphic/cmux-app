# Native Todo checklist

Reference: cmux `4c5272e9153eca2033c9f40ac749f0c3a5bcb291`.
Source comparison: shared `MobileTodo*` models, `MobileTodoMutation`,
`MobileCoreRPCClient+Todo`, `MobileShellComposite+Todo`, `TodoSurfaceModel`,
`TodoSurfaceView`, `TodoSurfaceRowView`, `TodoStatusMenu`, and status presentation.

## Physical checklist UI acceptance — 2026-10-03

`LiveNativeTodoUiCheck` passed **OK (1 test), 29.055 s** on the Pixel 6a and its
existing saved NIGHTLY Mac. The test launches actual MainActivity via the pairing
Intent and uses the pane picker/checklist UI. It verifies each result with fresh
authoritative native workspace reads while continuing Compose effects:

- Add two checklist items; edit one to Chinese text plus an emoji ZWJ sequence.
- Cycle pending → working → completed → pending and verify completion partition.
- Long-press/drag reorder with injected touchscreen gestures on the physical Pixel.
- Set Review, leave/reopen the workspace, and verify the exact retained snapshot.
- Swipe-delete only an owned item and verify both host state and row disappearance.

Both checklist/reopen screenshots were visually inspected. Input text uses Compose
framework injection; this does not prove Gboard key taps/composition or TalkBack.
The real Mac RPC reconnect test below remains the independent reconnect evidence;
this UI check exercises navigation away/back, not a forced network reconnect.

Existing login and earlier pairings were preserved. Only a uniquely named test
workspace was created; it was closed once, absence verified, and its private
receipt removed. Normal plugged-in sleep setting was restored to **0**. The
runner refuses a locked device and never clears app stores. Run with:

```sh
python3 scripts/check-live-todo-ui.py --serial DEVICE
```

Assembly passed in 38 s; Python syntax and diff checks passed. No production code
changed, no emulator started, and no extra signed build was triggered for this test.
Instrumentation SHA-256: `1b016315e981625cbabbfa44630b0044491fb464d8bc1df056f2494948701ff7`.
Ignored evidence: `captures/runtime/pixel-resume-20261002/todo-ui-first/`.

## Behavior

- A native Todo surface opens from the workspace inventory and surface picker.
  It displays pending, in-progress and completed items, completion progress,
  automatic/manual status, and hidden status as “No Status”.
- Tap the state circle to cycle states; tap text to edit and commit on Done or
  focus loss. Add text with the composer. Long-press to drag with edge scrolling,
  swipe left to delete, or use accessible move/delete actions.
- Completed transitions move to the completed partition; reopening inserts before
  completed items. Moves clamp within the item's completion partition. Host item
  IDs, text, origin and authoritative order are retained.
- Limits match the pinned model: 50 items and 500 extended grapheme clusters after
  trimming. Android ICU preserves emoji ZWJ sequences and combining characters.
- Native RPC mutations require `todo.v1`, the current ready client and the exact
  owning workspace. Todo mutations use workspace scope, not panel-file scope.
  After a successful mutation, the owning client's workspace inventory refreshes;
  both responses are fenced against a changed connection. There is no fallback
  workspace, automatic terminal focus, terminal input or mutation retry.
- Updates are optimistic and serialized. Authoritative snapshots arriving while
  a request is pending are deferred. Rejected/uncertain requests roll back to
  known truth and show an error; later reconnect snapshots reconcile with the Mac.
  A lost reply can mean the Mac applied the change, so the error does not claim
  that the Mac was undone. The app never resends that mutation automatically.
- Offline controls disable while the latest checklist stays readable. Lifecycle
  cancellation propagates without leaving a stale error dialog. State changes,
  additions and errors use Android haptic feedback.

## Verification — 2026-09-30

- Eight focused Todo JVM tests pass: strict decoding, ordering, bounds, exact RPC
  parameters/no-resend policy, concurrency, rollback/deferred truth, cancellation.
  Five existing surface JVM tests also passed in the same build.
- Final debug and test APKs build successfully. All four packaged native libraries
  pass LOAD/RELRO 16 KB checks; APK ZIP 16 KB alignment passes.
- Four final Android 17 tests pass on the 16 KiB kernel in **11.954 seconds**:
  - Native UI/RPC add/edit/state/drag/status/swipe workflow, rejected delete rollback.
  - Lost add reply: a new connection recovers the host-assigned item ID and text;
    exactly one add RPC was sent.
  - Offline checklist remains visible and all tested mutation paths are disabled.
  - Unicode normalization retains 500 whole family/flag/accent grapheme clusters.
- The first workflow run exposed an assertion before async navigation completed;
  the test now waits for the actual checklist. The corrected flow and the final
  four-test run pass. Final checklist/reconnect screenshots were visually inspected.
- Local ignored evidence: `captures/runtime/todo/runtime-final.log`,
  `todo-checklist-final.png`, `todo-reconnected.png`. Emulator stopped afterward.
- Debug APK SHA-256:
  `d8a18441ecbbb87943e725c004568a597a16d7fec7e95fa60447c56320b1b0d5`.
- Instrumentation APK SHA-256:
  `337469f809217ee86f4a965a827d833b22eec72a8db9978ac46c409dd01cc1f7`.

## Physical native RPC acceptance — 2026-10-02

`LiveNativeTodoCheck` passed **1 test in 13.24 s**, zero skips, using the physical
Pixel's existing account and saved Iroh Mac. It verifies the host identity and
advertised `todo.v1`, creates a uniquely named workspace and opens only that
workspace's checklist with `focus=false`. Authoritative inventory verifies two
adds, whitespace trimming/user origin, a Unicode edit, working/completed/pending
transitions, reorder, manual/cycled/automatic status, and removal. A distinct
underlying native connection restores the exact checklist snapshot without
resending any mutation. Login is preserved. The disposable workspace is closed
once and its absence verified; its private ownership receipt is removed.

This test needs no Activity or unlocked screen and never clears app stores or
mutates existing workspaces. Interrupted runs retain a private receipt and refuse
automatic rerun until inspected. Run only with explicit opt-in:

```sh
adb -s DEVICE shell am instrument -w -r \
  -e class io.github.docmorphic.cmuxapp.LiveNativeTodoCheck \
  -e cmux_live_todo_fixture true \
  io.github.docmorphic.cmuxapp.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Production debug APK SHA-256:
`f72f05838b1e441c43bdc154840b3796b4d696f28ec6eaf2297c3435b969e1cb`.
Test APK used for this run:
`e82f34a035935344bae571bd14005fd55a91f0763c416b343e18d1172fc91a1e`.
The interrupted initial build was not counted as successful; resumed test
assembly completed in 10 s. Ignored evidence:
`captures/runtime/pixel-resume-20261002/todo-*`. No emulator was started and
signed build 385 remains unchanged. Physical Todo touch/accessibility and visual
parity are still unverified; RPC acceptance does not prove those UI behaviors.

## Earlier acceptance status (superseded for RPC by the checkpoint above)

The Pixel was not visible to ADB during this checkpoint, despite the cable being
reported connected. No phone data, installed app or sleep setting was changed.
Real Mac/Pixel Todo operations, reconnect behavior, touch/accessibility and exact
visual parity remain unverified. These tests use the production Android UI and
transport with a local framed RPC fixture, not a real cmux Mac server.

Signed build 248 includes this Todo implementation along with zoom and file
panels. Its signature, alignment and emulator launch are verified; see
[PIXEL_INSTALL.md](PIXEL_INSTALL.md). Simulator and additional browser streaming work remains
tracked in [MAC_SURFACES.md](MAC_SURFACES.md); this is not full-app completion.
