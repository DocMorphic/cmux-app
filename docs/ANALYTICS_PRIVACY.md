# Mobile analytics and privacy

Audited 2026-10-09 at scoped cmux revision
`f4b1509054949eaad5d695569ad443c4c18ed68d`. This does not advance the global
implementation/review pin or establish full privacy/UI parity.

## Official iOS behavior

- `MobileSettingsView.swift` has a Privacy section and a persisted
  `sendAnonymousTelemetry` toggle, defaulting to true. Its label/footer varies
  with `CMUXCrashReportingEnabled`: analytics with crash reports, or analytics
  alone. The settings key is shared with the runtime consent provider.
- `CmuxMobileAnalytics` reads current consent, gates uploads and cancels registered
  upload tasks on revocation. The authenticated `HTTPAnalyticsUploader` posts
  batches to `/api/analytics/events` using the app's account token provider.
- `AgentFeedPerformanceModifier.swift` observes visible Feed scroll phases and
  pauses the optional replay interaction reporter during scrolling. The
  `MobileReplayPrivacyMask` overlays a recording mask without blocking user
  input; it is not an OS screenshot restriction.
- The scoped web proxy imports `iosEventPolicy.ts`. That policy permits a
  specific `ios_` event catalog plus `$identify`; unsupported event batches are
  rejected. Source:
  [event policy](https://github.com/manaflow-ai/cmux/blob/f4b1509054949eaad5d695569ad443c4c18ed68d/web/services/analytics/iosEventPolicy.ts),
  [proxy route](https://github.com/manaflow-ai/cmux/blob/f4b1509054949eaad5d695569ad443c4c18ed68d/web/app/api/analytics/events/route.ts).

## Android state and work still required

Android Settings now has the Privacy section, the analytics-only label and a
device-local `sendAnonymousTelemetry` opt-out defaulting to true, matching the
scoped iOS provider. It sits before Diagnostics, persists across Settings/store
recreation and shares one main-process runtime owner. Missing or malformed stored
values resolve to the iOS default. The row has one switch accessibility action,
a minimum 56 dp target and wrapping text. Its additional availability footer says
explicitly that this version does not upload product analytics.

`NativePrivacyConsent` reads the backing store before event/job admission and
publishes changes to Settings. `NativeAnalyticsConsentGate` permanently retires
pre-revocation snapshots by generation, ignores intervening stale provider reads,
and cancels registered jobs outside its lock on revocation or owner closure.
Lazy jobs register before starting IO; rejected jobs are cancelled. Cancellation
still happens if publication throws. The application initializes this owner only
in its ordinary process; this does not establish cross-process consent/identity
coordination for a future recorder.

No uploader, event collection, identity or replay recorder is activated by this
batch. Firebase analytics collection remains explicitly disabled. Local diagnostic
logging and user-requested exports are separate and remain available independently
of this product-analytics preference.

Before enabling the Android feature, establish an Android event contract and
accepted server destination, integrate the consent gate with the uploader's actual
transport and identity owner, and wire only the reviewed bounded event properties.
Android events must describe the actual Android platform. If a replay/crash
provider is adopted, integrate sensitive-content masking and the same revocation
gate before activating it. Coroutine cancellation cannot retract an accepted
request or cancel blocking HTTP without a bound transport cancellation hook.
Verify disabling consent during token acquisition,
queued uploads and in-flight requests; account/team changes must retire the old
identity and upload owner. Backend support and provider choices are unresolved;
this audit does not classify them as unavoidable Android differences.

Source evidence: the scoped settings source is retained in ignored
`captures/MobileSettingsView-f4.swift`; the other files were read directly from
the upstream Git cache. No analytics request, consent change or recording session
was performed during the original audit.

## Consent implementation checkpoint — 2026-10-10

Nine focused JVM cases passed with zero failures/errors/skips (0.081 s). They
cover stale provider observations, generation retirement across re-enable,
unchanged consent, active/lazy job cancellation, rejected registration, completed
job isolation, owner closure and cancellation when the publisher throws. These
are coroutine gate checks, not proof of real HTTP cancellation or analytics
delivery. Main and instrumentation Kotlin compiled in the same successful 52 s
Gradle invocation:

```sh
./gradlew :app:testDebugUnitTest \
  --tests io.github.docmorphic.cmuxapp.NativeAnalyticsConsentGateTest \
  :app:compileDebugAndroidTestKotlin --max-workers=2
```

Two compiled but unexecuted Android cases exercise actual SharedPreferences
defaults/malformed values, persistence/reopen, off-main external revocation while
the main-thread listener is pending, and the real Settings row's single switch
action, enlarged-text wrapping and opt-out restoration. Run
`NativePrivacySettingsTest` at the next integration milestone; inspect its rendered
screen before claiming visual acceptance. No APK, emulator, Pixel, analytics
service or signed release was run/created for this batch. Gradle was stopped;
the retained local log is `captures/privacy-consent-gradle.log`. Signed 641 remains
the verified download and predates this implementation. Full privacy parity,
account/team retirement and actual uploader/crash/replay integration remain open.
