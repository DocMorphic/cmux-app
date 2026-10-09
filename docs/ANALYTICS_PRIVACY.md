# Mobile analytics and privacy source audit

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

The Android app does not integrate this iOS analytics uploader, consent settings
or replay recorder. Its Firebase analytics collection is explicitly disabled;
local diagnostic logging and user-requested log export are separate facilities.
The iOS privacy toggle and Feed analytics work therefore remain open requirements.

Before enabling the Android feature, establish an Android event contract and
accepted server destination, implement the consent setting and its live
generation/cancellation gate, and wire only the reviewed bounded event properties.
Android events must describe the actual Android platform. If a replay/crash
provider is adopted, integrate sensitive-content masking and the same revocation
gate before activating it. Verify disabling consent during token acquisition,
queued uploads and in-flight requests; account/team changes must retire the old
identity and upload owner. Backend support and provider choices are unresolved;
this audit does not classify them as unavoidable Android differences.

Evidence: the scoped settings source is retained in ignored
`captures/MobileSettingsView-f4.swift`; the other files were read directly from
the upstream Git cache. No analytics request, consent change or recording session
was performed during this audit.
