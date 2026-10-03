# Native 16 KB verification

## RELRO classification correction — 2026-10-04

The original `scripts/verify-native-alignment.py` rejected every GNU_RELRO segment
whose end was not a multiple of 16384. That was too broad. Android's
[Bionic classifier at android-17.0.0_r1](https://android.googlesource.com/platform/bionic/+/refs/tags/android-17.0.0_r1/linker/linker_phdr_16kib_compat.cpp)
(`phdr_table_get_relro_min_align`) distinguishes a RELRO prefix from a RELRO segment
covering the whole writable LOAD. Only the prefix has a remaining writable suffix
that can conflict with protection of its final page.

The corrected checker exempts the conventional, unambiguous case with exactly one
LOAD starting at the RELRO address, RW flags, and no bytes beyond RELRO. It retains
end-alignment rejection for an unsafe prefix and unfamiliar/ambiguous layouts.
All LOAD alignment and virtual-address/file-offset congruence checks remain.
APK ZIP alignment remains a separate gate. No binary, protection flag, device
compatibility setting or linker preference is changed by this correction.

### Evidence

- **19 Python tests pass:** 10 update-policy tests and 9 ELF-layout regressions.
  CI now discovers both files. Tests cover a whole RW segment, RELRO padding,
  genuine unsafe prefixes, aligned prefixes, unrelated/ambiguous LOADs and retained
  LOAD alignment/offset rejection.
- All 13 arm64 libraries in the pinned GeckoView 157 experiment have RELRO covering
  the entire matching RW LOAD. The old checker rejected ten of them incorrectly;
  the corrected checker passes all 13. The experiment's ZIP alignment and its
  existing API 37 / 16 KB runtime with `pageSizeCompat=0` also pass. Physical Pixel
  acceptance is still required.
- The original **JNA 5.15.0 arm64** binary still fails for its RELRO prefix ending
  at `0x36000`. This was checked on that actual library, separately from unrelated
  32-bit ABIs in its AAR. Source download/hash and ELF headers are recorded.
- The retained pre-rebuild Iroh library and the original graphics-path 1.1.0 arm64
  library instead have whole-LOAD RELRO and pass the corrected classification.
  The earlier diagnosis attributing the Pixel warning to all three libraries was
  too broad. The original APK's JNA defect remains real; the historical replacement
  APK's verified Pixel `pageSizeCompat=0` result remains valid.
- The current rebuilt graphics AAR and signed milestone 494 also pass. This change
  does not replace/revert their native artifacts or change the signed milestone.

Evidence: `captures/runtime/notice-private-storage/` (ignored), including
`relro-layout.json`, `known-negative-jna.json`, `native-comparison.json`, the pinned
AOSP source and Python test output. Native ELF checks establish layout properties;
they do not replace package-manager, runtime or feature acceptance.
