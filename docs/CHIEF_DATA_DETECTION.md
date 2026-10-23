# Chief dates, addresses and detected links

Status: production still detects web/email/phone links. Date/address actions are
open; neither the Android system classifier nor the evaluated SDK is accepted as
a full replacement. Signed 715 and its account/pairing data are unchanged.

The pinned cmux source is a5911a1f92f3eeb11c4d7f3e2ef89204718c5009.
ConversationDataDetection uses link, phone, address and date checking types.
MessageCellLayout applies those ranges to literal text; source taps use the
matched URL, tel:, Apple Maps query or calshow: at the resolved date. The
manifest's dataDetectionFiles and referencePackage tree bind those sources.
Underline opacity is separately implemented/verified; that does not close the
missing detector/action families.

## Isolated Android SDK observations

The optional `-PentityDetectionSpike` project has a separate application ID and
no dependency from the companion. `gradlew projects :app:dependencies
--configuration releaseRuntimeClasspath` without that flag confirms both the
probe and entity-extraction dependency are absent. No SDK was added to the
shipping app, no action was fired and no physical phone/account was operated.

The evaluated dependency is `com.google.mlkit:entity-extraction:16.0.0-beta6`.
The actual AAR requires API 26 and its ARM64 library passes the static 16 KiB ELF
LOAD/RELRO checks. Runtime observations use API 36 with 4 KiB pages; they do not
prove 16 KiB runtime acceptance. SDK text processing is on-device, with model
loads/downloads and operational metrics as described in Google's
[API guide](https://developers.google.com/ml-kit/language/entity-extraction/android),
[disclosure](https://developers.google.com/ml-kit/android-data-disclosure) and
[terms](https://developers.google.com/ml-kit/terms). Production privacy, lifecycle,
notices/dependency and release integration are not accepted by this evaluation.

`tests/chief-data-detection/Cases.json` contains eleven public synthetic inputs
covering all nine app locales. Cold v8 and warm-boot v9 each run online and after
an actual process restart with no active network. All 44 case observations have
no SDK exception and identical entity outputs; offline clients report cached
models. Exact UTF-16 ranges, model identifiers, epochs, granularity and timings
are retained. Both network settings restore, crash buffers are empty, guest
filesystems sync before shutdown and both owned disposable AVDs are deleted.
Checkpoint: `captures/recovery-20261023/entity-extraction-checkpoint.json` binds
44 evidence files, APKs and tested inputs/sources.

Raw outputs are not an accuracy or iOS-equivalence verdict:

| Input | SDK observation |
| --- | --- |
| English relative date and public address | Next-day 08:00 and address; emoji offsets preserved. |
| English absolute date at DST change | 2026-10-25 08:00 Europe/Berlin (+01:00). |
| Date within a URL path | Also detected as a date; combined detection must protect existing full URL ranges. |
| German relative date and public address | Next-day 08:00 and address. |
| Spanish time | Both 08:00 and 20:00 alternatives. |
| French time | Only the next-day date, not the full time phrase. |
| Arabic time | No match for this sample. |
| Japanese time | Next-day 08:00. |
| Korean time | Separate date and time spans. |
| Simplified Chinese morning | Resolves to 20:00 for this sample. |
| Traditional Chinese time | Only the next-day date. |

V1–v4 are invalid quality evidence: the probe supplied language tags instead of
SDK model identifiers. The corrected code uses the public fromLanguageTag API.
V5 covers only the first four samples. V6/v7 found two zero-byte cached models
in the previous owned AVD, despite reported readiness; cause is not established.
Those failures remain recorded and are excluded from successful detection
claims. The final fresh AVD and guest-synced teardown pass the warm reboot.

## Actual iOS source comparison verified

`prepare-ios-chief-reference.py --with-data-detection` adds a dedicated test and
shared inputs to the immutable source host. It calls actual NSDataDetector with
the unmodified source type mask, and the pinned source actionURL function. It
observes ranges, components, all dates/action URLs and actual runtime/language.
A controlled process default time zone is restored after each test. NSDataDetector
has no injected reference clock here: before/after UTC times are captured for
each case, explicitly distinct from Android's fixed reference.

The manual `ios-chief-detection.yml` workflow requests all nine app language
contexts on an iOS 26 simulator. The verifier requires each context's actual
preferred language, complete exact inputs/UTF-16 slices, source/test/fixture
provenance, observed clock bounds, source Calendar/Maps/link mapping and no
opened action. Seven adversarial guards and all 103 existing/new Chief export
guards pass locally. Swift syntax/Foundation API checks and generated-project
parsing pass. Existing default/Translation/Poll hosts remain byte-identical
(237/240/240 files). Native run 39877268200 completed successfully at immutable project source
`c18eabaeaa2a0b7b5ca9d5b7ede240ce17571dad`. Its uploaded canonical output
and an independent rerun of that exact source verifier are byte-identical in
all ten files. The artifact ZIP is 473,467 bytes with SHA-256
`d9907961b023cc8ad6ef075e351cae842ccd90e58889adb8b250a889919c6970`.
The accepted scope is 99 actual detector/actionURL observations on iOS 26.4.1.
Preferred languages vary across nine contexts; `Locale.current` remains `en_DE`.
The process default time zone is Europe/Berlin; the simulator system zone is GMT.
These are nine preferred-language contexts, not nine independently changed
system locales. No action or rendered-bubble acceptance follows from this run.

Compare those actual source results before selecting or changing a production
engine. Retain whole URL/email/phone precedence, off-main bounded work, stale
result/owner fencing, model corruption/download/offline recovery, locale/time-zone
handling, explicit read-only Calendar/Maps dispatch and existing literal text.
Actual bubble rendering, taps, accessibility, physical/lifecycle behavior and
all broader full-parity gates remain open; no platform difference is waived.

Native run [39877268200](https://github.com/DocMorphic/cmux-app/actions/runs/39877268200)
and its independently verified immutable source/artifact are retained under
`captures/recovery-20261023/native-detection-run-39877268200`, including the
accepted checkpoint and all 99 observations in `source-comparison-input.json`.

### Calibrated differences

The SDK was rerun online and after an offline process restart at the first
native observation's clock (`1792715252130`, 2026-10-23T00:27:32.130Z), first
with per-case preferred locales and then with explicit `en-DE` matching native
`Locale.current`. Both completed phases retain eleven observations each;
clock/locale calibration does not remove these differences:

| Synthetic input | Pinned iOS detector | Evaluated SDK |
| --- | --- | --- |
| Friday at 5:30pm, observed on Friday | Next Friday, October 30 at 17:30 | Same day, October 23 at 17:30 |
| French demain à 8h | Whole phrase, next day 08:00 | Tomorrow only, midnight |
| Japanese morning | Whole phrase including に in CJK preferred contexts | Omits に |
| Korean morning | One full date/time phrase in CJK contexts | Separate date/time spans |
| Simplified Chinese 上午 | Next day 08:00 in CJK contexts | Next day 20:00 |
| Traditional Chinese morning | Full phrase, next day 08:00 in CJK contexts | Date only |
| Absolute English date | Excludes the trailing period | Includes it |
| Date within URL path | Keeps full URL only | Also emits an overlapping date |

Native en/de/es/fr/ar preferred contexts find the German date/address and
English dates/addresses; CJK contexts omit the German sample. Spanish is a full
next-day 08:00 phrase in western contexts but only today's 08:00 time in CJK
contexts. Arabic has no match in any captured context. These are bounded sample
observations, not a general language support guarantee. Apple Maps component
ordering is not stable; compare components, not raw query-string order. The SDK
retains the reference's .130 milliseconds, whereas these native targets have
whole-second precision; that alone is not a meaningful calendar discrepancy.

Calibrated SDK evidence is in `entity-native-clock-case-locales` and
`entity-native-clock-native-locale-v2` under `captures/recovery-20261023`.
The first native-locale launch was rejected at preflight due to a lingering
owned emulator ADB entry; it is excluded from SDK quality claims. The subsequent
run starts only after the previous emulator exited and ADB was clear. No
production engine is selected or integration accepted by this evaluation.
