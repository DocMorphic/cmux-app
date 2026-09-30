# Native Todo checklist

Reference: cmux `4c5272e9153eca2033c9f40ac749f0c3a5bcb291`.
Source comparison: shared `MobileTodo*` models, `MobileTodoMutation`,
`MobileCoreRPCClient+Todo`, `MobileShellComposite+Todo`, `TodoSurfaceModel`,
`TodoSurfaceView`, `TodoSurfaceRowView`, `TodoStatusMenu`, and status presentation.

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

## Acceptance still required

The Pixel was not visible to ADB during this checkpoint, despite the cable being
reported connected. No phone data, installed app or sleep setting was changed.
Real Mac/Pixel Todo operations, reconnect behavior, touch/accessibility and exact
visual parity remain unverified. These tests use the production Android UI and
transport with a local framed RPC fixture, not a real cmux Mac server.

Signed build 244 predates zoom, file panels and this Todo implementation. It does
not contain this feature. Simulator and additional browser streaming work remains
tracked in [MAC_SURFACES.md](MAC_SURFACES.md); this is not full-app completion.
