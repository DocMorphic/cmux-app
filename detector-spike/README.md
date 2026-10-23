# Isolated entity detector evaluation

This opt-in APK evaluates structured date/address detection against public
synthetic text. It has a separate application ID, no account/authentication code
and no dependency from `:app`; ordinary companion builds do not include it.
Eleven shared inputs in `tests/chief-data-detection/Cases.json` cover the nine
app locales; the first four also match the earlier system-classifier probe.
Observations, including download failures and exact UTF-16 ranges, are written to
the evaluation APK's external files directory. A passing test means the probe
completed, not that the detector matches iOS or that it has been adopted.

Build explicitly:

```sh
./gradlew -PentityDetectionSpike :detector-spike:assembleDebug \
  :detector-spike:assembleDebugAndroidTest
```

Run only on an owned emulator, naming its serial explicitly, with instrumentation
argument `-e entityExtractionProbe true`. Without that argument the test skips;
it also refuses non-emulator models. Never use the user's phone for this probe.
It does not fire any date, Maps or other action.

After an online model preparation run, `-e entityProbeOffline true` requires an
actual absent active network and previously downloaded models. A fresh extractor
still calls its public readiness method before annotation. Always convert BCP-47
language tags with `EntityExtractorOptions.fromLanguageTag`; model identifiers
are names such as `english`, not `en`.

The local v8/v9 cohorts record eleven observations in each of four phases:
fresh/warm boot, online/offline, with a process restart before offline inference.
All phases complete without SDK failures and have identical entity outputs.
Some outputs are ambiguous/partial, the Arabic sample has no match, and the
Simplified Chinese morning phrase resolves to 20:00. The independently verified iOS capture differs on these examples and on the
next-Friday policy; these outputs do not establish language equivalence.
No SDK or probe dependency is added to the ordinary companion release graph.

Earlier probes using language tags directly are invalid SDK-quality evidence.
Two later probes found zero-byte cached models in the old disposable AVD; their
cause is not established. The final fresh AVD and guest-synced teardown pass a
warm reboot. Both owned AVDs were removed after preserving the evidence; no
physical phone or signed-in companion data was used. Details/hashes are under
ignored `captures/recovery-20261023/entity-extraction-checkpoint.json`.

The SDK is pinned to `com.google.mlkit:entity-extraction:16.0.0-beta6`, under
[ML Kit's terms](https://developers.google.com/ml-kit/terms). It performs text
processing on-device but downloads language models and sends operational metrics;
see [Google's disclosure](https://developers.google.com/ml-kit/android-data-disclosure).
Its native ARM64 binary has 16 KiB-aligned ELF load segments. Neither this source
inspection nor future probe success establishes production dependency/privacy,
locale/model-lifecycle, calendar/Maps behavior or physical-device acceptance.

For calibrated comparison only, the opt-in instrumentation accepts
`-e referenceEpochMillis <epoch>` and `-e preferredLocale <BCP-47 tag>`.
It records whether the clock came from the shared fixture or an explicit override,
and the effective preferred locale for each observation. Neither changes the
synthetic fixture or the pinned iOS detector's actual captured clock. The local
calibrated online/process-restarted offline phases match the source clock and
then en-DE, and still expose the documented differences. Their 44 observations,
source/APK hashes and restored network state are bound in
`captures/recovery-20261023/entity-native-clock-checkpoint.json`.
