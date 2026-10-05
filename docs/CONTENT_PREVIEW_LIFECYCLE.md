# Content preview lifecycle

## Current Mac bytes for remote file actions — 2026-10-05

Remote Save, Share and Open now capture the visible path's current scoped RPC,
stat that path again, and stream its current bytes. This is wired through the
shared artifact page for terminal Files, direct terminal paths, session galleries
and native file/Markdown panels. A reconnect supplies the current page's new
admission for the next action; an action already in flight stays on its captured
connection. There is no fallback to stale local preview bytes after a missing
file, rejected authorization or failed transfer. The reader's displayed preview
and position are not intentionally reloaded by exporting.

Export actions remain available when a remote file has not loaded or exceeds the
inline preview limit. Each export still checks Mac metadata/authorization and
stream chunk bounds. Remote Save streams straight into its durable private
snapshot, avoiding a second full staging download. Fresh metadata supplies the
picker filename/MIME; the journal can reconcile a PREPARING bundle that predates
that metadata. Preparation now has progress/cancellation UI as well as writing.
Share/Open use a separately materialized file for the system chooser. Copy Image
and Copy Contents continue to use the displayed content; local downloads and
Changes revision previews retain their existing local snapshot behavior.

Reference: `ChatArtifactFileActionStore.materialize` and
`ChatArtifactTemporaryFileStore.fetch` at scoped iOS commit
`186cec79781256867ad4516f0802118738bd2393`. They establish a fresh stat followed
by scoped materialization, without the inline-preview byte limit. Android streams
fresh bytes for each action rather than adding an iOS-style full-content cache.
The global parity pin is unchanged. Source copies, hashes, build log and test
XML are under `captures/runtime/remote-file-actions-batch/`.

**Verification:** main Kotlin compiled and **26 focused JVM cases passed** in a
29-second Gradle run (6 new remote-source cases, 17 Save snapshot/recovery cases,
3 sharing regressions). New checks cover changing host bytes while the displayed
copy remains unchanged, exact terminal/session/panel scopes, refreshed MIME/name
restoration, missing files, reaching the stream beyond the preview limit, and
cancellation rejecting late bytes/cleaning only the owned snapshot. The oversized
case confirms stream admission then injects failure; it does not transfer a
512 MB file or prove low-memory behavior.

No Android runtime suite, emulator or APK build ran. Signed 606 is unchanged.
Still verify real toolbar availability for oversized/failed previews, picker
name/MIME/results and fresh chooser bytes on Pixel; revoke or replace connections
during preparation; rotate/close previews during Save/Share/Open; and exercise
large-file storage pressure. Share/Open preparation is still tied to its Compose
coroutine, so presentation ownership across recreation remains implementation
work. Background export completion and orphan/receipt cleanup from the Save
batch also remain open. This completes the source implementation of the freshness
follow-up below, not the device acceptance gate.

## Durable Save recovery and write progress — 2026-10-05

Pending exports now use an independent copy under Android's private
`noBackupFilesDir/file-saves`, rather than evictable cache storage. Preparation
records its identity, copies with an exact size bound, syncs the payload, and
publishes a SHA-256 seal. Before opening/truncating a selected destination, the
writer verifies the retained copy. A corrupt, missing or incomplete copy cannot
be exported as if it were complete. Existing waiting/writing saves can migrate
from the old cache layout; a legacy PREPARING copy is never assumed complete.

Each export has an atomic durable journal. Restoration uses that journal over
an older Activity bundle, covering preparation, picker wait, writing, failure,
completion and cancellation. A sealed preparation can finish restoring to READY.
Picker results arriving during asynchronous restoration wait for reconciliation.
WRITING resumes from the saved destination when the Activity owner is restored;
provider authorization is still enforced when opening the document. Small
completion/cancellation receipts prevent an old bundle or late journal update
from resurrecting a finished export. Completion cleanup is repeatable, including
when a process stopped after recording success but before removing its payload.

Large writes now report progress with a Cancel action. Cancellation waits for
the active writer to stop before removing its copy or releasing its acquired
provider grant. Grants already held by other app features remain unowned by
Save. Grant acquisition and journal writes run off the UI dispatcher. No file
bytes or RPC credentials enter Android saved state, and the durable copies are
excluded from Android backup by their storage location.

