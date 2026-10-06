# Terminal composer dictation

Implemented 2026-10-06; **physical microphone/recognizer acceptance is pending**.
Applies to the native and SSH terminal composers. The microphone does not send
terminal input: recognized words enter the existing draft, then the user chooses
Send or Insert through the existing delivery path.

## iOS comparison

Scoped upstream source: `186cec79781256867ad4516f0802118738bd2393`.
This audit does not advance the global implemented/reviewed pins.

- `Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/TerminalComposerView.swift`:
  `micButton`, `toggleDictation`, focus-loss/disappearance handling, and `send()`.
  Send cancels recognition before capturing the draft and explicitly requests
  composer focus. The editor grows to 14 lines.
- `Packages/iOS/CmuxMobileSupport/Sources/CmuxMobileSupport/ComposerDictationController.swift`:
  prefer on-device recognition, append partials to the captured draft prefix,
  lock editing from permission request through finalization, graceful Stop with
  a 2.5-second deadline, hard cancellation on Send and owner retirement.
- `ComposerDictationTextMerger.swift` in the same package: preserve prefix
  whitespace, trim leading transcript whitespace, replace rather than accumulate
  the partial tail, and ignore empty recognition results in the controller.
- `GhosttySurfaceRepresentable.swift` passes the composer's modal callbacks to
  `GhosttySurfaceView.photoPickerWillPresent/DidPresent/DidDismiss`. That host
  forwards them to `TerminalInputSessionReducer`, whose `modalWillPresent`
  clears requested focus and resigns the actual owner. Dismissal reconciles new
  intent; it does not automatically restore the old keyboard request.

Android now clears composer focus before Photos/Files, as it already did before
attachment preview. Send requests composer focus, and both editors allow 14 lines.
Actual IME timing, long drafts, landscape and enlarged-text layout still need the
combined runtime and Pixel checks.

## Android implementation

`ComposerDictation` owns a generation-stamped permission/start/listen/stop state
machine. Each start captures the existing text. A partial replaces only its own
transcript tail. Blank final results retain the last partial. Explicit Stop waits
up to 2.5 seconds for a final refinement; a 10-second startup watchdog releases a
recognizer that never reports readiness. Send cancels synchronously before taking
the draft snapshot. Closing, cancelled permission requests, late errors/results,
and callbacks from a replaced recognizer cannot mutate a successor draft.

The editor is read-only while recognition owns it; the mic and Send remain usable.
External draft edits also retire the recognition session instead of being replaced
by its next partial. Native account/generation and SSH binding checks apply on
callbacks, independently of Compose observing those changes.

`AndroidComposerSpeech` uses the Android on-device recognizer on API 31+ when
available. Otherwise it uses the system default speech service, which can process
audio remotely. This is not an offline guarantee. Unsupported or undownloaded
languages, unavailable services, microphone denial, network/audio errors and busy
recognizers surface an actionable message; there is no background recording or
silent repeated recognition loop. cmux-app does not store audio files or log
transcripts; draft text follows the existing native encrypted/SSH memory storage.

The manifest declares optional microphone hardware, `RECORD_AUDIO`, and the
recognition-service visibility query. Permission is requested only after a mic
tap. Lifecycle stop, loss of window focus to another window, leaving the visible
composer, changing its owner, or losing input availability cancels capture.
The permission dialog itself is exempt from the window-focus cancellation while
permission is pending. Returning from background never restarts the mic.

Android API references:
[SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer),
[RecognitionListener](https://developer.android.com/reference/android/speech/RecognitionListener),
[RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent).
Recognizer calls and listener transitions remain on the main dispatcher, and every
created recognizer is cancelled/destroyed when the attempt ends, as required by
Android's API contract.

## Evidence and next milestone

- Main and instrumentation Kotlin compilation passed.
- **22 JVM cases passed**: 12 new dictation lifecycle/merge cases plus 10 SSH
  draft ownership cases. They cover partial/final replacement, blank results,
  stopping and timeout, permission cancellation, old callback isolation,
  Send snapshot order, account/owner retirement, external edits, destruction,
  rejected writes, startup timeout and engine exceptions.
- Three new Android UI cases compile but have **not run**:
  `NativeFlowTest#terminalDictationSendsCurrentWordsAndDoesNotAcceptLateFinalResult`,
  `SshImageInputScreenTest#dictationStopRefinesDraftAndSendRejectsLateSpeech`,
  `SshImageInputScreenTest#leavingTerminalStopsDictationAndReturningNeverResumesMicrophone`.
  These inject recognizer events to check real composer/delivery wiring, readonly
  semantics, focus, and navigation. They neither record audio nor prove actual
  speech recognition works.
- Local evidence: `captures/runtime/composer-dictation/verification.json`, copied
  JVM XML, compilation logs and scoped upstream source snapshots.
- No APK packaging, emulator launch, Pixel action or signed-release promotion in
  this batch. The three preview-lifetime cases from the previous batch also remain
  queued for the next combined milestone.

Remaining physical checks: initial/denied/revoked permission; supported and missing
language models; actual partial/final recognition and silent Stop; on-device and
system-provider behavior; interruption by picker/preview, incoming call, screen
lock/background and account switch; no mic resumption on return; no transcript
refill after Send; Gboard/hardware keyboard focus, selection, resizing and TalkBack.
Task-composer/agent-reply dictation availability has not been audited in this scoped
terminal change. No platform difference is declared unavoidable on this evidence.
