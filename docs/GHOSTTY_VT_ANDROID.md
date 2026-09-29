# Upstream Ghostty VT core on Android

Checkpoint: 2026-09-30. Ghostty parses the Android app's byte/hybrid terminal
streams, and the app paints ordinary and Unicode placeholder Kitty placements.
Authoritative Mac grid delivery remains selected where negotiated. Full terminal
parity, performance and physical acceptance are unfinished. The
core/binding sections describe earlier steps; the final rendering section records
the latest implementation and evidence.

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
implemented below, including bounded decoding and ordinary image painting.
Remaining work includes broader graphics and glyph/input-mode
fidelity, performance measurement and actual Pixel/Mac TUI, resize, input,
reconnect and scrolling acceptance. A text render-grid frame alone cannot
reconstruct missing image payloads. PTY replies and clipboard effects stay with
the Mac.

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

The placeholder bridge needs a new core/JNI checkpoint. Earlier artifacts such
as `36637832054` predate it and no longer match the source hashes. Build from the
commands above or generate a new native CI artifact from this branch:

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
`GhosttyGraphicsFrame` snapshots. At this binding checkpoint (`b6ac57b`), the
painter did not consume them yet; the rendering follow-up is recorded below.

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
  painting remains open. Cell-metric synchronization, bitmap caching, layer
  ordering and partial-row clipping were implemented in the rendering follow-up.
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
Use the replacement artifact from successful [run 36637832054](https://github.com/DocMorphic/cmux-app/actions/runs/36637832054),
which matches the `b6ac57b` checkpoint before the placeholder bridge. All 29 receipt files and the
JNI inside its test APK were hash-verified. JNI SHA-256:
`73ef5984bdd3bc98748f1d04b64494bf14deb6918f563956661fa16f5dc530eb`.
The downloaded test APK passed all **11 native Android tests in 0.155 seconds**
and ELF/ZIP 16 KB alignment checks. Evidence is under
`captures/runtime/terminal-images/ci-native-runtime.log`. Gradle rejects the
older binding hash rather than loading stale JNI.


## Image rendering in the app (2026-09-30)

`NativeScreen` initializes Ghostty cell pixel metrics before parsing replay bytes.
Font-metric changes restart the terminal effect. `GhosttyVtTerminal` exposes
bounded cached graphics snapshots, and `TerminalGridPainter` consumes them in the
same live view used for text. Authoritative render-grid transport is unchanged.

The painter follows the pinned upstream `renderer/generic.zig` and `image.zig`
order: terminal background, images below cell backgrounds, explicit/inverse cell
backgrounds, images below text, text/cursor, then images above text. Placements
sort by z and unsigned image ID. It handles source cropping, alpha, pixel offsets,
scaling, letterboxing and fractional history clipping, including the extra bottom
row. The bitmap cache keys pixels by generation and crop and is bounded to 40 MB
(the maximum RGBA expansion of the native 10 MB grayscale budget). Eviction drops
references without recycling bitmaps that a hardware display list may still use;
view disposal releases the cache.

A pixel assertion caught Android filtering pixels from outside a source crop.
The implementation now crops owned pixels before bitmap scaling. The final test
checks a green translucent crop beside red source pixels and confirms no red
bleed. Earlier failing evidence is retained in the ignored runtime directory.

Verification:

- **15 Android 17 arm64 app tests passed in 13.972 seconds**: five new image
  rendering cases, four native-mirror regressions, three existing grid rendering
  checks, and three existing app/RPC terminal interaction scenarios.
- New checks cover PNG crop/alpha/offset/scale, all three z layers and default
  background transparency, same-size image replacement/delete, painting cached
  pixels after native close, fractional-history bottom clipping and byte-replay
  reconstruction through the production mirror. Saved pixel images were viewed.
- Main and test debug APKs build. Main APK native ELF LOAD/RELRO and ZIP alignment
  pass 16 KB. Main SHA-256:
  `aee6eae14a9051ad3a0eecb0760265bb08279a5bd4707001e41cae5efb909917`.
  Test SHA-256:
  `8c9537eab7a2808a3fb6cce5ca54a2766fbb8b623e51b5569490584f66b3d018`.
- Evidence: ignored `captures/runtime/terminal-images/` and
  `build/ghostty-vt-android/image-painter-*.log`. Emulator stopped after testing.
  Its runtime uses 4 KiB pages. No Pixel install or live-Mac image claim is made;
  the Pixel remains absent from adb and signed release 157 is unchanged.

At this ordinary-placement checkpoint, Unicode placeholders were still missing.
The pinned C API marks virtual placements but does not resolve their per-cell
fragments; the follow-up below exposes the upstream `graphics_unicode.zig`
resolver and adds hardware Canvas checks. Image-heavy performance and complete
graphics replay against a real payload-capable Mac remain open. The screen-anchored grid's missing image payload remains a separate
shared-contract constraint, as described above.


## Native Unicode placeholder rendering (2026-09-30)

The exported core now includes the project's small
`ghostty/src/main/zig/virtual_placements.zig` C bridge. It calls upstream
`placementIterator` and `renderPlacement` directly, preserving encoded high-byte
and palette image IDs, placement IDs, continuation runs and aspect-ratio rules.
The JNI caller matches the renderer's guard: it scans placeholders only when
virtual placements exist. Missing images/placements are skipped by the resolver.
The viewport scan includes the following row for fractional bottom-edge paint.

The core builder adds one export to `src/lib_vt.zig` and copies the bridge into
its isolated source export. It records the original/patched export hashes and
bridge hash; it never patches the input checkout. The JNI builder requires that
bridge hash and records the C header hash. Gradle and the CI cache include both
bridge sources, so an older core/JNI artifact cannot silently satisfy the build.
The C bridge's 56-byte record ABI is asserted at compile time and exercised by
native Android tests. Callbacks run synchronously under the terminal owner's lock
and copy values; they retain no borrowed pointers and install no terminal effects.

Snapshots retain stored virtual-placement metadata and add resolved fragment
records. The combined snapshot has a 65,536-placement bound inside the existing
16 MiB byte limit; the underlying protocol still has its separate 4,096 stored
placement limit. Placeholder fragments use Ghostty's resolved coordinates with
an edge-clamped bitmap shader, including independently rounded/zero-sized source
extents for tiny images split across larger grids. A sub-texel epsilon avoids a
singular Android shader transform. Ordinary image crops retain their separate
crop-before-scale behavior. Placeholder text shapes as blanks, while the original
characters remain available to copy/accessibility.

Hardware testing exposed a second bug: image-only replacement left an equal text
plan and did not immediately invalidate Compose's draw list. `RenderGridView`
now observes the output revision in the draw scope, also covering cursor-only
updates. The hardware test verifies native replacement pixels first, then checks
the captured live view without waiting for the blink timer.

Verification:

- **5 JVM decoder tests passed.**
- **15 native Android tests passed in 0.137 seconds**, including four new
  placeholder cases: high IDs and continuation runs; palette IDs/history/extra
  bottom row; missing/deleted/alternate state; and regular placements alone.
- **17 app Android tests passed in 3.156 seconds**: four new placeholder pixel
  cases, one hardware Canvas test, five ordinary-image regressions, four native
  mirror checks and three grid/glyph checks. They cover all image fragments,
  text-only geometry changes, deletion without fallback glyphs, fractional
  scrolling, one-pixel images across larger grids and hardware image replacement.
  The saved software and hardware render images were inspected.
- Main, app-test and native-test APKs build. Native ELF LOAD/RELRO and main/native
  test APK ZIP alignment pass 16 KB. Runtime is the API 37 arm64 emulator with
  4 KiB pages; no physical 16 KiB runtime claim is made.
- Main APK SHA-256:
  `28e05f67da0b16439f394c333845baf80c591ac05022508203ac190f81df9b8b`.
  App test SHA-256:
  `10cfa25e249a355f2b397099c7ad16a97266d2c50146b1ec505d5b20bbc7d26d`.
  Native test SHA-256:
  `9d5c30e126bf0366f421afa5b5c917b65867e5944277fd65bbba69ec600ab5ba`.
- Evidence: ignored `captures/runtime/terminal-placeholders/` and
  `build/ghostty-vt-android/virtual-*.log`; the earlier hardware failure is retained.
  Emulator stopped after testing. The Pixel is still absent from adb. Signed
  release 157 and the Pixel's installed checkpoint are unchanged.

A new Linux CI artifact remains to be verified for this bridge. Image-heavy
performance/resource-budget parity, wider graphics corpus comparison and real
Mac/Pixel replay/input/resize/reconnect acceptance remain open. Screen-anchored
render-grid delivery still cannot reconstruct images absent from its payload.

## Reusing unchanged image pixels (2026-09-30)

Graphics snapshots now use `GVI2`. The JNI caller supplies generations for the
immutable images it already owns; matching native images emit metadata and a
reference marker instead of resending their pixels. The decoder verifies ID,
generation, dimensions and format before reusing an image. Every snapshot still
recomputes placement geometry and Unicode fragments, including when only text or
the viewport changed. Replacements with identical dimensions receive new pixels.
Deletion, screen changes and close release the owner's unused cache references.

Image data is private: the `pixels` getter returns a defensive copy, and the
painter uses unsigned `byteAt` reads without cloning whole images for each crop.
Maps/lists returned by the decoder are unmodifiable. Reference packets retain
all decoded-byte/count limits; missing or mismatched generations are rejected.
The JNI manifest and Gradle gate require graphics snapshot version 2. Older JNI
artifacts are incompatible, even if they already contain the placeholder bridge.

The same synthetic workload was measured before and after this change: one
1,048,576-byte RGBA image, 20 warmup updates, then 200 one-character text updates
with graphics snapshots. Both runs used the API 37 arm64 emulator with two cores
and 1,536 MiB RAM. The benchmark measures this snapshot path, not full UI frame
rate; ART allocation/GC counters are process-wide. These are individual local
runs, not physical Pixel or production performance claims.

| Measurement | Before | After |
| --- | ---: | ---: |
| Median update/snapshot | 2.686 ms | 0.0286 ms |
| 95th percentile | 28.127 ms | 0.0985 ms |
| Thread CPU across 200 updates | 366.739 ms | 6.558 ms |
| Java bytes allocated across 200 updates | 421,494,784 | 163,840 |
| GC count | 4 | 0 |
| Updates sharing the immutable image object | 0 | 200 |

`GhosttyGraphicsProfileTest` accepts a `profile_label` instrumentation argument
and writes JSON under the test package's external `files/profiles` directory.
The baseline test APK is preserved locally under ignored
`build/ghostty-profile-baseline/`. Raw measurements and runtime logs are in
`captures/runtime/graphics-profile/`.

Verification:

- **7 JVM tests passed**, including reference-packet truncation, metadata
  mismatches, missing cached pixels, defensive copies and deletion.
- **18 native Android tests passed in 0.360 seconds**, including the profile,
  pixel reuse with changing scroll/resize geometry, replacement/alternate-screen
  isolation, and all prior terminal/graphics/placeholder cases.
- **17 app rendering tests passed in 2.791 seconds**, including the hardware
  image-replacement test and text-only placeholder geometry changes. The app
  test APK is unchanged from the previous checkpoint, SHA-256
  `10cfa25e249a355f2b397099c7ad16a97266d2c50146b1ec505d5b20bbc7d26d`.
- Main and native-test APKs build and pass ELF LOAD/RELRO and ZIP 16 KB alignment.
  Main SHA-256:
  `0ebe10105a94723ce2055e9bd6a76a8d6f07262d51ce28fb316fcd40c804fd0a`.
  Native-test SHA-256:
  `7843caca90388f011edc924e5aea6423d849f944eb8486fdb153b1b934f2a44a`.
- Emulator stopped after checks; no Pixel/Mac setting or signed release changed.
  Linux run `36641031462` for the preceding placeholder commit was still building
  its core at this checkpoint, and GitHub did not yet expose its logs. A current
  GVI2 Linux artifact still needs a successful build and verification.

Large-image decode/upload and overall rendering performance, resource-limit
parity, broader graphics comparisons and real Mac/Pixel acceptance remain open.
