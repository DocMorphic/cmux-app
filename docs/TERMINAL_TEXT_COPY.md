# Native terminal text capture

## Source comparison — 2026-10-08

Scoped to cmux `b9c0111a67bf8daadc2e87bff402254bfd9a26fd`:
`GhosttySurfaceRegistry.copyableTerminalText`,
`GhosttySurfaceView.copyableTextForCurrentSurface`, `TerminalTextSnapshot` and
`TerminalTextSheetView`. The source reads the selected local terminal off-main,
checks surface generation before publishing and caps the last 5,000 logical lines
while excluding trailing whitespace-only lines. No Mac RPC is required.
This scoped work does not advance the global parity pin.

## Implementation

- The JNI binding uses the pinned Ghostty formatter (`edefce7785c9f439966c68588db1edbd6b435203`)
  for a plain active-screen/history export. Soft wraps are joined, explicit line
  breaks remain, and ANSI formatting does not enter copied text. It measures the
  output before allocation and enforces the existing 16 MiB binding limit.
- Native lifetime/output locks cover the complete read. No borrowed pointer
  escapes and copy does not change the live viewport or held history anchor.
- Mac byte-terminal and shared SSH/Cloud text sheets capture their display owner,
  then export/cap on `Dispatchers.Default`. Replacement or dismissal cannot publish
  that pending read into another terminal's sheet. Failures show a retry or an
  explicit changed-terminal message; loading keeps Done available and Copy All
  disabled. The existing mutable render-grid compatibility path still freezes
  its bounded capture on its owner thread; it lacks native soft-wrap metadata.
- Android text metrics are prepared off-main with `PrecomputedTextCompat`. Native
  selection/Copy and Copy All remain available; only explicit copying writes the
  clipboard. The dark sheet now supplies a readable foreground for its title and
  status content, independent of the surrounding light/dark theme.

## Verification and limits

- **Six JVM cases passed**, covering bounded compatibility capture and the new
  logical-line cap, exact limits, internal blanks, trailing whitespace and Unicode.
- **Seven Android cases passed in 19.958 s** on the existing API37/16 KiB emulator:
  native logical wrapping/ANSI/Unicode, history/alternate screen, held position,
  5,000-line truncation, competing output/read calls and close rejection; sheet
  loading/dismissal, replaced-owner rejection/retry, native selection and exact
  clipboard contents. The final screenshot was visually inspected.
- Initial runtime tests exposed two fixture errors: a Kotlin test returned the
  exception from `assertThrows`, violating JUnit's void-method contract; another
  checked the error UI before background preparation settled. Both were corrected
  without removing their assertions. Screenshot review caught and fixed the dark
  title contrast. One freshness command omitted `ANDROID_HOME`; the subsequent
  correctly configured app/test build succeeded. Original logs are retained.
- The JNI binding was relinked against the existing verified local Ghostty core;
  no full native core rebuild was needed. Final APK ELF LOAD/RELRO and ZIP 16 KB
  alignment pass. No crash/ANR events were logged. Gradle and the sole emulator
  (1,536 MiB/two cores) were stopped and the emulator process reaped.
- Evidence: ignored `captures/runtime/terminal-native-copy/`. No real Pixel/Mac
  test, maximum-size latency claim, cloud checkpoint or signed release is implied.
  Physical selection/Gboard, large-text/TalkBack, compatibility-grid copy and
  broader lifecycle/latency acceptance remain part of the full goal. APK 616 is
  still the published release.
