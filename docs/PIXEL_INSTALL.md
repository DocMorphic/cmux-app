# Pixel 6a install and native pairing check

This is the direct cmux Android build on `feature/local-mac-bridge`. It is still
a development build; see [PARITY.md](PARITY.md) for the unverified features.

## Rejected candidate 486 and replacement in progress

Build 486 at `372f48c` passed full CI and independent packaging checks, but its
actual API 37 / 16 KB launch failed with `VerifyError` in the generated
`NativeScreenKt` root lambda. **Do not install or recommend build 486.** The
emulator also showed a separate System UI cold-boot ANR; after recovery, the app's
own verifier failure remained reproducible. The original installed-data timestamp
was preserved by the 481 → 486 install, but startup acceptance failed.

Fix `555e4b8` moves the unchanged onboarding body into its own composition lambda.
An ART probe reproduces the exact error in 486 and verifies the fixed local
release APK (1,339 declared methods). The CI workflow now runs that check on an
ephemeral Android 17 emulator before uploading APKs. This is a DEX gate; actual
signed launch and authenticated device acceptance remain separate.
[Replacement build 488](https://github.com/DocMorphic/cmux-app/actions/runs/37153210672)
was dispatched at `555e4b89257d73a984f6dbec74894dff1a18759b`; its result and signed
upgrade are pending. Build 481 remains the last verified signed milestone.
Local evidence: `captures/runtime/build486/`, including negative/fixed class probes.

## Current signed development APK — build 481 (2026-10-03)

[Build 481](https://github.com/DocMorphic/cmux-app/actions/runs/37148164630)
passed at `59c75e3ff8c95f00c52096c9877ea7fbfd15a5ce`. Download the
[signed APK artifact](https://github.com/DocMorphic/cmux-app/actions/runs/37148164630/artifacts/11282812782)
and extract `app-release.apk` (repository access required). This batch adds the
minimum Mac version policy, encrypted version history, consumer build admission,
saved Computers during offline startup, and the iOS-based Mac pairing guide.
It includes all build 474 features. PR #1 remains a draft; this is an Actions
artifact, not a published GitHub release.

- Package: `io.github.docmorphic.cmuxapp`; version code **481**, version 0.2.0.
- SHA-256: `4d1ffcbbe6106c38c33eb29ab17aad043a1b1b14a3c4c9af52f6abb94e4f3a54`.
- Signer: `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4` (unchanged).
- APK size: **39,197,706 bytes**; min SDK 26, target SDK 36.
- Local file: `build/signed-run-37148164630/app-release.apk` (ignored).

Full app/Ghostty JVM suites and debug/test/release assembly passed in CI;
Gradle reported **6m 57s**. Four helper tests, ten update-policy tests and the
signed-APK gate passed. No full JVM count is inferred from CI logs. Native
libraries were restored from pinned caches. Independent downloaded-APK checks
confirmed CI's APK hash, source/run artifact metadata, stable signer, all 14 viewer
hashes, six native LOAD/RELRO checks, 16 KB ZIP alignment, disabled backup,
non-debuggable manifest and exclusion of all five debug fixture activities.
Packaged NOTICE matches the source; the original guide images resolve through
the optimized resource table with source-matching 618 × 171 dimensions. AAPT
changes their PNG encoding, so raw image byte identity is not claimed.

The existing API 37 / 16,384-byte emulator upgraded **474 → 481** with `install -r`,
preserving first-install time `2026-09-30 01:57:49`. Explicit stop/start reported
COLD, the actual MainActivity and **1,376 ms**, reaching the reviewed sign-in
screen without a compatibility warning or ANR dialog. `pageSizeCompat=0` and the
crash buffer was empty. The new setup link opened the visually inspected guide,
and Back returned to sign-in. This single launch is not a benchmark; the baseline
was already signed out, so authenticated migration remains unverified. No new
AVD was created; the existing emulator was shut down. Evidence:
`captures/runtime/build481/` (ignored).

The Pixel was absent from ADB. Physical native/browser/keyboard acceptance,
authenticated release migration and configured push remain open. Full five-stage
onboarding is the next implementation; [ONBOARDING_AUDIT.md](ONBOARDING_AUDIT.md)
records reviewed root, progress, replay, discovery, permission and layout behavior.
The broader source/visual audit remains incomplete and the global parity pin is
unchanged. This milestone does not complete the full goal.

## Previous signed development APK — build 474 (2026-10-03)

[Build 474](https://github.com/DocMorphic/cmux-app/actions/runs/37142127734)
passed at c45920e8ba20c036bd3ca7c11491a6e03c42d763. Download the
[signed APK artifact](https://github.com/DocMorphic/cmux-app/actions/runs/37142127734/artifacts/11280951103)
and extract app-release.apk (repository access required). This batch adds computer
method sections/endpoint diagnostics, saved reconnect through discovery outages,
combined Mac/SSH Computers management, and local hide/show with exact build
isolation and session retirement. It includes all build 468 features. PR #1
remains a draft; this is an Actions artifact, not a published GitHub release.

- Package: io.github.docmorphic.cmuxapp; version code **474**, version 0.2.0.
- SHA-256: 3179fdc11b69347b8de93c0881499fcd46d817b67834cfda0e44f419a0e87d00.
- Signer: 1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4 (unchanged).
- APK size: **39,123,990 bytes**; min SDK 26, target SDK 36.
- Local file: build/signed-run-37142127734/app-release.apk (ignored).

The full app/Ghostty JVM suites and debug/test/release assembly passed in CI;
Gradle reported **5m 21s**. Four helper tests, ten update-policy tests and the
signed-APK gate passed. No full JVM count is inferred from CI logs. Native
libraries were restored from pinned caches, not freshly rebuilt. The downloaded
APK matches CI's hash and GitHub's source/run artifact metadata. Independent
checks confirmed the stable signer, 14 viewer hashes, six native LOAD/RELRO checks,
16 KB ZIP alignment, disabled backup, no debuggable flag, diagnostics Application
and exclusion of all five debug fixture activities. Packaged attribution exactly
matches the plain source asset.

The existing API 37 / 16,384-byte emulator upgraded **468 → 474** with install -r,
preserving first-install time 2026-09-30 01:57:49. The immediate launch command
initially reported Android's PackageUpdateActivity and UNKNOWN/0ms while the
package-update transition finished; that is not treated as a cold-launch result.
A subsequent explicit stop/start reported COLD, the actual MainActivity and
**1,320 ms**, reaching the visually reviewed sign-in screen without a compatibility
warning or ANR dialog. pageSizeCompat=0 and the crash buffer was empty.
This single launch is not a benchmark. The baseline was already signed out, so
authenticated migration remains unverified. No additional AVD was created; the
existing emulator was shut down. Evidence: ignored captures/runtime/build474/.

The Pixel was unavailable to ADB. Physical browser/terminal acceptance,
authenticated release migration, configured push, broader native Iroh diagnostics
and the remaining source/visual audit are open. The
[Mac compatibility audit](MAC_COMPATIBILITY_AUDIT.md) identifies missing upstream
version-policy behavior for the next implementation step. This is a verified
development milestone, not completion of the full iOS parity goal.

## Previous signed development APK — build 468 (2026-10-03)

[Build 468](https://github.com/DocMorphic/cmux-app/actions/runs/37135587173)
passed at `acfae3e04f3bc3c1f96ea7cfdc4fa1f9e7fc6fa2`. Download the
[signed APK artifact](https://github.com/DocMorphic/cmux-app/actions/runs/37135587173/artifacts/11278398595)
and extract `app-release.apk` (repository access required). This batch adds stable
open computer menus, Mac build subtitles from scoped foreground presence, and
separate phone-connection/heartbeat/last-seen rows with encrypted verified history.
It includes all build 463 features. PR #1 remains a draft; this is an Actions
artifact, not a published GitHub release.

- Package `io.github.docmorphic.cmuxapp`, version code **468**, version `0.2.0`.
- SHA-256: `15799d17ee1e890e463ec966596b0fccfc1248a1f510e3619c8a18c5b4bed039`.
- Signer: `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4` (unchanged).
- APK size: **39,074,318 bytes**; min SDK 26, target SDK 36.
- Local file: `build/signed-run-37135587173/app-release.apk` (ignored).

The full app/Ghostty JVM suites and debug/test/release assembly passed in CI;
Gradle reported **7m 19s**. Four helper tests, ten update-policy tests and the
signed-APK gate passed. No full JVM count is inferred from CI logs. Native
libraries were restored from pinned caches, not freshly rebuilt. The downloaded
APK matches CI's hash and the artifact's GitHub source/run provenance. Independent
checks confirmed the stable signer, 14 viewer hashes, six native LOAD/RELRO checks,
16 KB ZIP alignment, disabled backup, no debuggable flag, diagnostics Application
and exclusion of all five debug fixture activities. Packaged attribution exactly
matches the source asset.

The existing API 37 / 16,384-byte emulator upgraded **463 → 468** with `install -r`,
preserving first-install time `2026-09-30 01:57:49`. Cold launch reported **1,212 ms**
and reached the visually reviewed sign-in screen without a compatibility warning
or ANR dialog; `pageSizeCompat=0` and the crash buffer was empty. This single
launch is not a benchmark. The baseline was already signed out, so authenticated
migration remains unverified. Before the upgrade, the emulator cold boot showed
its recurring System UI ANR dialog; the observed Close app button restored the
OS and build 463's clean sign-in baseline. The cause of that OS stall remains
unestablished. No additional AVD was created; the emulator was shut down.
Evidence: ignored `captures/runtime/build468/`.

The Pixel was unavailable to ADB. Current physical browser/terminal acceptance,
authenticated release migration, configured push, broader native Iroh diagnostics
and the remaining source/visual audit are open. This is a verified development
milestone, not completion of the full iOS parity goal.

## Previous signed development APK — build 463 (2026-10-03)

[Build 463](https://github.com/DocMorphic/cmux-app/actions/runs/37131181771)
passed at `6fc143375b3b19cc89c922cd1639594dc4fb3846`. Download the
[signed APK artifact](https://github.com/DocMorphic/cmux-app/actions/runs/37131181771/artifacts/11277590573)
and extract `app-release.apk` (repository access required). This batch includes
filtered ANR stack recovery, stable colors per Mac app instance, failed-switch
recovery, launch pairing precedence/fallback, and pending-picker cancellation.
It includes all build 456 features. PR #1 remains a draft; this is an Actions
artifact, not a published GitHub release.

- Package `io.github.docmorphic.cmuxapp`, version code **463**, version `0.2.0`.
- SHA-256: `85d00b6edeb73f7dd95089e980170aa5de902b97032dd74c187834d5d4c055b3`.
- Signer: `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4` (unchanged).
- APK size: **39,041,158 bytes**; min SDK 26, target SDK 36.
- Local file: `build/signed-run-37131181771/app-release.apk` (ignored).

The full app/Ghostty JVM suites and debug/test/release assembly passed in CI;
Gradle reported **6m 8s**. Four helper tests, ten update-policy tests and the
signed-APK gate passed. No full JVM count is inferred from CI logs. The downloaded
APK matches CI's hash and the artifact's GitHub source/run provenance. Independent
checks confirmed the stable signer, 14 viewer hashes, six native LOAD/RELRO checks,
16 KB ZIP alignment, disabled backup, no debuggable flag, diagnostics Application
and exclusion of all five debug fixture activities. Packaged attribution exactly
matches the source asset.

The existing API 37 / 16,384-byte emulator upgraded **456 → 463** with `install -r`,
preserving first-install time `2026-09-30 01:57:49`. Cold launch reported **1,074 ms**
and reached the visually reviewed sign-in screen without a compatibility warning
or ANR dialog; `pageSizeCompat=0` and the crash buffer was empty. This single
launch is not a benchmark. The baseline was already signed out, so authenticated
migration remains unverified. No additional AVD was created; the emulator was
shut down. Evidence: ignored `captures/runtime/build463/`.

The Pixel was unavailable to ADB. Physical browser/terminal acceptance,
authenticated release migration, configured push, broader native Iroh diagnostics
and the remaining source/visual audit are open. This is a verified development
milestone, not completion of the full iOS parity goal.

## Previous signed development APK — build 456 (2026-10-03)

[Build 456](https://github.com/DocMorphic/cmux-app/actions/runs/37124887007)
passed at `ac3b20db5c4325234255873db2225b944e037c33`. Download the
[signed APK artifact](https://github.com/DocMorphic/cmux-app/actions/runs/37124887007/artifacts/11275051159)
and extract `app-release.apk` (repository access required). This combined build
adds background input ownership, SSH mouse/wheel/focus forwarding, and ordered
SSH image input from keyboards, clipboard and photo selection, with retained
composer drafts and explicit recovery after interrupted input. It includes all
build 448 features. PR #1 remains a draft; this is an Actions artifact, not a
published GitHub release.

- Package `io.github.docmorphic.cmuxapp`, version code **456**, version `0.2.0`.
- SHA-256: `cdc9645ffdd607c59e0e2852eac683516501454b551df3c05b573bbc557bd248`.
- Signer: `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4` (unchanged).
- APK size: **39,024,266 bytes**; min SDK 26, target SDK 36.
- Local file: `build/signed-run-37124887007/app-release.apk` (ignored).

The full app/Ghostty JVM suites and debug/test/release assembly passed in CI;
Gradle reported **5m 49s**. Four helper tests, ten update-policy tests and the
signed-APK gate passed. No full JVM count is inferred from CI logs. The downloaded
artifact's hash matches the CI verifier output; GitHub provenance matches the
source commit. Independent checks confirmed the stable signer, 14 viewer hashes,
six native LOAD/RELRO checks, 16 KB ZIP alignment, disabled backup, no debuggable
flag, diagnostics Application and exclusion of all five debug fixture activities.
Packaged attribution exactly matches its source asset. An additional local check
incorrectly expected the Markdown notice and plain-text asset to be identical;
these have different presentations, and the corrected asset comparison passed.

The existing API 37 / 16,384-byte emulator upgraded **448 → 456** with `install -r`,
preserving first-install time `2026-09-30 01:57:49`. Force-stop/cold launch reported
**1,103 ms** and reached the visually reviewed sign-in screen without a compatibility
warning or ANR dialog; `pageSizeCompat=0` and the crash log buffer was empty.
This single emulator launch is not a benchmark. The baseline was already signed
out, so authenticated migration remains unverified. No additional AVD was created;
the emulator was shut down after verification. Evidence: `captures/runtime/build456/`
(ignored).

The Pixel remains unavailable to ADB. Physical browser/terminal acceptance,
authenticated release migration, configured push, ANR stacks and the remaining
source audit are open. This milestone does not establish full iOS parity.

## Previous signed development APK — build 448 (2026-10-03)

[Build 448](https://github.com/DocMorphic/cmux-app/actions/runs/37117513871)
passed at `b63b875156ed3043d76b63829b05f052dff064e1`. Download the
[signed APK artifact](https://github.com/DocMorphic/cmux-app/actions/runs/37117513871/artifacts/11271728674)
and extract `app-release.apk` (repository access required). This combined build
adds filtered local Java/Kotlin and native crash stacks, recovery from connection
request timeouts, and foreground notification reconciliation on retained terminal
connections. It contains all build 441 features. PR #1 remains a draft; this is an
Actions artifact, not a published GitHub release.

- Package `io.github.docmorphic.cmuxapp`, version code **448**, version `0.2.0`.
- SHA-256: `6a171f57f263a6601c4592cbf4e421c2ba1f731fb2deb7311d338c57cb16adfa`.
- Signer: `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4` (unchanged).
- APK size: **38,991,226 bytes**; min SDK 26, target SDK 36.
- Local file: `build/signed-run-37117513871/app-release.apk` (ignored).

The full app/Ghostty JVM suites and debug/test/release assembly passed in CI;
Gradle reported **4m 51s**. Four helper tests, ten update-policy tests and the
reusable signed-APK gate passed. No full JVM count is inferred from CI logs.
Independent verification of the downloaded artifact confirmed its exact CI hash,
stable signer, all 14 viewer hashes, six native LOAD/RELRO checks, 16 KB ZIP
alignment, disabled backup, no debuggable flag, diagnostics Application and
exclusion of all five debug fixture activities. Packaged attribution matches source.

The existing API 37 / 16,384-byte emulator upgraded **441 → 448** with `install -r`,
preserving first-install time `2026-09-30 01:57:49`. Before the upgrade, emulator
System UI showed a startup ANR; Wait dismissed it and the baseline settled on the
sign-in screen. After upgrade, force-stop/cold launch reported **1,149 ms** and
reached the visually reviewed sign-in screen without a compatibility warning or
ANR dialog; `pageSizeCompat=0`. This single emulator launch is not a benchmark.
The baseline was already signed out, so authenticated migration remains unverified.
No additional AVD was created; the emulator was shut down after verification.
Evidence is retained in ignored `captures/runtime/build448/`.

The Pixel remains unavailable to ADB. Physical acceptance, authenticated release
migration, configured push, ANR stacks and the remaining source audit are open.
This milestone does not establish full iOS parity.

## Previous signed development APK — build 441 (2026-10-03)

[Build 441](https://github.com/DocMorphic/cmux-app/actions/runs/37112575050)
passed at `55e8dde507fe9360ad2cb4fc30661927055e20a1`. Download the
[signed APK artifact](https://github.com/DocMorphic/cmux-app/actions/runs/37112575050/artifacts/11271230339)
and extract `app-release.apk` (repository access required). It includes the
browser capability/fallback handling, live browser workspace inventory and
permissions, iOS-style pane menu icons, bounded recent-terminal text capture,
and one-time SSH key installation with independent key-only verification.
It includes all build 434 features. PR #1 remains a draft; this is an Actions
artifact, not a published GitHub release.

- Package `io.github.docmorphic.cmuxapp`, version code **441**, version `0.2.0`.
- SHA-256: `4113ad56ce9575755d83ab5813327e4ea7225eb3046d5abb4eae375f9d3ce07a`.
- Signer: `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4` (unchanged).
- APK size: **38,958,458 bytes**; min SDK 26, target SDK 36.
- Local file: `build/signed-run-37112575050/app-release.apk` (ignored).

The full app/Ghostty JVM suites and debug/test/release assembly passed in CI;
Gradle reported **7m 46s**. Four helper tests, ten update-policy tests and CI
packaging gates passed. No full JVM count is inferred from the logs. Local
verification of the exact downloaded APK passed with `verify-signed-apk.py`:
stable signer, all 14 packaged viewer hashes, six native LOAD/RELRO checks,
16 KB ZIP alignment, disabled backup, no debuggable flag, diagnostics Application,
and exclusion of five debug fixture activities. The packaged attribution text
also exactly matches source, including the new SSH installation notice.

The existing API 37 / 16,384-byte emulator upgraded **434 → 441** with `install -r`,
preserving first-install time `2026-09-30 01:57:49`. Force-stop/cold launch reported
**631 ms** and displayed the visually reviewed sign-in screen without a
compatibility warning; `pageSizeCompat=0`. This single emulator launch is not a
performance benchmark. Its baseline was already signed out, so this does not
prove authenticated migration. No new AVD was created. Evidence is retained in
ignored `captures/runtime/build441/`; the emulator was shut down afterward.

The Pixel was absent. Physical acceptance, authenticated release migration,
configured push, safe crash stacks and the remaining source audit are open.
This milestone does not establish full iOS parity.

## Previous signed development APK — build 434 (2026-10-03)

[Build 434](https://github.com/DocMorphic/cmux-app/actions/runs/37105849273)
passed at `5bac7f793d8c4f8a0e8596b3fdbc37d46a4d4307`. Download the
[signed APK artifact](https://github.com/DocMorphic/cmux-app/actions/runs/37105849273/artifacts/11267964056)
and extract `app-release.apk` (repository access required). It includes the
shared Mac/tmux/cmux-tui workspace close confirmations, persistent typed
diagnostics, Export/Clear Logs settings, verbose operation-start logging and
Android 11+ crash/ANR summaries. DEBUG-only clipboard log copying remains excluded
from release menus. It includes build 428's SSH/browser features. PR #1 remains
a draft; this is an Actions artifact, not a published GitHub release.

- Package `io.github.docmorphic.cmuxapp`, version code **434**, version `0.2.0`.
- SHA-256: `3f025f1b0ed47be460a369476fdd180ed811718eb5c53fc7a6738b16a6c56c9a`.
- Signer: `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4` (unchanged).
- APK size: **38,916,794 bytes**; min SDK 26, target SDK 36.
- Local file: `build/signed-run-37105849273/app-release.apk` (ignored).

Full app/Ghostty JVM tests and debug/test/release assembly passed; Gradle reported
**7m 24s**. Four helper tests, ten update-policy tests and CI packaging gates
passed. No full JVM count is inferred from CI logs. Independent checks of the
downloaded APK confirmed the stable signer, all 14 packaged viewer hashes, all
six native LOAD/RELRO checks, 16 KB ZIP alignment, disabled backup, no debuggable
flag, the diagnostics Application class, and exclusion of all five debug fixture
activities. An initial local signature invocation ran before the download had
finished and reported a missing file; verification succeeded after completion.

The existing API 37 / 16,384-byte emulator upgraded **428 → 434** with `install -r`,
preserving first-install time `2026-09-30 01:57:49`. The immediate launch overlapped
Android's package-update Activity, so it is not a cold-launch measurement. A
subsequent force-stop/cold launch reported **276 ms** and reached the visually
reviewed sign-in screen without a compatibility warning. This is one launch,
not a performance benchmark. The baseline was already signed out; authenticated
migration remains unverified. The emulator was shut down; no additional AVD was
created. Evidence: `captures/runtime/build434/` (ignored).

The Pixel remained absent. Physical acceptance, authenticated release migration,
configured push, crash stacks and the remaining source audit are open. The picker
capability/fallback gaps newly recorded in [PARITY.md](PARITY.md) are also pending.
This milestone does not establish full iOS parity.

## Previous signed development APK — build 428 (2026-10-03)

[Build 428](https://github.com/DocMorphic/cmux-app/actions/runs/37100622349)
passed at `139dcbe2b889f3cbf5f15860838749c2d2a018d9`. Download the
[signed APK artifact](https://github.com/DocMorphic/cmux-app/actions/runs/37100622349/artifacts/11266620691)
and extract `app-release.apk` (repository access required). It adds grouped SSH
pane menus, creation from the browser, direct-shell browser/workspace actions,
stale browser-return protection and correct restoration of the linked Android
browser page. It includes the previous build's feedback, notification recovery
and terminal features. PR #1 remains a draft; no main-branch preview or GitHub
release was published.

- Package `io.github.docmorphic.cmuxapp`, version code **428**, version `0.2.0`.
- SHA-256: `278076132b3f5aff070e9add70ab798053dd135b22179ea5e31da5b7d2ba3022`.
- Signer: `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4` (unchanged).
- APK size: **38,883,966 bytes**; min SDK 26, target SDK 36.
- Local file: `build/signed-run-37100622349/app-release.apk` (ignored).

Full app/Ghostty JVM tests and debug/test/release assembly passed; the Gradle step
took **7m 31s**. Four helper tests, ten update-policy tests and CI packaging gates
passed. No full JVM test count is inferred from the logs. Independent checks of
the downloaded APK confirmed the stable signer, all 14 packaged viewer hashes,
all six native LOAD/RELRO checks, 16 KB ZIP alignment, disabled backup, no
debuggable flag, and exclusion of all five debug fixture activities.

The single existing API 37 / 16,384-byte emulator upgraded **421 → 428** with
`install -r`, preserving first-install time `2026-09-30 01:57:49`. A force-stop
and cold launch reported **285 ms**, reaching the visually reviewed sign-in
screen without a compatibility warning. This is one emulator launch, not a
performance benchmark. Its baseline was already signed out, so authenticated
migration remains unverified. The emulator was shut down; no additional AVD was
created. Evidence: `captures/runtime/build428/` (ignored).

The Pixel was absent and its debug installation remains unchanged. Physical
acceptance, authenticated release migration, push provider configuration and
the remaining source parity audit are open. The newly documented iOS workspace
close-dialog differences are also pending; this is not a full parity release.

## Previous signed development APK — build 421 (2026-10-03)

[Build 421](https://github.com/DocMorphic/cmux-app/actions/runs/37095448842)
passed at `72a1b10427bbeef973eabcdbc21b925b85fcc981`. Download the
[signed APK artifact](https://github.com/DocMorphic/cmux-app/actions/runs/37095448842/artifacts/11264196689)
and extract `app-release.apk` (repository access required). It adds the shared
pane picker across native and on-device browser views, notification-route retry,
and public feedback with drafts and in-flight submissions retained across Activity
recreation. It also includes build 411's terminal sizing and accessibility work.
PR #1 remains a draft; no main-branch preview or GitHub release was published.

- Package `io.github.docmorphic.cmuxapp`, version code **421**, version `0.2.0`.
- SHA-256: `9140c69beb572b40982046e5a538f315afe14f3dcbb78668fff0a0d39b6425b9`.
- Signer: `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4` (unchanged).
- APK size: **38,834,814 bytes**; min SDK 26, target SDK 36.
- Local file: `build/signed-run-37095448842/app-release.apk` (ignored).

Full app/Ghostty JVM tests and debug/test/release assembly passed; the Gradle step
took **5m 6s**. Four helper tests, ten update-policy tests and CI packaging gates
passed. No full JVM test count is inferred from the logs. Independent checks of
the downloaded APK confirmed the stable signer, all 14 packaged viewer hashes,
all six native LOAD/RELRO checks, 16 KB ZIP alignment, disabled backup, no
debuggable flag, and exclusion of all five debug fixture activities.

The single existing API 37 / 16,384-byte emulator upgraded **411 → 421** with
`install -r`, preserving first-install time `2026-09-30 01:57:49`. The immediate
launch overlapped Android's package-update activity and was not a cold-launch
measurement. A subsequent force-stop/launch reported **353 ms**, reaching the
visually reviewed sign-in screen without a compatibility warning. This is one
emulator launch, not a performance benchmark. Its baseline was already signed
out, so authenticated migration remains unverified. The emulator was shut down;
no additional AVD was created. Evidence: `captures/runtime/build421/` (ignored).

The Pixel was absent and its debug installation remains unchanged. Physical shared
sizing, Gboard/TalkBack, browser workflows and authenticated release migration
remain pending. Push provider configuration and the broader upstream delta audit
are also unfinished; this is not a full iOS parity release.

## Previous signed development APK — build 411 (2026-10-03)

[Build 411](https://github.com/DocMorphic/cmux-app/actions/runs/37089201241)
passed at `28265f88f039a24e22849dbb3d426917a3ed53fa`. Download the
[signed APK artifact](https://github.com/DocMorphic/cmux-app/actions/runs/37089201241/artifacts/11261931650)
and extract `app-release.apk` (repository access required). It includes the
accumulated shared-sizing controls, primary/alternate keyboard presentation,
terminal-image protection and Android accessibility scroll actions described in
[the terminal checkpoint](TERMINAL_SHARED_SIZING.md). PR #1 remains a draft;
this is a CI artifact, with no main-branch preview or GitHub release published.

- Package `io.github.docmorphic.cmuxapp`, version code **411**, version `0.2.0`.
- SHA-256: `94e990751ea1be1c93eaf1c248d113ea7c630319df1f56afc3b58f6419ee1bc5`.
- Signer: `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4` (unchanged).
- APK size: **38,802,046 bytes**; min SDK 26, target SDK 36.
- Local file: `build/signed-run-37089201241/app-release.apk` (ignored).

Full app/Ghostty JVM tests and debug/test/release assembly passed; the Gradle step
took **7m 28s**. Four helper tests, ten update-policy tests and the CI signature,
asset and alignment gates passed. A full JVM test count is not inferred from the
logs. Independent downloaded-APK checks confirmed the stable signer, all 14 viewer
asset hashes, all six native LOAD/RELRO checks, 16 KB ZIP alignment, version/package,
disabled backup and absence of a debuggable flag or debug fixture activities.

The single existing API 37 / 16,384-byte emulator upgraded **397 → 411** using
`install -r`, retaining first-install time `2026-09-30 01:57:49`. A cold launch
reported **236 ms** and reached the sign-in screen, visually inspected without a
compatibility warning. This is one emulator launch, not a performance benchmark.
Its baseline was already signed out, so authenticated migration is still pending.
The emulator was shut down; no additional AVD was created. Evidence is ignored
under `captures/runtime/build411/`.

The Pixel was not connected and its debug installation is unchanged. Live shared
sizing, Gboard/TalkBack and signed-in release migration remain pending. Background
push is still unconfigured. This build does not establish full iOS parity.

## Previous signed development APK — build 397 (2026-10-03)

[Build 397](https://github.com/DocMorphic/cmux-app/actions/runs/37070675174)
passed at `8f1ce4233581b3f1f33154d8f3c468895b7e117f`. Download the
[signed APK artifact](https://github.com/DocMorphic/cmux-app/actions/runs/37070675174/artifacts/11254943027)
and extract `app-release.apk` (repository access required). This supersedes 385
and adds the account/credential deadlock fix exercised in real NIGHTLY pairing.
Later commits add production shared-sizing controls, keyboard layout/presentation
and image protection; those changes are not in build 397. See [current source
verification](TERMINAL_SHARED_SIZING.md). PR #1 remains a draft; no main-branch
preview or GitHub release was published.

- Package `io.github.docmorphic.cmuxapp`, version code **397**, version `0.2.0`.
- SHA-256: `96ce8a68f11dbd93bcb8c36e5fef5fc290c57d628b22d33752d8aa043e7ff69a`.
- Signer: `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4` (unchanged).
- APK size: **38,686,506 bytes**; min SDK 26, target SDK 36.
- Local file: `build/signed-run-37070675174/app-release.apk` (ignored).

Full CI app/Ghostty JVM tests and debug/test/release assembly passed (**7m 25s**),
as did four helper tests, ten update-policy tests, signature and asset/alignment
gates. No full JVM test total is inferred from the CI logs. Independent checks of
the downloaded APK confirmed all 14 packaged viewer hashes, six native LOAD/RELRO
checks, 16 KB ZIP alignment, signer, package/version, disabled backup and absence
of debug fixture activities. The initial local signature command lacked JAVA_HOME;
it was rerun successfully with the configured JDK 17.

The single existing API 37 / 16,384-byte emulator upgraded **385 → 397** using
`install -r`; first-install time remained `2026-09-30 01:57:49`. A cold launch
reached the sign-in screen in **2,202 ms**, visually checked without a compatibility
warning. The baseline was already signed out, so authenticated migration remains
unverified. No new AVD was created; the emulator was shut down afterward.

The physical Pixel retains its signed-in **debug** package with the same production
lock fix. The release package is separate and was not installed on the Pixel.
Real native pairing/browser and checklist UI acceptance are documented below and
in [Todo scope](TODO.md#physical-checklist-ui-acceptance--2026-10-03). Background
push is still unconfigured; this remains a development build, not full completion.
Ignored evidence: `captures/runtime/pixel-resume-20261002/build397-*` and
`signed-lock-fix-ci.txt`.

## Latest physical debug update — 2026-10-03

The Pixel debug package now includes the account/credential deadlock fix from
`1c62eab`, installed with `-r` and existing sign-in preserved. **23 focused JVM tests**
passed. The full real NIGHTLY pairing/browser UI journey also passed **1 test in
35.604 s**, including Back/Forward, workspace return/reopen and fixture cleanup.
Screenshots were inspected; phone sleep setting restored to 0. See
[browser acceptance and limits](BROWSER_TUNNEL.md#physical-pairing-and-browser-ui-acceptance--2026-10-03).
Debug SHA-256: `739142be417613109675d7e39a563b1d959941f7744e85098f8f94f5a2e9c49a`.
Signed build 397 above now includes this fix; the physical release package was not installed.

## Previous signed development APK — build 385 (2026-10-02)

### Physical debug update and resumed check — 2026-10-02

The reconnected Pixel received the current debug and instrumentation APKs with
`adb install -r`; both installations returned Success without clearing data.
Debug SHA-256: `f72f05838b1e441c43bdc154840b3796b4d696f28ec6eaf2297c3435b969e1cb`.
Test SHA-256: `f86df702928d6a0e9cc6b52032a8c1740bed2b0ab70c4351552f22f13ab821c1`.
The authenticated, read-only saved-Mac preflight passed **1 test in 7.758 s**.
The subsequent UI test failed its initial unlocked-device guard in **0.081 s**,
before acquiring the account or creating a workspace. This is not resize evidence;
the settled viewport and nightly pairing checks still need an unlocked Pixel.
Normal plugged-in sleep setting remains `0`. No emulator was started, and the
physical release package was not updated. Ignored logs:
`captures/runtime/pixel-resume-20261002/`.

**Follow-up:** the unlocked Pixel subsequently passed the exact host-grid resize
and reopen check (1 test, 21.902 s): 67×47 → 67×24 with Gboard → 67×47 after
reopening, with screenshots reviewed, login preserved and fixture cleanup verified.
The native Todo mutation/reconnect check also passed (1 test, 13.24 s), using only
its own disposable workspace. See [runtime evidence](NATIVE_RUNTIME_CHECKPOINT.md#settled-pixel-viewport-and-reopen--2026-10-02)
and [Todo scope](TODO.md#physical-native-rpc-acceptance--2026-10-02).
Final installed test APK SHA-256:
`b72aba20ca16be866de2f58b0403c7251ccf815f93706ad50ae109be59daf539`.
Debug production APK remains the hash above; temporary stay-awake was restored to
`0`. Nightly pairing, direct keyboard input and live push remain pending.

### Signed APK verification

**Latest physical retry checkpoint:** a real duplicate input after reconnect now
passes against NIGHTLY (1 test, 10.71 s), with DUPLICATE/APPLIED acknowledgements
and exactly one original output after a shell fence. See
[evidence and limits](NATIVE_RUNTIME_CHECKPOINT.md#physical-identified-input-retry-after-reconnect--2026-10-02).
Final installed instrumentation SHA-256:
`487a41d96b480338d54ed79d51932faef310541fc3ee1e5b844b882e89746ef9`.
The production debug APK and signed build 385 remain unchanged.

**Later NIGHTLY checkpoint:** account discovery/authentication, native browser
HTTP and identified terminal-input acknowledgement now pass on the real Pixel/Mac.
See [native terminal scope](NATIVE_RUNTIME_CHECKPOINT.md#physical-nightly-identified-input-acknowledgement--2026-10-02)
and [browser scope](BROWSER_TUNNEL.md#physical-nightly-discovery-and-native-http--2026-10-02).
Only the instrumentation APK changed, finally to SHA-256
`955a273af1b086832254843f279045c91a88189ece190ebd2cf704e4e9d808dc`.
No nightly pairing was persisted, and no production/release APK was rebuilt.

[Build 385](https://github.com/DocMorphic/cmux-app/actions/runs/37017511051)
passed at `7f8cefdd34811860e268270f96ecbc555dcfb36e`. Download the
[signed APK artifact](https://github.com/DocMorphic/cmux-app/actions/runs/37017511051/artifacts/11231236946)
and extract `app-release.apk` (repository access required). This supersedes 376.
PR #1 remains a draft. This manual integration build did not publish a main-branch
preview or change the installed physical Pixel app.

- Package `io.github.docmorphic.cmuxapp`, version code **385**, version `0.2.0`.
- APK SHA-256: `2d9960dd7222f094be44af7fbec96facecc832e32382d2b93a407d1461bce110`.
- Certificate SHA-256:
  `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4`.
- Downloaded APK size: **38,686,506 bytes**. Signing identity is unchanged.
- Adds selected-terminal preparation to start deferred Mac terminals, explicit
  light system-bar icons on the dark app/browser backgrounds, and push scheduling
  that lets fresh alerts bypass old retries while preserving priority and expiry.

CI passed the full app/Ghostty JVM tasks, debug/test/release assembly, helper and
update-policy tests, viewer source hashes, release signature and native/ZIP gates.
Gradle reported **5m 19s**. The CI artifact does not include individual JVM reports,
so no full-suite test total is inferred. Focused evidence includes the real Pixel
lazy-startup/reconnect, live GRID/native input lane and composer/reopen checks,
seven emulator startup UI checks, and the final six local push scheduling/ingress
checks. See `NATIVE_RUNTIME_CHECKPOINT.md` and `PUSH_DELIVERY.md` for exact scope.

Independent downloaded-APK checks confirmed **14 packaged viewer hashes**, all
**six native LOAD/RELRO checks**, 16 KB ZIP alignment, the unchanged signer,
expected version/package, non-debuggable manifest, disabled backup and absence of
four debug fixture activities. Firebase initialization/analytics/delegation remain
disabled; the receiver is nonexported and the SDK fallback display service absent.
**Live push is not configured.** Token registration and a sender remain required.

The existing API 37 / arm64 / 16,384-byte emulator upgraded **376 → 385** with
`adb install -r`, without clearing data. Both installed APK hashes matched their
downloads. First-install time remained `2026-09-30 01:57:49`, and `pageSizeCompat=0`.
The first post-update launch returned an existing Activity and showed a blank
frame; it is not counted as cold-start evidence. After installation completed,
a fresh force-stop/launch reached MainActivity in **2,139 ms**. Its sign-in screen
was visually checked without a compatibility warning. This is emulator launch
timing, not physical connection performance. The emulator is stopped.

The baseline was already signed out, so this does not prove authenticated
migration. The release package is separate from `cmux (debug)`; installing it
does not transfer the debug app's login. Settled physical keyboard resize, newer
host browser/identified-input support, live push and broader physical/UI parity
remain open. Mac UI capture failed during this checkpoint, so nightly Mobile
listener readiness was not reverified. No account code was requested.

Local APK: `build/signed-run-37017511051/app-release.apk` (ignored). Receipt, CI
log, package checks and screenshots: `captures/releases/7f8cefd/` (ignored). The
older tailnet download was not replaced.

<a id="current-signed-development-apk--build-376-2026-10-02"></a>

## Previous signed checkpoint — build 376 (2026-10-02)

[Build 376](https://github.com/DocMorphic/cmux-app/actions/runs/36996321428)
passed at `420327d855d9521b4117c02bd82cac0cbb631a59`. Download the
[signed APK artifact](https://github.com/DocMorphic/cmux-app/actions/runs/36996321428/artifacts/11221887084)
and extract `app-release.apk` (repository access required). This supersedes 369.
PR #1 remains a draft; this manual batch build did not publish a main-branch preview.

- Package `io.github.docmorphic.cmuxapp`, version code **376**, version `0.2.0`.
- APK SHA-256: `bca595537def20de5bb074d7753a81b7290ad9d5b57c80fd3e361ff1c4de16ce`.
- Certificate SHA-256:
  `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4`.
- Downloaded APK size: **38,686,506 bytes**. Signing identity is unchanged.
- Includes all of 369 plus preserved SFTP mutation outcomes after refresh failures,
  encrypted FCM queue/receiver and fresh-membership delivery checks, scrollable
  simulator recovery content, separate 48 dp toolbar targets and consistent
  disabled input during simulator-worker failures.

**Live FCM push is not activated.** Firebase automatic initialization and token
registration remain disabled. A Firebase project and sender must be configured;
see [the delivery decision and current receiver](PUSH_DELIVERY.md).

CI passed the full app/Ghostty JVM tasks, debug/test/release assembly, four helper
tests, ten update-policy tests, viewer hashes, signature and native/ZIP alignment
gates. Gradle completed in **7m 53s**; individual JVM totals are not published, so
none are inferred. Focused runtime evidence includes 12 push checks, nine SFTP
checks and the final ten v2/legacy simulator checks, recorded in their feature
documents. Those checks are fixtures, not physical end-to-end acceptance.

Independent downloaded-APK checks confirmed all **14 packaged viewer hashes**,
all **six native LOAD/RELRO checks**, 16 KB ZIP alignment, the unchanged signer,
package/version, non-debuggable manifest, disabled backup and absence of four
debug fixture activities. The manifest also preserves disabled Firebase automatic
initialization/analytics/delegation, the nonexported receiver and removal of the
SDK fallback display service.

The existing API 37 / arm64 / 16,384-byte emulator upgraded **369 → 376** with
`adb install -r`, without clearing data. Both installed APK hashes matched the
corresponding downloads. First-install time remained `2026-09-30 01:57:49`, and
`pageSizeCompat=0`. The first launch request overlapped Android's package-update
Activity; a subsequent force-stop/cold launch reached the actual MainActivity in
**991 ms**. Its sign-in screen was visually checked with no compatibility warning.
This is launch timing, not connection latency. The emulator was stopped.

The baseline was signed out; authenticated migration and the latest physical
Pixel/Mac workflow remain unverified. No account email was sent. Live push-provider
delivery, broader physical/UI acceptance and full parity remain open.

Local APK: `build/signed-run-36996321428/app-release.apk` (ignored). Receipt, CI
log, package checks and before/after screenshots: `captures/releases/420327d/`
(ignored). The older tailnet download was not replaced.

<a id="current-signed-development-apk--build-369-2026-10-02"></a>

## Previous signed checkpoint — build 369 (2026-10-02)

[Build 369](https://github.com/DocMorphic/cmux-app/actions/runs/36987796472)
passed at `59f279cbed609ad73c255bb192f2c02dbe2f14be`. Download the
[signed APK artifact](https://github.com/DocMorphic/cmux-app/actions/runs/36987796472/artifacts/11218422694)
and extract `app-release.apk` (repository access required). This supersedes 363.
PR #1 remains a draft; this manual batch build did not publish a main-branch preview.

- Package `io.github.docmorphic.cmuxapp`, version code **369**, version `0.2.0`.
- APK SHA-256: `eb9dac571fefbd2a7bfc6f55679f5db4933fe92a1296754fd3f843db824f7795`.
- Certificate SHA-256:
  `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4`.
- Downloaded APK size: **38,068,600 bytes**. Signing identity is unchanged.
- Includes all of 363 plus browser provider/Chrome/desktop restart recovery,
  visible workspace recovery errors, preserved unconfirmed-delivery warnings,
  cancelled-resize recovery and pointer-readiness gating.

CI passed the full app and Ghostty JVM tasks, debug/test/release assembly, four
helper tests, ten update-policy tests, viewer hashes, signature and native/ZIP
alignment gates. The Gradle step took **7m 42s**. This workflow does not publish
individual JVM totals; no total is inferred. The source's six real SSH/cmux-tui/
Chrome integration cases passed separately in **94.736 seconds** on API 37 / 16 KB;
see [the focused evidence and limits](DIRECT_SSH.md#lost-click-replies-resize-cancellation-and-pointer-readiness-2026-10-02).

Independent downloaded-APK checks confirmed all **14 packaged viewer hashes**,
all **five native LOAD/RELRO checks**, 16 KB ZIP alignment, the expected signer,
version and package, a non-debuggable manifest with backup disabled, and absence
of the four debug fixture activities from the release manifest.

The existing API 37 / arm64 / 16,384-byte emulator upgraded **363 → 369** with
`adb install -r`, without clearing data. The installed hashes before and after
matched their respective releases. The first-install time remained
`2026-09-30 01:57:49`, and `pageSizeCompat=0`. A cold launch reached the actual
MainActivity in **615 ms**; the sign-in screen was visually checked with no
compatibility warning. This is launch timing, not connection latency. The single
existing emulator was stopped afterward; no new virtual device was created.

The baseline was signed out, so this does not verify authenticated session
migration. No physical Pixel was connected, and no account email was sent.
Physical account/Mac acceptance and live Android push-provider delivery remain
open. This is a development checkpoint, not a completed parity release.

Local APK: `build/signed-run-36987796472/app-release.apk` (ignored). Receipt,
CI log, packaging checks, before/after package/hash records and screenshots:
`captures/releases/59f279c/` (ignored). The older tailnet download was not replaced.

## Previous signed checkpoint — build 363 (2026-10-02)

[Build 363](https://github.com/DocMorphic/cmux-app/actions/runs/36979580273)
passed at `47f63aa872765960a0e7778e3fef361271b2aa2c`. Download the
[signed APK artifact](https://github.com/DocMorphic/cmux-app/actions/runs/36979580273/artifacts/11215555782)
and extract `app-release.apk` (repository access required). This supersedes build
284 at that checkpoint; build 369 above is now newer. PR #1 remains
a draft; this was a manual batch build, not a main-branch preview publication.

- Package `io.github.docmorphic.cmuxapp`, version code **363**, version `0.2.0`.
- APK SHA-256: `d75f4ee2df24fb833011332df491a710e05d7915ba6a24dace223cf6c601e0f4`.
- Certificate SHA-256:
  `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4`.
- Downloaded APK size: **38,068,600 bytes**. The signer matches earlier releases.
- Includes the accumulated browser/SSH recovery, notification dismissal,
  encrypted push/reply handling and scheduling, foreground presentation/badges,
  account deletion/restoration, and right-Alt/dead-accent input fixes. Push ingress
  and reply code are present; a live Android push provider is still unconfigured.

CI passed both full `:ghostty:testDebugUnitTest` and `:app:testDebugUnitTest` tasks,
debug/test/release assembly, four helper tests, ten update-policy tests, source
viewer hashes, signature verification and native/ZIP alignment gates. The app
test/build step completed in **7m 37s**. Individual JVM totals are not published
by this workflow, so no count is inferred from earlier focused runs.

Independent checks of the downloaded signed APK confirmed the package/version,
non-debuggable manifest with backup disabled, all **14 packaged viewer hashes**,
all **five native LOAD/RELRO** checks and 16 KB ZIP alignment. The recent account,
reply and keyboard feature classes are present; the four debug fixture activities
are absent from both the manifest and DEX classes.

The existing API 37 / arm64 / 16,384-byte emulator upgraded **284 → 363** with
`adb install -r`, without clearing data. Before installation, the installed 284
hash matched its recorded release. After installation, the installed base APK
matches the downloaded 363 hash; the first-install timestamp is unchanged and
`pageSizeCompat=0`. The first immediate launch overlapped Android's package-update
Activity. A subsequent force-stop/cold launch reached the actual MainActivity in
**1,235 ms**, and its sign-in screen was visually inspected with no compatibility
warning. This is launch timing, not connection latency. The emulator was stopped.

The baseline was signed out, so this upgrade does not establish authenticated
session migration. No physical Pixel was available, no phone app/data was changed,
and no account email was sent. Physical account/Mac use, native notifications and
live provider delivery remain open. The full parity goal is not complete.

Local APK: `build/signed-run-36979580273/app-release.apk` (ignored). Verification
receipt, CI log, before/after package/hash records and screenshots are under
`captures/releases/47f63aa/` (ignored). The older tailnet download was not replaced;
use the artifact link above for this signed checkpoint.

## Current installed checkpoint — isolated browser integration (2026-09-30)

Debug production `9efef27` was installed in place on the connected Pixel. The
installed APK was pulled back and its SHA-256 matches the emulator-tested build:
`ea82c343fdcfba536d18859b991c1b461e13b105730ebf82dc7cc46ec9cde70e`.
This also delivers the earlier direct-keyboard focus and event-lane fixes. Their
physical UI retest remains open. No app data was cleared or sleep setting changed.

An explicitly selected, opt-in `LiveNativeBrowserCheck` passed in **5.768 s**
using the existing sign-in and the single saved Iroh Mac. It verified matching
Mac identity and authenticated workspace-list access. The transport reports
`LAN or Private VPN` and native browser-lane support on the Android side.
The live Mac **does not advertise `browser.tunnel.v1`**, so no browser listing or
TCP-connect operation was attempted. This is a verified host capability gap,
not evidence of a working physical browser tunnel.

The installed Mac is cmux **0.64.25 / build 106**, also the latest stable GitHub
release when checked. The official nightly `3669077704801` was subsequently
signature-verified, staged separately and launched with session restoration
disabled. Its live account/pairing/capability verification is still pending; see
[BROWSER_TUNNEL.md](BROWSER_TUNNEL.md). The stable Mac installation was unchanged.

The opt-in check refreshes the account/team and opens the ordinary native
connection; its Mac operations are reads only. It neither clears account stores
nor changes pairings/workspaces, and sends no terminal input. Ordinary CI skips
it unless `cmux_live_read_only=true` is explicitly supplied on a physical device.
To target the separate nightly, also supply `cmux_live_build=nightly`; an absent
or ambiguous matching saved pairing fails without selecting a different build.
Its output is limited to capability booleans/counts and a fixed route label.
Ignored evidence: `captures/runtime/browser-webview/pixel-live-*`, with the pulled
APK at `pixel-installed.apk`. The debug app was reopened after instrumentation.
Signed release 284 remains unchanged.

## Previous installed checkpoint — identified input integration

Debug `c763e2d` was installed in place on the USB-connected Pixel on 2026-09-30.
Its installed APK hash matches the tested local APK:
`1dc620de99ee7ea5646c1be581f7742e889414a6b2ed267e0c402cfdb21267cd`.
No app data was cleared. The phone was initially locked; a subsequent unlocked
check verified live input and saved-state recovery as recorded below. Host
capability and identified-delivery fault acceptance remain open. Install evidence
is in ignored `captures/runtime/input-delivery/session/pixel-install.txt`.

Before installation, 97 focused JVM and nine Android emulator runtime checks
passed; see [TERMINAL_INPUT_DELIVERY.md](TERMINAL_INPUT_DELIVERY.md). The emulator
was stopped afterward. Signed build 284 below is now the latest signed delivery.

## Physical c763e2d input and recovery check — 2026-09-30

The existing signed-in debug app loaded three workspaces. Only the dedicated
`PixelTapAcceptance` terminal received input. Composer submission of
`echo cmux_pixel_c763_composer_1130` and direct typing of
`echo cmux_pixel_c763_direct_1130` displayed the expected output and shell prompt.

An initial app-only process kill immediately after Home (PID 10390 → 27075)
reconnected to the workspace list without restoring the selected terminal. This
attempt did not first verify Android had saved the Activity state. The repeat
explicitly checked `state=STOPPED` and `mHaveState=true`, then killed only the
debug app's own PID through `run-as` (27075 → 27356). The existing task displayed
Restoring workspace and automatically returned to the same terminal and history,
without signing in again. This verifies Android task saved-state restoration,
not durable selection recovery after an arbitrary unsaved foreground crash.

One ADB text burst sent immediately after switching from Compose to direct input
lost the leading `ech`, resulting in `o cmux_pixel_c763_reconnected` and a shell
command-not-found error. A repeat after UI Automator confirmed the direct input
view was focused sent `echo cmux_pixel_c763_after_focus` successfully. Preserve
this timing observation for a focused mode-transition/hardware-key test; it does
not establish an RPC delivery failure or prove the transition race resolved.
The subsequent focus-handoff fix reproduces this gap in an Android regression
and passes both that test and the broader keyboard regression. See
[TERMINAL_INPUT_DELIVERY.md](TERMINAL_INPUT_DELIVERY.md). The fixed APK has not
been installed here; repeat the phone transition before closing this observation.

No app data was cleared, no account fixtures were run, and no workspace was
created or removed. No new APK was installed during this check. Plugged-in
stay-awake was temporarily `2` and restored to the original `0`. Ignored local
screenshots and lifecycle evidence: `captures/runtime/pixel-20260930-input/`.
These checks do not prove the host advertises identified input, deduplicates
retries, or supports the new browser tunnel capability. The later retained
composer fix, event-lane fixes and tunnel foundation are not in this installed APK.

## Previous installed checkpoint — 2026-09-30

After signed 274 verification, the Pixel appeared in ADB again. Debug app
`1d5958f` was installed in place over `5d24946`, without clearing app data.
The installed `base.apk` SHA-256 matches the tested local build:
`e62b09d6cf6c5272e2ab6326b6fbc50cb6031e12416949f94571451dc7859c4d`.
Install/hash evidence is in ignored `captures/runtime/pixel-1d5958f/`.
The phone was subsequently unlocked and passed the native checks below. Its
plugged-in stay-awake setting was temporarily set to `2`, then restored to `0`.
This installs the debug package; signed packages below remain separate.

The Android Iroh/V2 connection is implemented and earlier checkpoints have real
Pixel/Mac evidence; see [IROH_V2.md](IROH_V2.md) and [NETWORKING.md](NETWORKING.md).
This replaces the old statement that Iroh integration was unfinished.

## Physical native recovery check — 2026-09-30

The installed debug `1d5958f` connected to cmux 0.64.25 on the Mac using the
existing account and loaded all three workspaces. In the existing acceptance
workspace (identified by `PixelTapAcceptance`), the composer submitted
`echo cmux_pixel_recovery_20260930`; its output and the next shell prompt were
visually verified.

After backgrounding the app, `am kill` left its process alive. Killing only the
debug app's own PID through `run-as` changed PID **20855 → 27776** on relaunch.
The existing task displayed Restoring workspace, then automatically returned to
the same terminal and history without signing in again. The launch command
reported COLD, 734 ms; this measures Activity launch, not completed reconnection.
Direct keyboard input then executed `echo cmux_pixel_direct_after_recovery` and
showed its result. The original marker still appeared once as output; this is
visual recovery evidence, not proof of exactly-once delivery during packet loss.

An absolute README path printed in that terminal was detected as one file.
Files showed `README.md`, fetched its 5,644 bytes from the Mac, and visibly
rendered its Markdown. No account-clearing instrumentation was used, no personal
terminal received input, and no Mac workspace was created or removed. The app
was returned to the test terminal; plugged-in stay-awake was restored to `0`.
Screenshots are retained locally in ignored
`captures/runtime/pixel-1d5958f/live/`.

These checks cover the debug APK's live input, process recovery and one Markdown
file. They do not verify signed 274's account workflow, the newer input delivery
foundation, all file formats, full notifications, or complete iOS parity.

## Signed input-delivery build 284 — 2026-09-30

[Build 284](https://github.com/DocMorphic/cmux-app/actions/runs/36691406643)
passed at `0db3c178d69facb8468d62e6bca42e951d34c2b1`. Download the
[stable signed APK artifact](https://github.com/DocMorphic/cmux-app/actions/runs/36691406643/artifacts/11086635606)
and extract `app-release.apk` (repository access required). This is the newest
verified signed development APK at that checkpoint, superseding 274; build 363
above is now newer.

- Package `io.github.docmorphic.cmuxapp`, version code **284**, version `0.2.0`.
- APK SHA-256: `1482af3e6c56ab75643371ac6edef3ee757cf61565ff3e4f6d073c089fca46cd`.
- Certificate SHA-256:
  `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4`.
  The downloaded APK independently passes signature verification and matches the
  previous stable signing identity.
- Includes identified input protocol/ACK lanes, retained delivery ownership,
  bounded retry and explicit recovery, plus composer/image settlement across
  Activity recreation. Older hosts retain their legacy input behavior. See
  [TERMINAL_INPUT_DELIVERY.md](TERMINAL_INPUT_DELIVERY.md) for the separate
  synthetic-peer and native-lane runtime evidence.
- Full CI app/Ghostty JVM tasks, helper tests, viewer hashes, all APK builds,
  signing, native LOAD/RELRO and ZIP alignment gates passed. All five downloaded
  native ELFs and ZIP 16 KiB alignment were independently rechecked locally.
- Installed successfully over signed **274** on the Android 17 / API 37 emulator.
  `PAGE_SIZE=16384`, `arm64-v8a`, version 284 and `pageSizeCompat=0` were confirmed.
  COLD Activity launch reported **1,683 ms** (not a reconnect benchmark). The
  sign-in screen was visually inspected without a compatibility warning. The
  owned emulator was stopped afterward.
- This signed package’s native account/Mac workflow and physical Pixel acceptance
  remain pending. The Pixel still has debug `c763e2d`; account-clearing tests never
  ran there. Event-lane ownership (`0bf1707`) and optional-event recovery
  (`1d3319f`) postdate this APK and are being accumulated for the next build.

Local APK: `build/signed-run-36691406643/app-release.apk` (ignored).
Evidence: `captures/runtime/signed284/` (ignored). Stable/debug packages keep
separate data and sign-ins. The older tailnet download was not replaced; use the
artifact above for 284. Complete iOS parity is still unverified.

## Signed integration build 274 — 2026-09-30

[Build 274](https://github.com/DocMorphic/cmux-app/actions/runs/36681430449)
passed from `1d5958fe9982606f7aefaab514bbf02ae5221343`. Download the
[cmux-app-stable-signed-apk artifact](https://github.com/DocMorphic/cmux-app/actions/runs/36681430449/artifacts/11082157032)
and extract `app-release.apk` (repository access required). This is the newest
verified signed development APK at that checkpoint, superseding 261; 284 is now newer.

- Package `io.github.docmorphic.cmuxapp`, version code **274**, version `0.2.0`.
- SHA-256: `a20471ffccbb09fc808b11fe630a4073b8a5f825979c13b266bb7b614795e396`.
- Certificate SHA-256:
  `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4`.
  It matches the previous stable builds; signing settings are unchanged.
- Includes remembered-pane/default selection, new-terminal startup deadlines,
  ordered workspace refresh, empty/delayed pane discovery, Activity and real
  process-death restoration, and consumed pairing/notification entry routes.
  See [WORKSPACE_SELECTION.md](WORKSPACE_SELECTION.md) and
  [ENTRY_ROUTES.md](ENTRY_ROUTES.md) for the separate runtime batches.
- CI passed the complete app/Ghostty JVM tasks, four helper tests, viewer asset
  checks, debug/instrumentation/release builds, signature and native/ZIP checks.
  The downloaded APK independently passed `apksigner`, all five native libraries'
  LOAD/RELRO checks and `zipalign -c -P 16`.
- Installed successfully over signed 261 on the API 37 / Android 17 emulator with
  16,384-byte pages. Version 274, `arm64-v8a` and `pageSizeCompat=0` were confirmed.
  The sign-in screen was visually inspected without a compatibility warning.
  `am start -W` reported successful WARM launch, total time 1,060 ms. This is
  upgrade/launch evidence; native sign-in and Mac workflows remain untested on
  this signed package. The owned emulator was stopped afterward.
- Signed 274 was not installed on the Pixel. The debug package was subsequently
  updated in place when USB reappeared; see the current checkpoint above.
  Nested file/detail state, unacknowledged creation, full push,
  broader phone acceptance and the new upstream audit remain open. See
  [UPSTREAM_REFRESH_2026_09_30.md](UPSTREAM_REFRESH_2026_09_30.md).

Local verified APK: `build/signed-run-36681430449/app-release.apk` (ignored).
Evidence: `captures/runtime/signed274/` (ignored). Stable and debug installations
have separate app data and logins. The older tailnet download was not updated in
this checkpoint; use the artifact above for 274.

## Signed integration build 261 — 2026-09-30

[Build 261](https://github.com/DocMorphic/cmux-app/actions/runs/36664427496)
passed from `3b0f6fdb1a9a3bb262f66210cbe156714f7bb1a1`. Download the
[cmux-app-stable-signed-apk artifact](https://github.com/DocMorphic/cmux-app/actions/runs/36664427496/artifacts/11075149281)
and extract `app-release.apk` (repository access required). This is the newest
verified signed development APK at that checkpoint; 274 above is now newer.

- Package `io.github.docmorphic.cmuxapp`, version code `261`, version `0.2.0`.
- SHA-256: `72aaf2600c348557dd4f1c6a7b0402eb459a723e1577fc4766f3ee7d0aee9781`.
- Certificate SHA-256:
  `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4`.
  It matches 157/244/248/257; signing settings are unchanged.
- Adds the phone-local browser, iOS address resolver, WebView controls, persistent
  cookies/storage, popup/POST handling and page recovery. `New Browser` creates
  a Mac panel when supported and otherwise opens the local fallback. Workspace
  restoration, scoped ownership and cancellation are included.
- CI passed app/Ghostty JVM suites, all four helper tests, viewer hashes, APK
  build/signature and native/ZIP checks. The downloaded APK independently passed
  `apksigner`, all five native libraries' LOAD/RELRO checks and `zipalign -c -P 16`.
- Installed over the previous stable package on Android 17's 16 KiB emulator;
  version `261`, `arm64-v8a`, and `pageSizeCompat=0` confirmed. The sign-in screen
  was visually inspected with no compatibility warning. This establishes package
  launch, not native account or live Mac acceptance. Emulator stopped afterward.
- Browser runtime evidence includes five full-screen RPC navigation tests and
  three additional system-picker/Activity-recreation tests on the same production
  debug code; see [LOCAL_BROWSER.md](LOCAL_BROWSER.md). The test-only follow-up
  did not alter the debug APK hash and does not require another signed build.
- No Pixel install: it remains absent from ADB. Physical Mac/Pixel, full push/Doze,
  accessibility and remaining navigation acceptance are still open. This is not
  the completed parity release.

Local verified download: `build/signed-run-36664427496/app-release.apk` (ignored).
Launch evidence: `captures/runtime/signed261/` (ignored).
Stable and debug packages have separate app data and logins.

## Signed integration build 257 — 2026-09-30

[Build 257](https://github.com/DocMorphic/cmux-app/actions/runs/36659644624)
passed from `9753083aa8b825f886a3c6ad83e186ddc823d0a7`. Download the
[cmux-app-stable-signed-apk artifact](https://github.com/DocMorphic/cmux-app/actions/runs/36659644624/artifacts/11073628314)
and extract `app-release.apk` (repository access required). This supersedes 248
at that checkpoint; build 261 above is now newer.

- Package `io.github.docmorphic.cmuxapp`, version code `257`, version `0.2.0`.
- SHA-256: `1cc38dab71c2a2ac1f1189170bcd33e04d65de95dac6930e94f3d7767c684e49`.
- Certificate SHA-256:
  `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4`.
  It matches builds 157/244/248; signing settings are unchanged.
- Adds both Simulator modes: native AVC/HEVC streaming with quality/device/
  recovery controls, and legacy PNG/JPEG streaming with ownership and input.
  Includes serialized lifecycle/mode transitions and workspace navigation.
- The full app/Ghostty JVM suites, four helper tests, pinned viewer hashes, APK
  builds, signature and native/ZIP alignment gates passed in CI. The local
  Simulator milestone passed 35 focused JVM tests and eight Android 17/16 KiB
  runtime tests; see [SIMULATOR_STREAMING.md](SIMULATOR_STREAMING.md).
- The downloaded APK independently passes `apksigner`, all five native libraries'
  LOAD/RELRO checks and `zipalign -c -P 16`. Its certificate and version were
  checked on this Mac.
- Installed and launched on the Android 17 16 KiB emulator; version 257 and
  `pageSizeCompat=0` confirmed. Sign-in screen visually inspected without a
  compatibility warning. This verifies launch, not signed native-account or
  live Simulator acceptance. Ignored evidence: `captures/runtime/signed257/`.
- The Pixel remains absent from ADB. No physical install or settings changes.
  Phone-local browser fallback, full push/Doze behavior and remaining physical
  and UI acceptance are still open; this is not the completed parity release.

Local verified download: `build/signed-run-36659644624/app-release.apk` (ignored).
Stable and debug packages have separate app data and logins.

## Signed integration build 248 — 2026-09-30

[Build 248](https://github.com/DocMorphic/cmux-app/actions/runs/36650296593)
passed from `ecdccb0710e661862365fcf7ed979653f406f44e`. Download the
[cmux-app-stable-signed-apk artifact](https://github.com/DocMorphic/cmux-app/actions/runs/36650296593/artifacts/11069984729)
and extract `app-release.apk` (repository access required). This supersedes 244
at that checkpoint; build 257 above is now newer.

- Package `io.github.docmorphic.cmuxapp`, version code `248`, version `0.2.0`.
- SHA-256: `40c4f83a375b93aa438bfd8bd8bbfeee88cf17adabefb31376bfe104e7519193`.
- Certificate SHA-256:
  `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4`.
  It matches the stable certificate; no signing settings changed.
- Includes terminal zoom, native file/Markdown panels and native Todo controls,
  in addition to build 244's Ghostty renderer and configurable toolbar.
- CI's full app/Ghostty JVM suites, helper tests, pinned viewer hashes, APK
  builds, release signature and native/ZIP alignment gates passed.
- The downloaded APK independently passes `apksigner` signature verification,
  native LOAD/RELRO checks and `zipalign -c -P 16` on this Mac.
- Installed and launched on Android 17's 16 KiB emulator. It reports version 248
  and `pageSizeCompat=0`; the sign-in screen was visually inspected without a
  compatibility warning. This proves launch, not signed native-login/terminal
  acceptance. Local evidence: `captures/runtime/signed248/launch.png`.
- No Pixel installation: ADB still shows no physical phone. The debug app and
  stay-awake setting on the Pixel remain unchanged.
- Does not include the later Simulator work. Build 257 above includes both
  Simulator viewers; full parity remains incomplete.

Local download: `build/signed-run-36650296593/app-release.apk` (ignored).
Stable and debug packages have separate app data and logins.

## Signed integration build 244 — 2026-09-30

[Build 244](https://github.com/DocMorphic/cmux-app/actions/runs/36645287501)
completed successfully from `3c806083a37a53b24a3662609f6c5be562029702`.
Download its
[cmux-app-stable-signed-apk artifact](https://github.com/DocMorphic/cmux-app/actions/runs/36645287501/artifacts/11068798967)
and extract `app-release.apk`. Repository access is required. This replaces build
157 at that checkpoint; build 248 above is now newer.

- Package `io.github.docmorphic.cmuxapp`, version code `244`, version `0.2.0`.
- APK SHA-256:
  `0710c86a9f003310051ce84c6aea8f33f4e8f50106fe1cfd54203ad92e429d18`.
- Signing certificate SHA-256:
  `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4`,
  matching the existing stable release certificate. No signing changes.
- The full app/Ghostty JVM suites, helper tests, viewer hashes, APK builds,
  release signature, native LOAD/RELRO and ZIP alignment gates passed in CI.
- Downloaded APK independently verified with `apksigner`, `aapt`, the native
  alignment verifier, and `zipalign -c -P 16` on the Mac.
- Installed and launched on the Android 17 16 KiB emulator; package reports
  `pageSizeCompat=0`. The sign-in screen renders without a page-size warning.
  This is launch evidence, not signed-build account/terminal acceptance.
- Includes native Ghostty/GVI2 graphics and the configurable terminal toolbar.
  The later per-view zoom and Mac panel work are **not** in this artifact.
- No Pixel install: the physical phone remains absent from ADB and the Mac USB
  inventory. The installed Pixel debug checkpoint above remains unchanged.

Local verified download: `build/signed-build-244/app-release.apk` (ignored).
The stable and debug apps have separate data; installing this package does not
upgrade `cmux (debug)` or transfer its login.

## Native account workflow

1. Install the current debug APK as an in-place update to **cmux (debug)**.
   Its package is `io.github.docmorphic.cmuxapp.debug`. Keep the existing account
   state; do not uninstall it or run account-clearing fixture tests on the Pixel.
2. On the Mac, sign in to cmux, then open **Settings → Mobile** and enable
   **iOS pairing**. This listener also serves the Android native companion.
3. Open **cmux (debug)** on the Pixel and sign in with the same cmux account/team
   if prompted. Select the Mac in Computers. The normal account route uses Iroh;
   a Tailscale QR is not required for this workflow. This Mac version's active
   panel may show no QR despite retaining a Show Tailscale QR label.
4. Verify the workspace list and terminal rendering. Use a dedicated test
   workspace for input acceptance, then check resize, scrollback, reconnect,
   browser panels and notifications. Preserve personal terminals and account data.
5. For background alerts, enable **Settings → Background notifications** and
   grant Android notification permission if prompted. An ongoing cmux connection
   notification is expected. Full Doze/boot behavior is still an acceptance item.

Tailscale-only routes are a separate, explicitly authorized per-computer option;
see [TAILSCALE_CONNECTION.md](TAILSCALE_CONNECTION.md) for prerequisites and the
remaining physical VPN checks. A debug APK and a release APK use separate signing
and package identities; do not replace signing keys to work around an install error.

The old Mac helper path remains available from the Android sign-in and Settings
screens for comparison. Its private `cmux-app://pair` URL contains terminal
access credentials and must be kept private. The native QR contains a route
and account identity but does not contain an access token.
