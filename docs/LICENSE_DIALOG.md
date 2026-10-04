# Responsive open-source license dialog

## Change — 2026-10-04

`OpenSourceLicensesDialog` previously read all bundled licenses during composition,
concatenated them, and laid out a single large Text. Signed build 517 exhibited a
3,019 ms opening frame on the existing Android 17 / 16 KB emulator.

The dialog now loads and segments the same 18 assets on Dispatchers.IO. A bounded
lazy viewport renders sections of at most 2,048 UTF-16 code units, preferring line
boundaries and preserving surrogate pairs. Concatenating each document's sections
reproduces its original text exactly. Every license remains scrollable, with file
headings and an explicit end marker. Visible sections support text selection;
selection across unloaded sections is not provided. Done/back dismiss during
loading; composition cancellation retires the load; errors display Retry.

## Verification

- Debug and instrumentation APK assembly: success, 1m 5s.
- One focused JVM case passes: lossless reconstruction, empty text, CRLF,
  long lines and supplementary Unicode at section boundaries.
- Three Android cases pass in 113.075 s on the existing arm64 API 37 / 16 KB AVD:
  exact reconstruction of every packaged asset and actual scrolling through the
  large Gecko document to the end of the list; dismissal during a held load;
  failure followed by retry and dismissal.
- MainActivity: opened from sign-in, dismissed to sign-in, reopened from the start,
  dismissed again. Screenshots inspected; crash buffer empty. App data preserved.
- Reset gfxinfo before each opening. First-open HWUI event was 761 ms; reopen
  99th-percentile histogram bucket was 500 ms. Neither captured interval includes
  the previous three-second frame, but debug emulator animation jank remains.
  This is not a controlled release comparison or proof of physical-device speed.
- The immediate screenshot requested after the tap still showed the previous
  screen; the later hierarchy and screenshot confirm the loaded dialog. The
  recorded 1,042 ms tap/capture duration includes ADB/PNG overhead and is not a
  first-visible-frame measurement.
- Pinned notice-engine packaging verification passes. No license assets changed.

Local evidence: `captures/runtime/licenses-responsive/` (ignored): `build.log`,
`runtime.txt`, `packaging.txt`, `main-licenses-loaded.png`, `test-running.png`,
`test-progress.png`, UI hierarchies, `open-gfxinfo.txt`, `reopen-gfxinfo.txt`,
`main-process.log`, `capture-timing.json`, `crash.txt`.

The only existing emulator was stopped and reaped after checks. No physical Pixel
was visible in ADB, so browser/Mac acceptance remains pending. No release APK was
built for this small change; signed development build 517 remains available and
does not contain it. Full app parity, physical performance, notice feed and push
configuration remain open.

## Signed follow-up — build 537 (2026-10-04)

The fix now ships in the signed development artifact described in
[PIXEL_INSTALL.md](PIXEL_INSTALL.md). After a signed-out 517 → 537 in-place upgrade,
the actual release MainActivity opened the populated license dialog and Done
returned to sign-in. The 23-frame interval contained 22 janky frames and a 650 ms
99th-percentile bucket; no three-second frame was observed in this sample.
This is an emulator smoke check, not a physical performance benchmark or proof
that all jank is fixed. The final crash buffer was empty; the sole AVD stopped.
Evidence: `captures/runtime/release-3921de2/license*` (ignored).
