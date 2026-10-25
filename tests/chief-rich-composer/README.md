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

## Separate live-font probe candidate

FontCases.json contains four cases / 44 public actions. Dispatch the rich-composer
workflow with probe=fonts and category=all to observe public controller text-metric
trait overrides while the original editor is mounted. Verify each category with
verify-ios-chief-rich-composer.py --font-cases and the exact run source commit.
The separate fixture/provenance key and probe marker prevent accepting transaction
exports as font observations. Export guards require actual category propagation
to the original editor and actual per-span fonts, without prescribing formatting
outcomes. Native execution/export acceptance is pending. This does not update the
accepted transaction projection or establish global Settings, gestures, physical
input, rendered pixels, Android or full app parity.
