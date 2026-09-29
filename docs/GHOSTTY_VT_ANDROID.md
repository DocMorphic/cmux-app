# Upstream Ghostty VT core on Android

Checkpoint: 2026-09-29. This is a native engine and Android binding prerequisite,
**not an app renderer replacement or a completed terminal-parity milestone**. The installed
app continues to use the authoritative Mac render grid and the existing Termux
compatibility parser for byte/hybrid streams.

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
hashes. Outputs and downloaded dependencies remain ignored; no new binary is
packaged in the APK by this checkpoint. Retain upstream licenses and dependency
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

1. Connect the implemented Android binding to production replay replacement,
   surface switch and disposal. The binding has explicit ownership/close; the
   app must invoke it at each lifecycle boundary before enabling the engine.
2. Adapt the owned snapshots to the Compose painter, including full style and
   history viewport geometry. The binding already uses render-state iterators;
   upstream warns that grid references are unsuitable for a frame-rate loop.
3. Replace the byte/hybrid compatibility engine behind `TerminalStreamMirror`
   while retaining its replay barrier, unsigned cursor, overlap trimming and
   authoritative-grid negotiation. Keep Mac PTY replies, clipboard operations and
   other terminal effects disabled in this remote mirror.
4. Add bounded image decoding/placement, glyph/style painting and input-mode
   integration. Exposed Kitty data is not yet Android image rendering; a text
   render-grid frame alone cannot reconstruct missing image payloads.
5. Run parser/replay/lifecycle regression checks, rendered pixel comparisons,
   Android 16 KB compatibility checks and actual Pixel/Mac TUI, resize, input,
   reconnect and scrolling acceptance before selecting the new engine in the app.

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

The app has no dependency on `:ghostty` yet, so existing app CI tasks and the
installed Pixel APK are unchanged. App dependency/CI native preparation,
`TerminalDisplay` adaptation, actual replay/disposal ownership, images, rendering
performance and Pixel/Mac acceptance remain required before shipping this engine.