Android explicitly notes that `SavedStateHandle` updates made while an Activity
is stopped may not reach the saved bundle until another start/stop cycle:
[Saved state ViewModel documentation](https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-savedstate).
Provider permission persistence is separate from saved UI state:
[Storage Access Framework documentation](https://developer.android.com/training/data-storage/shared/documents-files).
This is why the journal is needed; these references do not establish runtime
acceptance of the implementation.

**Verification:** main Kotlin compiled and all **17 focused JVM tests passed**
in the final 19-second Gradle run: seven existing snapshot cases and ten recovery
cases. These cover exact bytes, stale-bundle reconciliation, terminal receipts,
corruption before destination opening, cache migration, interrupted preparation,
late journal writes, and write cancellation/progress. An earlier 16-test pass
preceded the repeatable completion-cleanup regression. Logs, XML and source
hashes are in `captures/runtime/save-recovery-batch/`.

The existing Android picker regression source now uses the durable storage
location and asserts payload cleanup (small terminal receipts intentionally
remain); it was not executed in this batch. No emulator, Android runtime suite,
or APK build ran. Signed build 606 remains unchanged.

**iOS freshness follow-up:** `ChatArtifactFileActionStore.materialize` at scoped
commit `186cec79781256867ad4516f0802118738bd2393` stats the host path and passes
size/modifiedAt into its content cache before export. Android's viewer toolbar
still copies its already downloaded preview. Add an explicit current-loader
materialization path for remote Save/Share/Open, preserving account/path scope;
keep local browser/download/Changes snapshot behavior separate. This source
finding is not an unavoidable platform difference. The scoped source and hash
are in `captures/runtime/save-recovery-batch/`; the global parity pin is unchanged.

Still required: actual process termination with the system picker open and during
writing; restored Activity result/grant behavior; provider denial/reboot/disconnect;
large-file progress and cancellation UI on Pixel; background completion without
reopening the Activity; abandoned-copy/old-receipt reclamation; and handling any
partial document left by a cancelled provider write. These remain acceptance or
implementation work, not exclusions. The journal tests reconstruct storage
objects; they do not simulate Android process death or prove those device flows.

## Files and folder recovery batch — 2026-10-05

Implemented on main after `f8ee24d`; this is a source milestone, not a new APK.
Terminal Files now separates permission to retain local content from permission
to make a request. Transient feed loss keeps the sheet, selected file, gallery
rows/session/cursor and completed preview. A replacement connection must verify
the same Mac and obtain its own workspace snapshot before the retained owner
adopts it for future operations. Old RPC objects never switch connections.
Rejected snapshots, account access changes, removed terminals and changed
capabilities invalidate retention; equal-looking later snapshots cannot revive
an invalidated admission.

Completed previews retain both their local file and presentation identity, so
connection adoption does not deliberately unmount the reader. Interrupted
transfers become explicit retryable failures; reconnection alone does not fetch
file bytes. Gallery paging/search jobs pin their RPC, cancel on loss and retain
readable state. A failed In view refresh keeps the scan that admits the open
preview. An interrupted initial scan can resolve its session when retried.
Open folders now have retained controllers for both gallery and direct terminal
routes, keeping listings across view recreation/reconnect and rejecting late
responses. Folder and initial direct-path errors use the shared typed failure
copy and retry policy. Direct-path metadata resolution may restart when the
connection returns; an already selected byte preview waits for explicit Retry.

Reference: scoped iOS commit `186cec79781256867ad4516f0802118738bd2393`,
`WorkspaceDetailView+TerminalArtifacts`, `TerminalArtifactFilesSheet`,
`ChatArtifactFolderView` and `ChatArtifactLoader`. The iOS sheet's initial task
is keyed to terminal identity; its session selection persists independently of
wire changes. Terminal loaders leave `sourceIdentity` nil, and the folder's
load identity is path plus that source identity. Copies/hashes are under
`captures/runtime/files-recovery-batch/ios-source/`. This does not advance the
global parity pin or establish matched-screen/real-route parity.

**Verification:** main Kotlin compiled and 27 focused JVM cases passed in the
final 19-second Gradle run (10 preview-controller, 13 gallery, 2 folder-controller,
2 coordinator cases). They check preserved content/identity/cursors, explicit
retry, late-response rejection, terminal removal and irreversible revocation of
an old admission. The first run had 24 passes and two new folder-test failures:
the fixture supplied `path` instead of the wire contract's child `name`, so the
parser correctly returned no entries. Corrected the fixture and retained the
original failures. Logs, XML and source hashes are in
`captures/runtime/files-recovery-batch/`.

**Still pending:** real Compose route/rotation/reading-position evidence for this
batch, direct-tap and long-text acceptance, account/network transitions on Pixel,
folder back-stack scroll behavior and process death. No Android runtime suite,
emulator or APK build ran for this batch, per the feature-first cadence. `adb`
reported no devices. Signed build 606 remains the last verified download. Add
this batch to the next integrated physical/Android acceptance pass; the JVM
controller checks do not prove visible UI restoration.

## Explicit panel retry after connection replacement — 2026-10-05

A failed panel now retains its error and exact selection through transient feed
loss and recreation, just as a completed panel retains its document. Reconnect
alone does not reload a failed panel. Retry on a live original connection keeps
its owner; Retry after replacement obtains a fresh panel admission from the
coordinator, with the same account/Mac/workspace/surface/path/kind/title. It
acquires the new connection hold before releasing the old owner. The old request
object cannot send requests on the replacement connection.

While no verified replacement is available, Retry makes no network request and
shows the connection-specific failure hint. The hint comes from the panel feed's
actual availability, including OFFLINE, rather than inferring CONNECTING from
the foreground UI. Revocation or descriptor/account changes still discard the
retained failure. An old Retry callback cannot replace the currently selected
owner or reopen a revoked panel.

This compares the explicit retry counter/load task in `MarkdownSurfaceView`,
the path-stable embedded preview and its current loader actions, and the panel
loader construction at scoped iOS revision `186cec79781256867ad4516f0802118738bd2393`.
Five source files and hashes are copied under `captures/runtime/panel-retry-recovery/`.
The global parity pin is unchanged. **16 focused JVM tests and three changed
Android recovery cases (28.731 s) passed.** They cover retained errors, offline
recreation, fresh-connection Retry, stale callbacks, revoked access and interrupted
loads. Four of seven captured screenshots were inspected (offline recreation,
both successful retry renders, revoked state). Source/APK receipts match; all
14 assets verified; no successful-run crashes/new ANRs; screen/sleep settings
unchanged; sole AVD stopped/reaped. Both cold boots encountered System UI ANRs
before testing; screenshots and recovered home screens are preserved. Signed
606 is unchanged. The user requested larger feature batches and deferred broad
regressions during this run; the final pass therefore reruns the three changed
cases, not the full nine-case suite.

The first runtime pass completed eight checks and failed the old interrupted-load
assertion, which expected the panel owner to be discarded and the file to reload
automatically. It exposed ordering-dependent behavior: a socket error published
before feed retirement could now retain the owner, whereas cancellation before
publication still discarded it. Pending requests now consistently retain their
selection, cancel the unfinished transfer and show a connection failure. Late
chunks cannot publish over that failure; partial files are cleaned up. Retry
uses the newly admitted owner and keeps the original request unusable. The
updated regression asserts no second fetch until an actual Retry tap, and exact
replacement bytes afterward. The initial failure is preserved under
`interruption-attempt/`; this supersedes the earlier automatic-refetch checkpoint.

Main Files connection recovery, native errors outside the RPC contract,
process death, live panel-kind changes and physical Pixel/Mac acceptance remain
open; it does not establish whole-screen or all-route parity.

## Local preview storage and size-limit clarity — 2026-10-05

Local open/write/sync/close operations now classify Android errno causes separately
from remote transfer failures. ENOSPC and EDQUOT produce the storage-full state;
other local IO failures produce local-storage-unavailable. The scan is bounded by
identity to tolerate cyclic causes, and does not parse localized error messages.
Stream exceptions from the Mac remain outside this boundary, and partial files
still get cleaned up. The controller retains only the structured state and a
short diagnostic, not the exception/cause graph.

This follows `ChatArtifactLocalFailureClassifier.swift` at scoped reference
`186cec79781256867ad4516f0802118738bd2393`; source/hash recorded under
`captures/runtime/preview-storage/`. The global parity pin is unchanged.
When formatted actual and limit sizes round to the same text, the oversized-file
message now explicitly says the limit was exceeded and includes localized exact
byte counts. The ordinary short size message remains for distinct rounded sizes.

The runtime fixture uses Android's documented
[proxy-file callback](https://developer.android.com/reference/android/os/ProxyFileDescriptorCallback#onWrite(long,int,byte[]))
to return ENOSPC through a real file descriptor without filling storage. An initial
attempt using `/dev/full` instead was rejected by Android access controls and
failed its storage-full assertion; the other four checks passed. The failure and
runner preflight correction are preserved under `captures/runtime/preview-storage/`.
No device permissions were changed.

**19 focused JVM checks and five Android runtime checks (10.210 s) passed.**
The latter verify an actual file-descriptor write returning ENOSPC, nested errno
and quota classification, cause-cycle termination, real local open failure,
exact successful bytes, socket-failure classification and partial cleanup, plus
the actual native-panel size-limit UI with no content fetch. The corrected size
screenshot was inspected. All 14 viewer assets verified; source and both APK
hashes match the final receipts. The successful run has no crash entries or new
ANRs and unchanged screen/sleep settings. Two cold boots before the runtime runs
hit System UI ANRs; their screenshots and recovered home screens are retained.
An earlier startup was stopped before testing to add the scoped iOS quota case.
The sole AVD is stopped/reaped. Signed 606 remains the verified download.

Storage classification here covers output operations;
directory creation/rename still report generic local-storage-unavailable, and
decoder/read/export errors and physical-device acceptance remain open. It does
not complete the broader native admission/retry, lifecycle or parity gates.

## Typed preview failures and explicit retry — 2026-10-05

The shared file preview controller now retains a structured failure alongside its
diagnostic string. File previews use specific messages for request, authorization,
file, transfer and response failures, with retry offered only where the scoped
iOS reference permits it. Markdown panels apply their separate iOS vocabulary:
missing file, forbidden preview, closed panel, Mac update required, unreachable
Mac, temporary transfer failure and generic load failure. Unknown/malformed host
failures do not claim that the Mac is unreachable or display raw host messages.

Metadata failures, oversized files, changed transfer sizes and invalid chunk
content retain typed reasons. Oversized previews retain actual/limit byte counts
and use Android's localized file-size formatting. No content request or private
file is created for an oversized preview. Legacy not_found errors retain the
terminal/session authorization distinction. The failure object contains no
Throwable, view or full host error payload.

Reference: `MobileChatArtifactFailureClassifier.swift`,
`ChatArtifactFailurePresentation.swift`, `MarkdownSurfaceModel.swift` and
`MarkdownSurfaceView.swift` at `186cec79781256867ad4516f0802118738bd2393`.
Source hashes and execution evidence are under `captures/runtime/panel-failures/`;
this scoped comparison does not advance the global parity pin.

**31 focused JVM tests passed. Seven Android panel tests passed in 99.758 s**,
including failure retention through recreation, distinct file/Markdown messages,
non-retryable errors, successful explicit retry and oversized-file rejection
without fetching bytes. **Nine Files regressions passed in 86.445 s** on the same
production APK. The latter ran before a test-only fixture correction. Source and
APK hashes match the receipts. Eight new failure/recovery screenshots were
inspected; nine repeated lifecycle screenshots were captured but not reinspected
in this batch. The Files regression class produces no screenshots. All 14 viewer
assets verified. Both successful runs have unchanged screen/sleep settings,
no app crash entries and no new ANRs; the sole AVD is stopped/reaped. No Pixel
was attached. Signed 606 remains the verified download.

Two cold boots encountered System UI ANRs before instrumentation; those logs and
screenshots are preserved. The first seven-test run passed six checks but failed
the file-specific case because the fixture changed panel kind after launch,
racing the initial workspace list. Configuring kind before launch fixed that
test setup; all seven then passed. This does not verify live panel-kind changes.
The size-limit screenshot also exposes rounded sizes that can appear equal when
the file exceeds the limit by one byte; clarify that presentation in follow-up.

**Remaining:** native transport/authorization errors raised outside the RPC error
contract, complete local-storage/decoder failure classification, route recovery
and manual retry after the original feed owner has been retired, process death,
live panel-kind changes, icons/layout/accessibility and physical Pixel/Mac acceptance. Completed-document
connection-loss retention is covered by the preceding source checkpoint below;
that does not prove every iOS failure/retry workflow.

## Native panel connection recovery — 2026-10-05

Completed native file/Markdown panels now keep their downloaded content and
reading state during a transient feed disconnect. A snapshot identity preserves
only the exact account, Mac, workspace, surface, path, kind and title. The old
request object remains bound to its original connection and cannot issue requests
on a replacement wire. Interrupted transfers discard their old owner and can
load again after the replacement connection passes host verification.

An authorization, identity or protocol rejection invalidates the snapshot
identity. Restoring an apparently identical workspace list cannot resurrect an
old admission. Account removal, a removed/changed panel or a title refresh also
releases the old preview and its private bytes. The UI displays Preview unavailable
after rejected access, including if the feed subsequently disconnects.

Scoped iOS reference: eight source files at
`186cec79781256867ad4516f0802118738bd2393`, recorded with hashes in
`captures/runtime/panel-wire-recovery/upstream-source.json`. The iOS surface owns
its loaded view by surface identity; Markdown load tasks use path/title/retry,
and file preview refresh uses the title. Connection status supplies failure hints
without replacing an already loaded document. The global parity pin is unchanged.

**58 focused JVM checks passed** (50 feed coordinator, two cache policy and six
preview controller). **Four Android panel checks passed in 63.287 s** on the sole
API 37 / 16 KB arm64 AVD. Real pinch/scroll, native viewport and paragraph-geometry
assertions verify a rendered panel through socket loss, offline recreation and
verified feed reconnect, with unchanged owner, exact bytes and one fetch. Title
refresh fetches new content and deletes the old file. Revocation deletes its
replacement, leaves the old request unusable and shows Preview unavailable even
after socket loss. An interrupted transfer is discarded and fetched on a newly
verified feed connection. Existing Raw Markdown search/reading and pending
recreation checks also pass. Removal now broadcasts a workspace-list event and
checks the final empty-workspace route as well as private-file cleanup.

**Two existing Files/panel regressions passed in 39.722 s** on the identical
production APK (before a test-only rebuild). Nine panel and five regression
screenshots were inspected; content is visibly drawn. The pending-recreation
capture catches the menu dismissal animation; the pending-transfer reconnect
capture precedes foreground-connection recovery and still shows its banner.
Those images do not establish menu-animation or whole-screen reconnect parity.
Both successful runs have no new ANR or app crash entries, unchanged screen/sleep
settings, and matching source/APK receipts. All 14 assets verified; sole AVD
stopped and reaped. Signed build 606 is unchanged.

Original failures remain in `captures/runtime/panel-wire-recovery/`: two JVM
runs timed out at the initial two-Mac connection wait in different tests; a
subsequent 58-test run passed with added connection diagnostics, so the cause is
still unresolved. The first Android attempt was blocked by an emulator System UI
boot ANR. Subsequent attempts passed both new recovery checks but exposed a Raw
menu lookup failure and an assertion against the temporary Panel closed message.
The isolated Raw test passed unchanged (27.183 s); final retention tests wait for
the rendered document before opening that menu, so early-load menu interaction
still needs separate investigation. The removal test now waits for the final
route after the foreground list refresh: Android's existing reconciliation drops
the removed selection, consistent with iOS's `selectedMacSurface` fallback and
`WorkspaceActiveSurface.derive`. Test failures now capture a screenshot/hierarchy.
The final four-test run passed after these test-only changes.

**Still open:** the iOS typed failure/manual retry comparison, main terminal Files
transport-loss recovery, different-width/text reflow, process-death/refetch,
physical Pixel/Mac acceptance and full format/accessibility behavior. This
checkpoint does not establish those workflows.

## Rendered Markdown reading state — 2026-10-05

`ArtifactViewerState` now saves a small Markdown viewport bookmark: CSS scroll
coordinates, pinch zoom and a document-block anchor. Capture is synchronous on
Android's save callback, while the JavaScript adapter tracks block geometry.
The saved state contains no document bytes or Android view. Raw/Rendered toggles
share this bookmark, and renderer replacements reuse it. Older saved bundles
without the five new values remain readable.

`MarkdownViewportBinding` restores after the new WebView has committed a visual
state and follows subsequent layout changes above the anchor. It updates the
bookmark's coordinate origin after those changes, so resuming interaction and
reopening the document preserve the paragraph-relative position. Touch, keyboard,
mouse and explicit accessibility scroll/click actions end automatic restoration;
automatic accessibility focus does not. Anchor lookup uses binary search through
the document's block children. Disposal clears native callbacks and the saved
capture closure before destroying the WebView. The upstream shell and all other
hash-pinned assets are unchanged.

**Seven JVM policy checks passed. Two Android lifecycle checks passed in
33.480 s** on the sole arm64 API 37 / 16 KB AVD. Real pinch/scroll followed by
Activity recreation and Raw/Rendered switching preserved native zoom/scroll and
the visual-viewport position of the same heading. A late 180 CSS-pixel height
increase above the reader, then interaction and reopening the original layout,
preserved the paragraph-relative bookmark. Two actual WebView renderer
terminations restored that viewport; a third displayed the existing raw-source
fallback with the real source text. The checks wait for a committed visual state
and require painted text pixels. All seven final screenshots were inspected.

**Three existing Markdown regressions passed in 23.465 s** on the identical
production APK: table/code/Mermaid/Vega rendering and mode switching, sanitization
and image-consent boundaries, and the large-file raw-only limit. Mermaid labels
and Vega bars passed actual screenshot pixel assertions; the screenshot was
inspected. All 14 pinned asset hashes matched. Final runs had no app-process crash
entries or new ANRs, device settings were unchanged, and the single AVD was
stopped and reaped. Source/APK hashes and raw outputs are retained locally in
`captures/runtime/markdown-lifecycle/`.

Earlier attempts are retained: the first fallback assertion used an accessibility
label absent from the native raw view; the next layout assertion failed to account
for the heading's existing CSS padding. Both test errors were corrected. A later
39.823 s two-test pass still captured one unpainted recovery frame and a transient
layout frame, so its coordinate assertions were strengthened with heading geometry,
visual-state commitment and screenshot text pixels before the final pass above.
The separate regression run remains valid because its production APK hash matches
the final APK. Emulator cold boots produced launcher/System UI ANRs; those dialogs
were dismissed and healthy UI confirmed before testing. They are recorded apart
from test-run results.

Scoped reference: `MarkdownWebContentView.swift` under
`Packages/iOS/CmuxAgentChatUI/Sources/CmuxAgentChatUI/Markdown/` at
`186cec79781256867ad4516f0802118738bd2393`. Its coordinator owns page zoom and
allows two WebKit content-process recovery attempts. Android retains its existing
two-retry/raw-source fallback policy. The scoped reference and SHA-256 receipt
are in `captures/runtime/markdown-lifecycle/`; the global parity pin is unchanged.

This does not close physical Pixel acceptance, application-process death with
remote artifact refetch, different-width/text-reflow restoration, the full native
panel transport-loss route, or large-document performance/accessibility gates.
The signed download remains build 606 until the next bundled release.

## Native file and Markdown panel retention — 2026-10-05

The actual native panel route now uses `NativePanelPresentation`, retained by
`NativeFeedSession`, for its selected file and in-progress transfer. Its feed
connection lease survives Activity recreation and reconnection of the separate
foreground terminal client. No Activity or Android view is retained. Leaving the
panel releases its transfer and private files.

Admission captures one exact verified Mac connection, account, workspace,
surface, path, kind and title. Requests are limited to that panel's displayed
file; neither session authorization nor a different panel/path can be substituted.
The owner checks admission before and after requests and native artifact lanes.
Focus-only descriptor changes preserve the download; path, kind or title changes
invalidate it. The title is the upstream refresh token. Removing the panel hides
and deletes its old bytes and displays “Panel closed”. Unavailable connections,
changed descriptors and unsupported hosts have separate presentation messages.
The UI integration lives in `NativePanelRetention.kt` to avoid adding another
large block to `NativeScreen`'s generated JVM method.

**60 JVM checks passed** (48 feed/admission, six preview-controller and six
artifact-RPC tests). The two actual NativeScreen panel checks passed in
**32.104 s** on the sole arm64 API 37 / 16 KB AVD. They verify Raw Markdown mode,
search result `2/2`, reading position within five pixels and exact content, the same retained owner
and local file with one fetch through recreation, an in-progress transfer surviving
another recreation, cleanup on Back, and removal of the private file/native view
with a visible “Panel closed” message when the feed withdraws the panel.
All three panel screenshots were inspected. Two existing Android regressions
also passed in **34.940 s**: the full terminal Files retention case and native
file/Markdown/unknown-surface flow. All three regression screenshots were inspected.
No final-run crash entries or new ANRs; device settings unchanged; source/APK
hashes matched. The sole AVD was stopped and its process reaped.

The initial two panel checks passed in 32.841 s, and two existing panel/Files
regressions passed in 36.350 s. Screenshot review then found a misleading
“Connecting” message after panel removal; the final panel pass above includes its
fix and a stronger visible-message assertion. The first implementation exceeded
NativeScreen's JVM method-size limit; extracting the integration component fixed
that compilation failure. A later build process stopped during test-APK packaging
and was incrementally resumed after confirming that it was no longer running.
The final build succeeded in 1 m 54 s. One emulator System UI startup ANR was
dismissed and recovery visually confirmed before the final tests. Failed build
logs, prior screenshots and both attempts are retained in
`captures/runtime/panel-retention/`, alongside source/APK hashes and JUnit XML.
No new debug Activity was added; the release exclusion inventory remains ten.
Signed build **606** is unchanged and remains the latest independently verified
download. No physical Pixel was attached.

Scoped upstream references at `186cec79781256867ad4516f0802118738bd2393`:
`WorkspaceDetailView+PanelArtifacts.swift`, `PanelFileSurfaceView.swift`,
`MarkdownSurfaceModel.swift` and `MarkdownSurfaceView.swift`, under
`Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/`. They establish the
non-browsable panel loader, path/title/retry refresh identity, generation-fenced
Markdown transfer and explicit closed-panel presentation. Their SHA-256 hashes
are recorded in `captures/runtime/panel-retention/upstream-panel-source.json`;
this does not advance the global parity pin.

This change does not establish rendered-Markdown scroll/zoom, renderer or process
recovery, descriptor-refresh visual acceptance, every typed load-failure message,
physical Pixel/Mac workflows, or retention through an actual feed transport loss.
An old feed admission is retired on transport replacement and the panel refetches
after readmission; compare that lifecycle with iOS before closing the reconnect
gate. The global completion checklist remains in `REMAINING_WORK.md`.

## Save picker ownership — 2026-10-05

Save now has one Activity-owned `FileSaveModel` and a stable result launcher at
`FileSaveHost`, above conditional preview content. The toolbar delegates to that
owner. Before presenting Android's real document picker, the owner makes a
private copy with the correct suggested filename and MIME type. The pending
copy, result callback and asynchronous write survive Activity recreation and
replacement of the preview's loading/loaded call site. They do not retain an
Activity or view. A failed destination keeps the copy for retry; successful Save,
cancellation and permanent owner closure clean up only that request's directory.
Persisted write grants acquired by Save are released, preserving preexisting
write grants. Errors present recovery actions without exposing provider URLs.

**17 JVM tests passed**: seven snapshot tests, seven existing preview-file tests,
and three sharing tests. **Three Android Save checks passed in 47.142 s** on the
sole arm64 API 37 / 16 KB AVD:

- The real system picker remains open across Activity recreation and replacement
  with a loading preview. Deleting the original preview after the independent
  copy is ready does not change the exact saved Unicode bytes. The picker launches
  once, uses the expected MIME/name and releases the private copy on completion.
- Cancelling the real picker after recreation cleans up the copy, preserves the
  preview and allows a second independent Save/cancellation.
- A synthetic unavailable-provider result shows a friendly error, preserves the
  exact retry bytes and owner across recreation, then cleans up after retry is
  cancelled. This does not claim a successful retry to a second real provider.

Fourteen existing Android regressions passed in **158.618 s**: three text/media
lifecycle cases, the actual NativeScreen Files retention case, nine artifact
Files cases and the native file/Markdown-panel flow. Their six screenshots were
also inspected. Final-run crash log empty, no new ANRs, settings unchanged and
Downloads empty after generated-file cleanup. The only AVD is stopped and its
process reaped. Final sources and debug/test APKs match the recorded hashes;
no new debug Activity was added (release exclusion inventory remains ten).

All four Save screenshots were inspected. The loading label is debug fixture
content. The fixture's cancelled-picker screenshot also has dark system-bar
icons; production MainActivity separately configures light icons. Physical
system-picker return appearance remains part of Pixel acceptance.

Earlier failed attempts are retained: ActivityScenario's foreground-only recreate
helper rejected recreation while DocumentsUI held the foreground; a debug fixture
restored a stale loading flag; startup System UI ANR dialogs obscured controls;
and test cleanup attempted document deletion after closing the Activity and
losing its temporary grant. The test now requests recreation directly while the
picker is foregrounded, retains its loading fixture and deletes its generated
document before Activity closure. The final Save run passed after those fixes.

Scoped iOS references at `186cec79781256867ad4516f0802118738bd2393`:
`ChatArtifactViewerFileActionState.swift`, `ChatArtifactFileActionPresentation.swift`
and `ChatArtifactFileActionStore.swift`, under
`Packages/iOS/CmuxAgentChatUI/Sources/CmuxAgentChatUI/Artifacts/`. iOS presents
`UIDocumentPickerViewController(forExporting:asCopy:true)` for a materialized
local URL and cleans it on completion/cancellation. Exact source hashes are in
`captures/runtime/file-save-lifecycle/upstream-save-source.json`; this scoped
audit does not advance the global parity pin. Android currently exports the
displayed preview snapshot. Fresh remote-file materialization/cache semantics
still need call-site comparison before claiming that aspect of iOS parity.

The owner serializes bounded picker metadata with SavedStateHandle, but **actual
process death, interrupted large writes, provider permission transitions and
abandoned-cache cleanup after a killed process remain unverified**. This shared
viewer test does not prove full browser-parent or main Files Save flows on Pixel.
Local evidence is in `captures/runtime/file-save-lifecycle/`, including raw results,
source/APK hashes, failed attempts, screenshots and source references. Signed
build **606** remains the latest independently verified download.

## Main terminal Files retention — 2026-10-05

Main terminal Files now uses a `TerminalFilesPresentation` retained by
`NativeFeedSession`. The gallery, its admitted session, navigation and selected
preview download survive Activity recreation. A connection lease retains the
verified feed channel while the main terminal reconnects. Direct terminal-path
sheets use the same presentation with a separate selected-preview controller.
Only the selected pager file downloads; closing/backing out releases its private
files. No Activity, Android view or file bytes enter retained navigation state.

`NativeFeedCoordinator.terminalArtifactAccess` captures one exact verified Mac,
connection and terminal. It checks account/caller eligibility and terminal
membership before and after requests, cancels borrowed operations when the feed
retires, and never rebinds an old admission to a replacement connection. Main
Files hides and closes an invalid presentation; reopening creates a new scan,
so saved session routes must pass the existing session-identity checks.

Verification: **68 JVM tests** passed (six controller, seven preview files, six
artifact RPC, 45 feed coordinator, four navigation memory). The actual
`NativeScreen` terminal → Files gallery test passed in **24.449 s** on the sole
arm64 API 37 / 16 KB AVD. It verifies PDF page 2 pixels and exact bounds, the same
retained owner/file and unchanged fetch count after recreation, one in-progress
text transfer completing after another recreation, exact visible/file content,
and cleanup on Done. The nine existing Files Android regressions passed in
**76.590 s**, covering session replacement, reconnect navigation, direct relative
folder authorization, corrupt-transfer retry and share ownership.

Both screenshots were inspected. The first attempt never reached the workspace
because of an Android System UI startup ANR; its log/screenshot are preserved.
After grounded dismissal and a responsive hierarchy, the unchanged test passed.
No app crashes or subsequent ANRs; device settings unchanged; sole AVD stopped
and process reaped. Local evidence: `captures/runtime/files-retention/`, including
source/APK hashes, JUnit XML, raw Android results and screenshots.

This source change is newer than signed build **606**. This test does not close
physical Pixel/Mac acceptance, direct-tap recreation, long-text reading/search
restoration through the full Files route, account/connection revocation UI,
native Markdown-panel retention, pending Save results, rendered-Markdown state,
or process death. Continue those specific checks rather than treating the
shared-viewer or gallery PDF test as proof for every content route.

## Historical source audit before terminal Files retention (2026-10-05)

The following audit preceded the implementation above. Its native Markdown-panel
and shared Save ownership findings are addressed above for Activity recreation. The text/media tests below recreate the
shared viewer with the same local file.
Changes supplies that stable file through its retained preview controller. The
main Files flow and native Markdown panels still need equivalent integration:
`ArtifactFilesSheet` creates its gallery store in `remember`, while
`ArtifactPreviewPage` downloads in `produceState`, closes its private directory
on disposal, and keys the viewer by the local absolute path. `ArtifactPreviewFiles`
uses a new UUID directory per download. MainActivity does not handle orientation
changes itself, so recreation can redownload into a different key and discard
saved viewer state even when the remembered remote destination is restored.
This is source evidence of an outstanding integration gap, not a new runtime
result. The earlier component tests must not be described as end-to-end Files
rotation acceptance.

Next: move the gallery/download lifetime to the owning retained presentation,
keep authorization/session invalidation explicit, and test the actual Files and
Markdown-panel routes across recreation with exact content, position, selection
and fetch-count assertions. Cover an in-progress download and closing/revoking
the owner as well. Pending Save-result ownership must work across both the loading
and loaded states; saving a local path only inside `FilePreviewActions` is not
sufficient because those states currently mount it at different call sites.

## Text and media recreation — 2026-10-05

The shared artifact viewer now saves raw-text reading position as a character
anchor with a fractional line offset, horizontal position, selection, search
query/result, line-number visibility, Raw/Rendered choice and Go-to-line dialog
input. It reloads text/syntax rather than storing a document in the saved-state
bundle. Restoring a search no longer jumps back to its first result. Go waits
for the reloaded document before accepting navigation.

Media previews save their position, restore paused or active playback across
Activity recreation, pause on backgrounding, and stay paused on return. A small
saved bookmark replaces the old polling-only state; player/views are released
with the composition. Only configuration recreation saves an autoplay request.
The tests use a 30-second WAV and actual Android playback/seek positions; video
frame/codec coverage and process-death recovery are still separate gates.

Screenshot review found that the native text view could draw scrolled document
lines over the search row, toolbar and system bars. The raw-text viewport is now
explicitly clipped. A pixel assertion checks the empty center of the toolbar for
leaked document glyphs, and the final screenshot visibly confirms the correction.

**Seven Android tests passed in 94.922 s** on the sole existing 16 KB AVD:

- Paused seek at eight seconds, Activity recreation, continued playback from the
  saved position, background/foreground pause, another paused recreation,
  Restart and player release.
- Search result `2/2`, exact reading position, selected text range, line-number
  setting and unchanged document bytes across recreation; subsequent search
  wrap to `1/2`, plus the toolbar pixel check.
- Markdown Raw mode and a pending Go-to-line `120` draft across recreation,
  followed by navigation in the native source view.
- Existing three text checks for search/navigation, Unicode selection/copy,
  line-number rendering, per-kind wrap/font persistence and pinch; existing
  Changes audio prepare/play/restart/close check.

All three final screenshots were inspected. No crash entries or ANR events in
the final run; device settings unchanged; emulator stopped and reaped. The first
cold boot had a System UI startup ANR before testing and a stalled launcher was
restarted. The initial seven-test attempt had six passes and one test selector
failure: UI Automator could not see a Compose-only raw-view label. A diagnostic
screenshot proved that raw Markdown was displayed; the test now checks the
actual native view and exact source bytes. The next seven passed in 101.277 s,
but screenshot review exposed the clipping bug; the final run above includes its
fix and stronger pixel assertion. Initial build setup/import errors were fixed
before runtime testing. Original logs and screenshots are retained.

Scoped iOS references at `186cec79781256867ad4516f0802118738bd2393`:
`ChatArtifactMediaView.swift` (SHA-256
`a3d4533995547f50ffae8efdd007a6c2a44f18ae6926e905c8c040a2237f36b9`)
uses AVKit controls and releases its player on dismantle;
`ChatArtifactTextView.swift` (SHA-256
`b279ae19d45f030e94f6a60957de0c8fe092570c9b2fcfd806daa32b253b7964`)
passes search/navigation/display state into a native text container. These scoped
references do not advance the global parity pin.

Evidence: `captures/runtime/text-media-lifecycle/` (source/APK hashes, runner,
results, screenshots, settings, event/crash logs, earlier attempts and references).
The new nonexported `ArtifactPreviewTestActivity` is debug-only, bringing the
release exclusion inventory to **ten** Activities; release verification derives
this list dynamically. No signed APK was produced in this batch; **596** remains
the verified download. No Pixel was attached.

**Still open:** rendered-Markdown scroll/zoom and renderer recovery; pending Save
picker restoration; forced browser-parent recreation with binary previews;
video/codec and changed-aspect-ratio acceptance, process death, accessibility and
physical Pixel/Mac testing. Other completion gates remain in
[REMAINING_WORK.md](REMAINING_WORK.md).

## Browser parent follow-up — 2026-10-05

The routed text Changes sheet now passes recreation plus rotation in both
directions while the underlying webpage retains its JavaScript draft. The
browser also has a retained view owner, with exact DOM/history retention tested
through two Activity recreations in the production view components. See
[LOCAL_BROWSER.md](LOCAL_BROWSER.md#retained-browser-view-and-changes-rotation--2026-10-05)
for the four-test evidence and precise scope. Forced separate-process parent
recreation with a binary preview, process recovery and pending-picker/media/text/Save
restoration remain open. Signed 596 is unchanged.

## PDF navigation and image transform restoration — 2026-10-05

Short final PDF pages now get enough trailing layout space to reach the top of
the continuous viewer. Next/Previous and the page label therefore follow the
selected page instead of remaining on a sliver of the preceding page. The
spacing is recalculated from the viewport and final page dimensions.

Zoom and pan are now saved per artifact. Pan uses bounded viewport fractions
rather than storing old pixel dimensions. Real touch testing exposed a separate
one-finger pan failure in the existing gesture path: double-tap zoom worked but
the drag did not move the image. The viewer now claims touch transforms in the
initial pointer pass, after the gesture crosses touch slop. At minimum zoom it
leaves one-finger swipes to the containing file pager. Foundation's transform
modifier remains for its non-touch input handling; keyboard/mouse acceptance was
not part of this run.

**Seven Android checks passed in 79.850 s**, covering:

- Short/deleted PDF: select page two, verify `2 / 2` and disabled Next, then return
  to page one with Previous. Existing PDF rendering/base-revision checks pass.
- Four mixed-height pages (40, 40, 600, 80 points): navigate forward and backward
  through every page, check labels/visible destinations and boundary buttons,
  with one file fetch.
- A striped image: prove initial colors, double-tap zoom, one-finger pan,
  Activity recreation with the same zoomed/panned pixels, revision, artifact and
  fetch count; then reset, pinch out, reset again, and swipe into the next file.
- Retain the tall PDF page's `2 / 2` label, exact visible bounds and green pixels
  across recreation without another download. Closing the owner clears files.
- Existing retry, audio playback/release, image clipboard/export and extensionless
  image MIME checks.

Final screenshots inspected; no crash entries, no new ANR events during testing,
and unchanged device settings. The existing AVD was stopped/reaped. Each cold
boot had a System UI startup ANR before testing; the affected system component
was restarted and the fresh UI was checked before running. The first seven-test
attempt had one actual app gesture failure (pan) and six passes; its screenshot
and output are retained. The final pass follows the gesture fix.

Reference: `ChatArtifactPDFView.swift` at upstream
`186cec79781256867ad4516f0802118738bd2393`, path
`Packages/iOS/CmuxAgentChatUI/Sources/CmuxAgentChatUI/Artifacts/ChatArtifactPDFView.swift`,
SHA-256 `cdf21836a120538deb88667ee37a66b215d106f52107ece600e9b1a4f456eefc`.
That implementation uses PDFKit's vertical continuous mode and automatic scaling.
This scoped check does not establish every Quick Look/PDFKit behavior. The actual
Compose Foundation 1.9.1 source was also inspected from its
[official source artifact](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation/1.9.1/foundation-1.9.1-sources.jar)
while diagnosing consumed touch events. Global parity pins remain unchanged.

Evidence: `captures/runtime/viewer-navigation/` (source/APK hashes, failed attempt,
final instrumentation output, screenshots, device settings, event/crash logs,
upstream excerpt and source artifact). Signed **596** remains the verified APK;
this source has not yet been published in a signed build. No Pixel was attached.

**Still open:** browser-parent recreation, media position, text viewer and Save
picker restoration; PDF zoom and image focal-point behavior across aspect-ratio
changes; process death, accessibility and physical Pixel/Mac acceptance. The
short-PDF navigation and same-size image zoom/pan recreation issues described in
the earlier checkpoint below are now fixed and verified within the scope above.

## Retained binary preview — 2026-10-05

The Changes presentation now owns the selected Before/After revision, active
transfer, progress/error and private downloaded artifact. Activity recreation
reattaches to the same artifact instead of resetting the revision and downloading
again. The stable artifact key also lets the existing PDF scroll state restore.
Only the selected binary page loads content; neighboring pager items retain their
text-diff prefetch behavior. The owner retains one active artifact and at most 32
revision choices. It retains no Activity or view.

Selecting another file, returning to the file list, refreshing repository data,
forcing a diff refresh, or closing the presentation cancels the old transfer and
retires its private directory. Request identities prevent a late cancelled
transfer from publishing over or deleting its replacement. Retry creates a new
request; choices are revalidated against the changed-file policy.

### Reference and scope

Inspected `Packages/iOS/CmuxMobileChanges/Sources/CmuxMobileChanges/UI/FileDiffBinaryView.swift`
at upstream `186cec79781256867ad4516f0802118738bd2393` (SHA-256
`06a7858e203e966503b2908098ad3b3632e59bd5b5e74d9e9678e940981919a9`).
It binds the selected revision and passes it into the inline preview. This is a
scoped reference check, not an audit of every iOS preview cache/lifecycle path;
the global parity pin is unchanged.

### Verification

- **29 JVM tests passed:** retained controller 6, preview files 7, Changes store 7,
  content transfer 6 and routed sidebar Changes 3. Includes cancellation races,
  refresh, retry, revision policy, bounded choices and cleanup.
- **7 Android tests passed in 71.973 s** on the existing `cmux_api37_16k` AVD:
  new retained preview test, five existing image/PDF/audio/retry/clipboard tests,
  and the existing text-diff recreation/landscape/sign-out test.
- The new test was then strengthened after screenshot review showed that a
  partially visible second PDF page could satisfy the original pixel assertion.
  **The strengthened test passed in 28.727 s:** a tall second page retains the
  `2 / 2` label, exact visible bounds and green pixels across recreation, without
  another file fetch. The renamed image retains Before, the old path, red pixels,
  the same owner/artifact and fetch count. Closing Changes leaves no preview files.
  Production code was unchanged between these passes.
- Both final screenshots were visually inspected. Final run: no crash entries or
  ANR events; screen/sleep settings unchanged; emulator stopped and reaped.
- Three earlier attempts were obstructed by boot-time System UI/Pixel Launcher
  ANR dialogs. Logs/screenshots are preserved. The launcher was restarted and
  one abandoned synthetic preview directory from the interrupted process was
  removed before the clean seven-test run. The final cold boot and stronger test
  had no ANR event. The first instrumentation build also had a test-only
  PdfDocument Closeable mismatch, corrected with explicit try/finally cleanup.

Local evidence: `captures/runtime/changes-preview-retention/`, including original
failures, `initial-seven-pass/`, final instrumentation output, JVM XML, source/APK
hashes, device settings and screenshots. Synthetic peer fixtures only; no physical
Pixel/Mac acceptance was performed. Signed **596** remains the verified download;
this change is newer and has not been published as a signed APK.

### Remaining work

- Browser-parent recreation and process-death recovery remain unverified.
- Image zoom/pan, media position, text viewer state and an open Save picker need
  their own restoration work and tests; this change does not retain those views.
- The initial short-page PDF screenshot exposed a separate navigation issue:
  after Next page reaches the scroll limit, page two can dominate the viewport
  while the label/buttons still use the partially visible first page's index.
  Fix the current-page calculation or final-page alignment, and verify navigation
  for short/mixed page sizes. The stronger retention fixture deliberately uses
  tall pages to test a definite selected-page transition; it does not resolve
  that short-page navigation issue.
- Full format/UI/accessibility and physical Mac/Pixel validation are still open.
