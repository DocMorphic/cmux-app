# Upstream Ghostty VT core on Android

Checkpoint: 2026-09-29. Ghostty is now integrated into the Android app's byte and
hybrid terminal streams. Authoritative Mac grid delivery remains selected where
negotiated. Full terminal parity, image rendering and physical acceptance are
still unfinished. The core/binding-only sections below describe earlier steps;
the app integration section records the current state.

## Source and build

The cmux source pin `4c5272e9153eca2033c9f40ac749f0c3a5bcb291` references
[`manaflow-ai/ghostty`](https://github.com/manaflow-ai/ghostty) submodule commit
`edefce7785c9f439966c68588db1edbd6b435203`. Its `libghostty-vt` C API exposes
terminal parsing, render snapshots, Unicode graphemes, modes, scrollback, key and
mouse encoding, and Kitty image/placement data. The `cmux-tui/bindings/java` SDK
is a resource/control SDK; it does not provide the terminal renderer.

The upstream build already supports Android NDK paths and a 16 KB maximum page
size. The unmodified arm64 shared library nevertheless failed this project's
RELRO check: its RELRO end was `0x1d3000`, not a 16 KB boundary. The build script
adds `link_z_common_page_size = 16384` to the Android block in an exported copy
of the pinned source. It leaves the input checkout untouched and records both
source-file hashes. Do not substitute the unpatched shared library.

Prerequisites: Python with tar extraction filters, Git, Zig **0.16.0**, and NDK
**28.2.13676358** (r28c), on macOS or Linux. The current app supports arm64/API 26+;
this script targets the same ABI and minimum API. Install tools normally; do not
disable HTTPS verification to work around a local Python certificate problem.

```sh
python3 scripts/build-ghostty-vt-android.py \
  --source /path/to/pinned-ghostty-checkout \
  --zig /path/to/zig-0.16.0/zig \
  --ndk /path/to/android-sdk/ndk/28.2.13676358 \
  --output build/ghostty-vt-android
```

Use a new output directory. The script archives the exact commit, builds the
static/shared libraries, compiles a synthetic Android smoke executable and checks
ELF LOAD/RELRO alignment. `manifest.json` records source/tool versions and artifact
hashes. Outputs and downloaded dependencies remain ignored; the separate JNI
builder supplies the app's packaged library. Retain upstream licenses and dependency
notices when eventually vendoring or distributing these artifacts.

## Runtime probe

`scripts/native/ghostty-vt-smoke.c` exercises 100 terminal create/free cycles with
byte-by-byte UTF-8 and ANSI input, RGB/curly-underline styles, wide characters,
application cursor/bracketed paste modes, alternate-screen restoration,
scrollback, resize and a render snapshot. It uses synthetic content only, with no
PTY, account, network, clipboard or terminal-effect callbacks.

To run on an explicitly selected Android emulator, copy the built shared library
as `libghostty-vt.so.0` beside the executable in a task-specific directory under
`/data/local/tmp`, make the executable runnable, then invoke it with that directory
as `LD_LIBRARY_PATH`. Keep the exit status and printed page size. A runtime pass
on a 4 KB emulator does not establish 16 KB runtime acceptance; the independent
ELF alignment check remains necessary.

## Verified checkpoint

- The build script completed successfully from an exported copy of the pinned
  source. The input checkout remained clean.
- Both the shared library and smoke executable pass ELF validation with four
  LOAD segments and one RELRO segment, aligned for 16 KB pages.
- The smoke executable exited **0** on the Android 17/API 37 arm64 emulator:
  all **100 lifecycles passed**. Its reported runtime page size was **4096**.
- Shared library SHA-256:
  `9e46622a8cbe353bc1c9117866ea1e77f20c86081f719d70b07ac0c235219cf7`.
- Smoke executable SHA-256:
  `7d98347f37526ded4970d19745f43ec364446c7ee27235d7a3426b6b154ba09e`.
- Build logs/receipt are under ignored `build/ghostty-vt-android/`; runtime
  evidence is under `captures/runtime/ghostty-vt-android/`. The emulator was
  stopped after the check. No Gradle APK build or release publication was needed.
- The physical Pixel remains on app checkpoint `5d24946`; it was in use, so no
  UI or native-core runtime check was performed there and its sleep setting
  remained unchanged at `0`.

## Integration still required

The binding, app adapter, replay ownership and native byte/hybrid selection are
implemented below. Remaining work includes bounded image decoding/placement,
remaining glyph/input-mode fidelity, performance measurement and actual Pixel/Mac
TUI, resize, input, reconnect and scrolling acceptance. Exposed Kitty data is not
yet Android image rendering; a text render-grid frame alone cannot reconstruct
missing image payloads. PTY replies and clipboard effects stay with the Mac.

The C API is marked unstable upstream, so the exact source pin and matching
headers are part of the binding contract. Building the library does not prove
JNI correctness, Android UI performance, inline graphics or iOS feature parity.

## Android binding checkpoint

The new `:ghostty` Android library module owns the terminal, render state, row
iterator and cell iterator together. Kotlin serializes each terminal's operations;
the C registry uses monotonic IDs and a mutex to reject stale handles instead of
dereferencing Java-provided pointers. Close is idempotent and frees all four native
objects. Failed construction also unwinds partial allocations.

Snapshots copy versioned, bounded binary data into JVM-owned values. They carry
grapheme clusters and cell widths, resolved explicit/default colors, rich underline
style/color, decorations, cursor, screen/modes and scrollback. Reading history
restores the live viewport before subsequent bytes are parsed. No PTY reply or
clipboard callbacks are installed; pinned upstream image storage defaults to
direct payloads, with file/temp-file/shared-memory media disabled. Image decoding
and painting are not exposed by this binding yet.

The pinned C header labels `max_scrollback` as lines, but tracing through
`c/terminal.zig` to `Screen.init` proves it is a **byte budget**, rounded up to
storage pages. The binding therefore calls the option `scrollbackBytes` (16 MiB
default, configurable 0–64 MiB) and reports the actual retained row count. It does
not convert bytes into an assumed line limit. Zero disables history.

Build after the core command above:

```sh
python3 scripts/build-ghostty-jni-android.py \
  --core build/ghostty-vt-android \
  --ndk /path/to/android-sdk/ndk/28.2.13676358
./gradlew :ghostty:testDebugUnitTest :ghostty:assembleDebug :ghostty:assembleDebugAndroidTest
```

The JNI builder verifies core artifact hashes, links the static archive into
`libcmux_ghostty.so`, strips debug information, checks alignment and records the
binding source hash. Gradle refuses an outdated binding checkpoint. Upstream and
downloaded dependency licenses/notices are included in the module's assets. The
library needs only Android's libc/libm/libdl; no separate versioned Ghostty `.so`
must be packaged.

Verification on the final binding:

- **3 JVM tests passed**: owned-data decoding; every truncated prefix, trailing
  bytes and unknown versions; invalid dimensions/colors/widths/lengths.
- **7 Android 17/API 37 emulator tests passed in 0.378 seconds**. Coverage includes
  split Unicode/ANSI, combining and emoji clusters, wide-cell columns, RGB and
  curly underline color/style, modes/alternate restore, OSC colors and erased
  backgrounds, history/resize, copied snapshot independence, 100 create/close
  cycles with handle-count checks, concurrent close, stale-ID rejection, invalid
  input and 3,997 retained history rows. A zero-history terminal was also checked.
- The earlier six-case run passed before the byte-budget correction. The final
  seven-case run is the acceptance evidence for the corrected API.
- The AAR and instrumentation APK build. Both pass ELF 16 KB alignment; the test
  APK also passes ZIP 16 KB alignment. Runtime page size remains the emulator's
  4 KB, so physical/16 KB runtime acceptance remains open.
- JNI SHA-256: `03e65225bc3e804d40f7650dd00ca35ebf0b579985083ac79267e956523ef70a`.
- Instrumentation APK SHA-256:
  `22344d984876599fecc7f9655a8a5b59e453e8dc72d881931b497e913654d069`.
- Evidence: ignored `captures/runtime/ghostty-jni/` and
  `build/ghostty-vt-android/gradle-binding-final.log`. Emulator stopped afterward.

At the binding-only checkpoint, the app had no dependency on `:ghostty` and the
Pixel APK was unchanged. The integration below supersedes that state.

## App build dependency

The app now depends on `:ghostty`. A local build requires the core and JNI commands
above, or the native artifact produced by the existing Android workflow's new
`ghostty_only` option. That option does not publish a signed app or require release
signing secrets. From a checkout with GitHub CLI access:

```sh
gh run download 36635864459 --repo DocMorphic/cmux-app --name cmux-ghostty-android-arm64 --dir build/ghostty-vt-android
```

That reviewed Linux checkpoint passed the artifact and runtime checks below. If
it expires, build a replacement and inspect its result before downloading:

```sh
gh workflow run android.yml --repo DocMorphic/cmux-app --ref feature/local-mac-bridge -f ghostty_only=true
gh run list --repo DocMorphic/cmux-app --workflow android.yml --branch feature/local-mac-bridge --limit 5
# After the selected native run succeeds (replace RUN_ID):
gh run download RUN_ID --repo DocMorphic/cmux-app --name cmux-ghostty-android-arm64 --dir build/ghostty-vt-android
```

Use a fresh download directory, or move an older checkpoint aside first. The
artifact includes JNI, receipts and notices for Gradle builds on Windows/macOS/
Linux; it does not contain the entire source/build cache. Rebuilding JNI locally
requires a complete core source build. Normal app CI restores the reviewed native
artifact cache or builds it from the exact source pin using SHA-verified Zig
0.16.0 and NDK r28c, then validates the native receipts through Gradle.

## App integration

`NativeScreen` supplies `GhosttyVtTerminal` to `TerminalStreamMirror`. Pure grid
sessions allocate no local VT core. Byte and hybrid sessions use Ghostty, while
the existing replay barrier, sequence/overlap handling, gap recovery and alternate
grid negotiation remain in place. The Termux implementation remains the JVM
compatibility fixture through the injected `ByteTerminal` interface.

A replay fully creates and materializes its candidate before closing the old
owner. Failed candidates are closed while the prior frame survives. The terminal
effect closes its mirror after cancelling output/event consumers; mirror close is
idempotent and refuses later mutations. Cached copied frames can finish painting
during viewport replacement without reopening or entering a closed native handle.

The adapter retains explicit/default colors, glyph clusters, wide cells, cursor
shape/blinking, modes and history. The Canvas painter now supports colored single,
double, curly, dotted and dashed underlines, faint decorations and hollow cursors.
Other image and glyph-fidelity work remains open. Upstream and dependency notices
are packaged and accessible from the app's open-source licenses dialog.

Integrated verification:

- **23 focused JVM cases passed**: 10 stream/replay, 10 render-grid and 3 new
  ownership cases. They cover retiring an old owner, retaining old content after
  a candidate snapshot fails, idempotent close and avoiding native allocation in
  grid/unopened sessions.
- **10 Android 17 emulator cases passed in 26.357 seconds**: four native adapter/
  mirror/bitmap cases, three existing font/grid rendering cases and three app/RPC
  scenarios. The native cases include the captured Vim open/edit/exit bytes,
  hybrid reseeding and byte-gap recovery, closed-frame painting data, rich
  graphemes/styles and five distinct red underline patterns with faint shading.
- The production `NativeScreen` byte path passed split UTF-8, duplicate output,
  alternate restore, gap replay, scrollback/Latest and no unsolicited PTY reply.
  Existing authoritative-grid primary scrolling and alternate mouse/wheel checks
  also passed with the new lifecycle and painter.
- The final main/test APKs build. All four packaged native libraries pass ELF
  16 KB LOAD/RELRO checks; the main APK passes ZIP 16 KB alignment. Ghostty's
  aggregated notices were found in the APK and the license dialog includes them.
- Main APK SHA-256:
  `e90dbfe9518edf46ef5b290e34fa7ee97b9cf5540bf797aebfe0fadc687de82f`.
- Test APK SHA-256:
  `1d6afae4acc63f27f5d7fcf6bb99648cee740c550548c245d3fdd84f6d82d9bc`.
- Evidence is under ignored `captures/runtime/ghostty-integration/`. Underline,
  full-screen editor and scrollback screenshots were inspected; emulator stopped.
- Linux native CI [run 36635864459](https://github.com/DocMorphic/cmux-app/actions/runs/36635864459)
  succeeded at `cdfa0db`. All 29 files in the downloaded JNI receipt matched their
  hashes; source revision and JNI binding hash matched this checkout. The test
  APK contains exactly the published JNI library, SHA-256
  `a2e4809c0fd3163ad805a80e81464cda724cba63c1c3f0d1d537f442cd53cf3e`.
  Its ELF LOAD/RELRO and APK ZIP checks pass 16 KB alignment. The downloaded test
  APK passed all **7 native runtime tests in 0.398 seconds** on the Android 17
  arm64 emulator (4 KiB runtime pages). This verifies the Linux-produced artifact
  on Android; it is not a physical 16 KiB runtime result or a Windows build run.
  Evidence: `captures/runtime/ghostty-integration/ci-runtime.log` and
  `ci-36635864459.log`. The emulator was stopped after testing. This native-only
  workflow did not publish a signed main app.
- The Pixel disconnected before this build could be installed; its last verified
  install remains `5d24946`. No phone/Mac setting was changed. Signed build 157 is
  unchanged; physical/performance/inline-image/full terminal acceptance is open.

## Inline graphics transport audit

The following source trace is at cmux pin
`4c5272e9153eca2033c9f40ac749f0c3a5bcb291`, not an assumption based on desktop
Ghostty's capabilities:

- `Packages/iOS/CmuxMobileShell/.../TerminalOutputTransportSelection.swift`
  selects render-grid when the host supports screen anchors. It explicitly
  retains that choice for local pixel scrolling. Grid+bytes hosts without the
  anchor use hybrid delivery, and older hosts can use raw bytes. Android follows
  that selection; enabling an image decoder is not a reason to change it.
- `Packages/Shared/CMUXMobileCore/.../MobileTerminalRenderGrid.swift` defines
  text spans, styles, cursor, modes, themes, revision/row-space and history
  metadata. The frame has no image pixel data or image placements.
- `Packages/iOS/CmuxMobileShell/.../TerminalOutputDelivery.swift` turns grid
  frames into `frame.vtPatchBytes()`. `MobileTerminalOutputSinking.swift` identifies
  these as generated grid patches or compatibility raw PTY bytes. Sending a text
  patch through Ghostty does not recover an image payload absent from that frame.
- `Packages/iOS/CmuxMobileRPC/.../MobileTerminalReplayResponse.swift` defines a
  preferred render-grid replay and VT snapshot/raw byte-tail fallbacks. This
  response has no separate image attachment field.
- The desktop/TUI SDK's `KittyGraphicsState` reports counts, byte totals, IDs and
  replay cursor metadata; that type supplies no pixel payload for the mobile
  renderer.

Consequently, graphics acceptance must identify the negotiated transport and the
actual bytes received. The verified screen-anchored mobile contract cannot supply
inline images through its grid alone. This is a constraint of the pinned shared
contract, not evidence of an Android platform restriction or a completed graphics
feature. Byte/hybrid compatibility streams can carry Kitty commands and still
need Android decoding, placement and painting support. Initial/recovery replay
must also contain sufficient graphics state; a tail that omits an earlier image
transmission cannot recreate it.

The pinned Ghostty C API provides the required byte-path primitives:
`GHOSTTY_SYS_OPT_DECODE_PNG`, owned allocator-compatible decoded RGBA data, image
and placement iterators, per-image generations, source cropping, viewport-relative
positions and z-layer classification. Stored images are already decompressed;
PNG becomes RGBA. File/temp-file/shared-memory media remain disabled for the
remote mirror. Remaining implementation checks should cover PNG/RGB/RGBA,
chunking/compression, alpha, source cropping, scaling/offsets, z-order, image
replacement/delete, alternate screens, history, resize and replay. A standalone
decoder test will not establish that images appear in the negotiated app path.

## Android image data binding (2026-09-30)

The JNI binding now installs a bounded Android PNG decoder and exposes owned
`GhosttyGraphicsFrame` snapshots. This is the image-data step; the app painter
still needs to consume these snapshots before inline images can appear.

- PNG decoding checks the signature and decoded dimensions before allocation,
  limits decoded RGBA to 10,000,000 bytes and returns straight RGBA through the
  allocator supplied by Ghostty. Native callbacks retain no Java pixel buffers.
  Class/method lookup happens at library load and has a consumer keep rule.
- Terminal image storage is bounded to 10,000,000 bytes, 1,024 images and 4,096
  placements per screen. File, temporary-file and shared-memory media are
  explicitly disabled. RGB/RGBA and compressed/chunked payloads are parsed by
  the pinned native core. PNG is decoded to RGBA before storage.
- Snapshots copy image IDs, unique generations, formats and pixels once per
  image, plus resolved crop, offset, scale, z, visibility, virtual/internal
  identity and viewport-relative geometry per placement. They validate lengths,
  IDs, image references, crops and allocation bounds. History reads restore the
  live viewport, and no borrowed native pointer survives the call.
- Virtual placements are identified, but resolving Unicode placeholders for
  painting remains open. The painter also needs cell-metric synchronization,
  per-generation bitmap caching, layer ordering and partial-row clipping.
- **5 JVM tests passed**, including truncation/length/identity/ownership checks.
  **11 Android 17 arm64 runtime tests passed in 0.563 seconds**, including four
  new graphics cases and seven existing lifecycle/terminal regressions. The
  graphics cases exercised PNG alpha and byte-by-byte input, crop/offset/scale,
  resize, compressed chunks, unsigned image IDs, generation invalidation,
  deletion, alternate/virtual placements, history and disabled file media.
  The owned snapshot remains readable after native close. This emulator uses
  4 KiB runtime pages; test APK ELF LOAD/RELRO and ZIP alignment pass 16 KB.
- Evidence: ignored `captures/runtime/ghostty-graphics/` and
  `build/ghostty-vt-android/graphics-{jni,gradle}.log`. Emulator stopped afterward.
  No Pixel or live-Mac graphics result is claimed.
- The main debug APK builds with this binding; all four native libraries pass
  ELF 16 KB LOAD/RELRO checks and the APK passes ZIP 16 KB alignment.
  Main APK SHA-256:
  `20c90fe689596cee7676953dab6eb259ddced26bcfcdcdea98fecf57ae960f07`.
  Native test APK SHA-256:
  `7cf8668abc322d61310eb9c6da23bc4f5a8baf902df8d41e776803df5e5ca0ff`.

The previous CI artifact `36635864459` matches the earlier text-only binding.
The current JNI source needs a fresh source build or replacement native CI
artifact; Gradle rejects the older binding hash rather than loading stale JNI.
