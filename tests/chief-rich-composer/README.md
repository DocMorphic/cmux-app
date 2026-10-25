# Native rich composer observations

Cases.json defines eleven synthetic public-input/source-control scenarios.
NativeObservations.json projects the accepted original UIKit capture at commit
60e3fcef609d6863bfb2615c2cc0190a872e4b8a, run 40157384521 / artifact 12585616903.
It contains 141 shared semantic states and 282 display profiles from actual Large
and Accessibility Medium on iOS 26.4.1 (23E254a).

The raw archive is local/ignored. Its 525,195 bytes hash to the service digest
7d3316fe6bc0a886878ccccdd19c3fdaff69caab690d467ed9fd2086afb2a5e5.
All 24 canonical files were independently reproduced with a six-file source
snapshot read from the immutable native commit. Projection provenance binds
the source tests, case fixture, verifier/projector, artifact and 22 payloads.

The shared records contain only equal text/formatting/input/control-selection/
Store observations. Font/layout/window properties remain per category. Two
Large initial open records have an unattached palette window; the next toggle
records are attached. The explicit differences are retained; no cause, stable
visibility, pixel/animation or keyboard/gesture acceptance is inferred.

Replay against the accepted local evidence (use --output only to regenerate):

```sh
python3 scripts/project-ios-chief-rich-composer.py \
  --canonical captures/recovery-20261025/rich-composer-native-v1-independent \
  --source-root captures/recovery-20261025/rich-composer-native-v1-source \
  --project-commit 60e3fcef609d6863bfb2615c2cc0190a872e4b8a \
  --run-id 40157384521 --artifact-id 12585616903 \
  --artifact-zip captures/recovery-20261025/rich-composer-native-v1.zip \
  --artifact-sha256 7d3316fe6bc0a886878ccccdd19c3fdaff69caab690d467ed9fd2086afb2a5e5
```

The projector validates every payload and receipt hash, refuses to collapse
different semantic states, preserves display differences and checks exact
output bytes. Raw native source snapshots and canonical exports are required;
the committed projection alone is not a new native run.

This uses the original controller/Store and public UIKit APIs, with a recorded
temporary UIButton bridge to the source menu UIAction. Its backend is authored,
in-memory rejection/plain ACK. It does not execute Chief's adapter or prove
actual IME/hardware keyboard, gestures, rendered pixels, Android integration
or physical/full app parity. See [CHIEF_RICH_TEXT.md](../../docs/CHIEF_RICH_TEXT.md).

## Separate live-font controller observations

FontCases.json contains four cases / 44 public actions. Run 40171337742 at 96c52a8e
passes both actual system categories, with public controller trait overrides while
the original editor stays mounted. Artifact 12590397040 matches service SHA256
332233c17389359a2655e3eb439afbae7fd6836f0bf72520343c8a5fc0c0d5ce. The exact
nine-file native source snapshot independently reproduces all ten canonical files.
Replay verify-ios-chief-rich-composer.py --font-cases from that exact snapshot;
its separate fixture/provenance key and probe marker prevent accepting transaction
exports as font observations. Categories and actual span fonts are recorded
without prescribing semantic outcomes. Existing accepted transaction projection
is unchanged. See CHIEF_RICH_TEXT.md for observations and important scope limits:
per-controller overrides retain a fixed global application category and do not
prove live global Settings, rendered pixels, physical input or Android behavior.

## Verified mounted global system-font observations

GlobalFontCases.json contains four cases / 49 actions. Original run 40175245243 at
fc39577c42420931ff374b6c505499dae01b61c8 passes all 53 records and eight real system
category changes/readbacks. Artifact 12591394770 is 191,285 bytes; service digest:
900eaad6534d6af9d850cb8464622aac4f88ad13abdd09c97307b4c314df0665. All five canonical
files were independently reproduced using the exact eleven-file source snapshot.
GlobalFontObservations.json preserves semantic/font/control and source Store
observations, with strict source/driver/bridge/archive bindings. Its SHA256 is
027c3a5ce706548777e490e0ebc9614715bb0efe7cc675a0d820884b0d9c9602.

Replay the committed projection against the accepted local evidence:

```sh
python3 scripts/project-ios-chief-global-fonts.py \
  --canonical captures/recovery-20261025/rich-global-fonts-native-v3-independent/large \
  --source-root captures/recovery-20261025/rich-global-fonts-native-v3-source \
  --project-commit fc39577c42420931ff374b6c505499dae01b61c8 \
  --run-id 40175245243 --artifact-id 12591394770 \
  --artifact-zip captures/recovery-20261025/rich-global-fonts-native-v3.zip \
  --artifact-sha256 900eaad6534d6af9d850cb8464622aac4f88ad13abdd09c97307b4c314df0665 \
  --bridge-events captures/recovery-20261025/rich-global-fonts-native-v3/rich-composer-global-font-events.json
```

Use --output only to regenerate. The projector also requires canonical payloads
and host events to match actual service archive bytes. The native verifier's
--system-font-cases mode requires the exact --bridge-events receipt. Dispatch
probe=systemFonts and category=large for a new original run. No private selector,
source modification or real account/backend is used. Ordinary transaction and
controller-override font observations remain separate and unchanged. See
CHIEF_RICH_TEXT.md for font-base/undo findings, excluded attempts and remaining
empty/font-trait/lifetime/physical/presentation acceptance. This is not full parity.
