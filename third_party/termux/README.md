# Termux terminal emulator source

Vendored from https://github.com/termux/termux-app at
`8629e632fcb95da272221be327db653fb24befe9` (2026-09-27).
The Java sources are compiled directly into cmux-app. No Termux shell/session is
created and no JNI library is loaded; only the terminal parser and its buffers
are used. Repository licensing is GPLv3; see LICENSE.md and COPYING, including
the upstream attribution to Android Terminal Emulator (Apache 2.0).

Local changes are recorded here:
- Added `TerminalEmulator.isBracketedPasteMode()` to expose its existing mode bit.
- Bitmap garbage-collection intervals use `System.nanoTime()` in milliseconds
  instead of Android SystemClock, allowing the same parser to run in JVM tests.

The adapter suppresses parser-generated replies and clipboard operations: the
Mac's terminal is the PTY owner and already handles device queries. Only explicit
Android user input is sent to cmux. Keep this source pin and modifications visible
when updating the engine.
