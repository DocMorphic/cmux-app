# Supplemental Cloud dependency notices

`sources.json` records immutable source revisions/URLs, file hashes and the crate
versions using each text. These supplement license files omitted from published
crate archives; the original crate checksums remain in the pinned Cargo.lock.
The collector verifies every supplemental file before using it.

- Most texts come from the repository commit in the crate's `.cargo_vcs_info.json`.
- `enum-assoc` 1.3.0 declares `MIT OR Apache-2.0` but includes no license file at
  its pinned repository commit. Its original manifest/author attribution and the
  standard Apache-2.0 text are retained under that declared alternative.
- The Android Rust standard-library distribution's copyright and the matching
  Rust source licenses are retained. Its archive checksum is recorded.
- Ghostty's vendored SIMD header identifies simdutf **9.0.0** (the Zig package
  wrapper's 5.2.8 version is stale). Texts come from the 9.0.0 release commit; the
  complete additional PyTorch-derived BSD notice is extracted from Ghostty's
  pinned header.
- uucode's top-level license refers to two additional notices omitted from the
  downloaded package. They are preserved from its pinned source commit.

Ghostty's VT import graph uses uucode, itijah and Highway plus its embedded SIMD
source. `GhosttyZig.initVt` and `SharedDeps.addSimd/addItijah` at `324c027` were
inspected for this scope. Desktop fonts, themes, GTK and canvas dependencies are
not linked into this VT library. Re-audit this list when changing the Ghostty pin.

The resulting Android asset includes texts and attribution alongside a hashed
component inventory. This evidence does not establish runtime feature parity.
