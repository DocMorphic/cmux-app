# Attribution

Mac pairing setup guidance and the unmodified MacSettings-dark.png and
MacSettings-light.png images follow OnboardingPairingView.swift,
OnboardingPairingSettingsScreenshot.swift and OnboardingConnectionView.swift in
Packages/iOS/CmuxMobileShellUI at cmux 0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc.
Copyright Manaflow, Inc.; GPL-3.0-or-later. Android explains the existing Mac
setting's iOS label and uses its own account/connection controls.

Consumer Mac build admission follows MobileMacBuildCompatibilityPolicy.swift,
MobileMacCompatiblePairedMacStore.swift and MobileShellComposite+BuildCompatibility.swift
at cmux 0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc.
Copyright Manaflow, Inc.; GPL-3.0-or-later. Android explicitly selects the
distributed consumer audience for debug and release, retaining its own identity.

Mac minimum-version compatibility follows MobileMacCompatPolicy.swift and its DTOs,
MobileMacAppVersion.swift, MobileMacVersionCompatibility.swift, MobileMacCompatCenter.swift
and MobileShellComposite+BuildCompatibility.swift at cmux
0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc. Copyright Manaflow, Inc.; GPL-3.0-or-later.
Android explicitly maps its Iroh v2 protocol to the reviewed iOS 1.0.6 production
policy profile, retaining its own app identity. Android admission, pooled-wire
revalidation, bounded caching and update guidance are implemented in Kotlin.

Saved reconnect rows follow DisconnectedWorkspaceShellView.swift and the shared
MacComputerSnapshot projection in Packages/iOS/CmuxMobileShellUI at cmux
0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc. Copyright Manaflow, Inc.; GPL-3.0-or-later.
Android retains saved pairings during discovery outages, adds only unambiguous
new device/build rows, and rechecks current pairing and account authority.

Computer method sections, endpoint captions and older-pairing labels follow
MacComputerListSection.swift, DeviceTreeRouteDescription.swift, MacComputerRow.swift
and MacComputerSnapshot+Store.swift in Packages/iOS/CmuxMobileShellUI at cmux
0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc. Copyright Manaflow, Inc.; GPL-3.0-or-later.
Android projects existing per-account settings and exact device/build routes for
display only, retaining its existing connection authorization and transport logic.

Computer presence rows follow MacComputerRow.swift and MacComputerSnapshot+Store.swift
in Packages/iOS/CmuxMobileShellUI, plus PresenceMap.swift in CmuxMobileShell,
at cmux 0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc.
Copyright Manaflow, Inc.; GPL-3.0-or-later. Android keeps phone connection and
server heartbeat separate, with verified last-seen history stored independently
of pairing identity inside the existing encrypted account state.

Mac build subtitles follow MacBuildChannel.swift, LocalizedMacBuildLabel.swift,
MobileShellComposite+PairedMacAliases.swift and WorkspaceMacTitlePickerMenuButton.swift
at cmux 0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc.
The foreground display-metadata subscription follows PresenceClient.swift,
PresenceServiceConfiguration.swift, PresenceUpdate.swift and PresenceMap.swift
at that revision. Copyright Manaflow, Inc.; GPL-3.0-or-later.
Android adds bounded parsing, account/lifecycle cancellation and current-scope
checks; presence display metadata never authorizes a route or pairing.

`NativeComputerSelector.kt` follows the per-opening presentation and callback
snapshot in `WorkspaceMacTitlePickerMenuButton.swift` and
`WorkspaceMacTitlePickerMenuTests.swift` under `Packages/iOS/CmuxMobileShellUI`
at cmux `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`
(GPL-3.0-or-later, Manaflow, Inc.). Android independently checks current account
scope and exact saved pairing before invoking a captured menu action.

Pending picker selection and cancellation in `NativeScreen.kt` and
`NativeMacSwitchRecovery.kt` follow `WorkspaceListView.swift`'s
`handleMacTitlePickerSelection`, `cancelMacTitlePickerSwitch` and
`applyMacTitlePickerSelection`, together with the shell's restore-generation
checks, at cmux `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`
(GPL-3.0-or-later, Manaflow, Inc.). Android uses cancellable Compose effects and
revalidates its existing saved-route permissions when restoring.

Launch pairing precedence and fallback in `NativeScreen.kt` also follow the
production `CMUXMobileRootView.swift` deferred-link and reconnect flow at cmux
`0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc` (GPL-3.0-or-later, Manaflow, Inc.).
`MobileStartupConnectionCoordinator.swift` and `MobileInjectedAttachStartupTests.swift`
were reviewed as development-launch context; their injected task runner is not
ported. Android retains its explicit Tailscale confirmation and account directory checks.

`NativeMacSwitchRecovery.kt` and the picker recovery integration follow cmux's
live foreground baseline and superseding switch-attempt behavior in
`MobileShellComposite.swift`, `MobileShellComposite+MacSwitchState.swift` and
`IrohMacSwitchRecoveryTests.swift` at
`0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc` (GPL-3.0-or-later, Manaflow, Inc.).
Android reconnects through its existing authenticated connector and rechecks the
exact saved route, instance and account/team before restoring the previous Mac.

`NativeMacColorSlots.kt` follows the additive app-instance palette assignment and
scope pruning in cmux's `MobileWorkspaceAggregation`, `MacPairingKey` and
`MobileShellComposite+MacSwitchState` at
`0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc` (GPL-3.0-or-later, Manaflow, Inc.).
Android retains the display-only table in the screen ViewModel and filters new
discovery rows against the current account/team before assigning colors.

Firebase Cloud Messaging Android SDK 25.0.1 is used without modification.
Copyright Google LLC; Apache License 2.0. Source:
https://github.com/firebase/firebase-android-sdk/tree/main/firebase-messaging
The full Apache 2.0 license is included in the app's license viewer. Project
configuration and token registration are not included in this checkpoint.
AndroidX DataStore 1.2.1 is used without modification (Apache License 2.0),
overriding the older transitive version for 16 KiB native alignment.

`LocalBrowserAddress.kt`, `LocalBrowserState.kt`, `LocalBrowserPane.kt` and
`LocalBrowserWebHost.kt` follow cmux's `Packages/iOS/CmuxMobileBrowser`
resolver, surface/store and pane behavior at revision
`4c5272e9153eca2033c9f40ac749f0c3a5bcb291`. Copyright (c) 2024-present
Manaflow, Inc.; GPL-3.0-or-later. Android adds platform ICU domain conversion,
view-attachment callback fencing and account/team/Mac/workspace scoping.
`scripts/generate-local-browser-fixtures.py` runs the unmodified Swift resolver
and records its source hash with the reference address corpus. ICU4J 77.1 is a
JVM test-only dependency; Android uses its built-in ICU implementation.
`LocalBrowserNavigation.kt` and `LocalBrowserWorkspaceView.kt` also adapt the
pinned `WorkspaceDetailView` browser creation/fallback, tab selection and restore
behavior from `Packages/iOS/CmuxMobileShellUI`, with Android lifecycle and
multi-Mac account scoping.

The Android byte/hybrid terminal path uses the Ghostty VT core from
https://github.com/manaflow-ai/ghostty at
`edefce7785c9f439966c68588db1edbd6b435203`, referenced by the pinned cmux source.
Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors; MIT license.
Changes: Android RELRO common-page-size build setting, an independently written
JNI ownership/snapshot binding, and a Kotlin/Canvas adapter. Upstream and bundled
dependency notices are packaged in the app's Ghostty license entry. See
`docs/GHOSTTY_VT_ANDROID.md` for build provenance and verification.

`IrohV2SigningCodec.kt` follows the canonical signing contract in cmux's
`Packages/Shared/CmuxIrxTransport/Sources/CmuxIrxTransport/V2/V2WireSigningCodec.swift`
at `4c5272e9153eca2033c9f40ac749f0c3a5bcb291`. The unchanged public test vectors
under `app/src/test/resources/iroh-v2` come from the same package's V2 test
fixtures (GPL-3.0-or-later, Manaflow). Their deterministic test seed is not an
application or user credential and is not included in production app assets.

`app/src/main/res/drawable-nodpi/cmux_logo.png` is copied without modification from
`ios/cmux/Assets.xcassets/CmuxLogo.imageset/cmux-logo@3x.png` in the
[cmux source repository](https://github.com/manaflow-ai/cmux), commit
`e7f1c40bf0d05b6fdaf8c48d2e834897640a3cc5`.

Copyright © 2024–present Manaflow, Inc. The source repository states that,
unless otherwise noted, its files are licensed under GPL-3.0-or-later.
The full GPL text is in [LICENSE](LICENSE).

This project is an unofficial personal Android companion and is not endorsed
by Manaflow or the cmux team. The cmux name and logo belong to their owners.

Workspace hierarchy, move policy, and optimistic ordering/reconciliation are
translated from CmuxMobileShellModel in https://github.com/manaflow-ai/cmux,
revision 4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
The reference test cases are generated by running that revision's unmodified
Swift algorithms; scripts/generate-workspace-parity.py records source hashes.

Unread count state and badge behavior are translated from cmux's
MobileWorkspaceUnreadState.swift and WorkspaceUnreadDot.swift at the same pinned
revision. The Swift reference tests include exact and unknown aggregate counts.

Workspace vector symbols are adapted from Lucide Icons, revision
66d8f9fc394b8530377e5f6112f0b8908ba01280, https://github.com/lucide-icons/lucide.
Copyright (c) 2026 Lucide Icons and Contributors; ISC license. Some symbols derive
from Feather, Copyright (c) 2013-present Cole Bemis; MIT license. Complete notices
are in third_party/lucide/workspace-icons/LICENSE and the app's Lucide.txt license.
Changes: converted SVG geometry to Android vectors; filled folder and pin variants.

Task model catalog parsing, refresh priority, selection rules and provider command
option rewriting are derived from cmux's MobileTaskAgentProvider,
MobileTaskModelCatalogClient, MobileShellComposite+TaskModels,
MobileTaskModelRefreshLoop and TaskComposerSheet+ModelSelection at revision
4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0. Copyright (c) 2024-present Manaflow, Inc.;
GPL-3.0-or-later. The task command reference generator runs the unmodified Swift
provider code and records its hash alongside 695 cases.

Task submission request equivalence and retry identity are translated from
MobileTaskSubmissionIdentity and MobileTaskSubmissionSnapshot at cmux revision
4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0. Created-workspace response validation is
based on MobileShellComposite+WorkspaceCreateRequest; partial list merging follows
MobileShellComposite.setForegroundWorkspaceState at the same revision.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.

Saved task draft content, the newest-first bounded collection, leave/save/delete
choices and restore behavior are derived from MobileTaskComposerDraft,
MobileTaskComposerSavedDraft, MobileTaskTemplateStore and TaskComposerSheet at
cmux revision 4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android additions: Keystore encryption, ordered lifecycle writes and editor leases.

Completed-task recovery is derived from TaskComposerCompletedOperationRecovery,
TaskComposerSheet+CompletedOperationRecovery and TaskComposerSheet+DraftState at
cmux revision 4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.

Editable templates, protected shipped identities, editor controls and successful
task defaults derive from MobileTaskTemplate, MobileTaskTemplateStore,
TaskTemplateEditorView, TaskTemplateFormView and TaskComposerSheet+Policies at
cmux revision 4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0.
Bundled Claude, Codex and OpenCode images are copied unchanged from that revision's
CmuxMobileShellUI/Resources/AgentIcons; source paths and SHA-256 hashes are recorded
in docs/TASK_TEMPLATE_ASSETS.json. Brand names and images identify their agents.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.

Task Options, workspace naming/group routing, folder browsing and search derive
from TaskComposerOptionsSheet, TaskComposerContextSection, TaskComposerSheet,
TaskComposerDirectoryBrowseState/Screen/PickerView, MobileTaskDirectorySuggestion,
MobileTaskDirectoryListRequest/Response/Entry, MobileTaskDirectorySearchResponse
and MobileTaskSubmissionSnapshot at cmux revision
4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0. Directory ranking reference cases execute
the unmodified Swift source; its SHA-256 is included in the fixture.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.

Terminal momentum behavior references `GhosttySurfaceView.swift` in
`Packages/iOS/CmuxMobileTerminal` at upstream commit
`4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0`: whole-line fractional carry,
a 450 ms alternate/legacy-scroll momentum budget, and cancellation on user input.
The Android implementation uses Android spline decay rather than UIKit mechanics.
Upstream copyright Manaflow (2024–present), GPL-3.0-or-later.

Terminal Files wire models, gallery snapshot merging and eager paging are adapted
from ChatArtifactGallery*, TerminalArtifactScanResponse, TerminalArtifactFilesSheet
and MobileChatEventSource in cmux revision
4c5272e9153eca2033c9f40ac749f0c3a5bcb291.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android additions include explicit immutable authorization scopes, rejection of
foreign session identities, and immediate-child path validation.

Terminal Files layout, kind filters, stable sorting, folder navigation and file
preview transfer rules are adapted from TerminalArtifactFilesSheet,
ChatArtifactGalleryPresentation/Classifier/SwipeOrder, ChatArtifactFolderView,
ChatArtifactChunkValidator and ChatArtifactTemporaryFileWriter at cmux revision
4c5272e9153eca2033c9f40ac749f0c3a5bcb291.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android uses Compose, bounded bitmap/PDF decoders, native media playback and a
scoped FileProvider for exported copies. Changes keeps its fingerprint validation;
standard terminal/session artifact transfers use the upstream size/offset/EOF contract.

Terminal Files chip count/visibility and conservative local path detection are
translated from TerminalArtifactChipCountState, TerminalArtifactChipVisibilityState,
TerminalArtifactPathDetector and GhosttySurfaceCoordinator+Artifacts at cmux commit
4c5272e9153eca2033c9f40ac749f0c3a5bcb291. The generator
scripts/generate-artifact-count-parity.py executes unmodified Swift policies and
records their source hashes with the reference cases.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.

Terminal path tap detection and soft-wrap stitching derive from
TerminalArtifactTapHitTester at cmux commit
4c5272e9153eca2033c9f40ac749f0c3a5bcb291. The unmodified Swift implementation
produces 1,217 reference cell hits through scripts/generate-artifact-tap-parity.py.
Android uses its renderer's grapheme cell widths and retains terminal authorization
for relative paths and folder descendants.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.

Terminal artifact row Share and folder-tap preference behavior are adapted from
TerminalArtifactGalleryItemView, ChatArtifactFileActionStore,
TerminalFolderTapPolicy and GhosttySurfaceCoordinator+Artifacts at cmux revision
4c5272e9153eca2033c9f40ac749f0c3a5bcb291.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android shares original bytes through read-only FileProvider grants and preserves
terminal authorization when a classification refusal opens the error viewer.

The Markdown viewer bundles the unmodified cmux shell, navigation code, marked,
highlight.js, GitHub CSS, Mermaid, Vega, Vega-Lite and Vega-Embed assets from
cmux commit 4c5272e9153eca2033c9f40ac749f0c3a5bcb291. Exact source hashes are in
app/src/main/assets/markdown-viewer/manifest.json. Third-party versions, copyright
notices and MIT/BSD license text are in app/src/main/assets/licenses/Markdown.txt.
The cmux renderer, rendering threshold, mobile link behavior and remote-image
policy are copyright (c) 2024-present Manaflow, Inc., GPL-3.0-or-later.
Android additions include WebView lifecycle integration, a restricted local bridge,
and DNS-validated HTTPS image transport using OkHttp 4.12.0 and Okio 3.6.0
(Square and contributors, Apache-2.0; full license bundled in Apache-2.0.txt).

The artifact text viewer adapts ChatArtifactViewerActionsMenu, ChatArtifactSearchModel,
ChatArtifactLineIndex, ChatArtifactTextLayoutKind and ChatArtifactTextPreferences
from cmux commit 4c5272e9153eca2033c9f40ac749f0c3a5bcb291.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android additions provide a native selectable buffer, line-number gutter, search
highlighting, two-axis scrolling, text-size controls and per-kind preferences.

Raw artifact syntax policy and native attribute application adapt cmux's
ChatArtifactSyntaxHighlightPolicy/Highlighter/TextViewCoordinator at
4c5272e9153eca2033c9f40ac749f0c3a5bcb291 (Manaflow, 2024-present,
GPL-3.0-or-later). The raw-code assets are copied from Highlightr 2.3.0 at
05e7fcc63b33925cd0c1faaa205cdd5681e7bbef, the exact dependency pinned by iOS.
They contain highlight.js 11.11.1 and the Xcode palettes. RawCode.txt contains
the MIT and BSD licenses; raw-code/manifest.json records exact asset hashes.

The `iroh` module, now a main app dependency, builds manaflow-ai/iroh-ffi at
ee19f156667ca640b912108a45f8b5bb8d156fec (MIT OR Apache-2.0), including its
matching generated UniFFI Kotlin bindings and Android context initializer.
Native build receipts record Cargo.lock and all delivered file hashes. The module
packages upstream LICENSE-MIT and LICENSE-APACHE under assets/licenses/iroh.
JNA 5.17.0 is used under Apache-2.0; its Android AAR retains upstream notices.

AndroidX graphics-path 1.1.0 retains its official Java classes and resources. Its
arm64 JNI library is rebuilt from unchanged AndroidX source revision
`7b1104d5e67bd061e736e8d576b539498b498be4` with 16 KiB RELRO alignment.
Source attribution and combined Apache/BSD notices are in
`third_party/androidx-graphics-path` and the app's open-source licenses.
The main debug APK now includes Iroh/JNA for arm64. No updated signed release has been published.

IrxWire, IrxClientSession, IrxEndpointRuntime and IrxMobileRpcTransport adapt the
cmux IrxProtocol, IrxAdmission, IrxEndpoint, IrxServerEventLaneHub and
MobileIrxRuntimeComposition+Dial contracts at
4c5272e9153eca2033c9f40ac749f0c3a5bcb291. Copyright (c) 2024-present
Manaflow, Inc.; GPL-3.0-or-later.

IrohInstallationStore follows cmux V2IdentityKeyStore/V2InstallationIDStore's
full-scope identity contract at 4c5272e9153eca2033c9f40ac749f0c3a5bcb291
(Manaflow, GPL-3.0-or-later), with Android Keystore wrapping and atomic private
file persistence in place of the iOS Keychain implementation.

IrohV2ControlTransport and IrohV2SignedRequests adapt the cmux V2 control-service
wire contracts at 4c5272e9153eca2033c9f40ac749f0c3a5bcb291 and deployed Worker
e0263f46a6698bf7d74e828b6200e4bfa963a3dc (Manaflow, GPL-3.0-or-later).
MockWebServer 4.12.0 is a JVM test dependency only (Square, Apache-2.0).

IrohV2ControlSession and NativeAccountTeams follow the cmux V2 service and
authenticated team-scope lifecycle contracts at
4c5272e9153eca2033c9f40ac749f0c3a5bcb291 (Manaflow, GPL-3.0-or-later).
Account HTTP paths and payloads follow the Stack Auth client API used by that
revision of the bundled Swift SDK; no Stack SDK code is compiled into this app.

NativeTodo/NativeTodoView and the simulator stream protocol, input outbox, session
and lifecycle policies adapt the cmux iOS/Shared contracts at
4c5272e9153eca2033c9f40ac749f0c3a5bcb291. Copyright (c) 2024-present
Manaflow, Inc.; GPL-3.0-or-later. Android simulator wire fixtures are generated
with the unmodified pinned Swift codec; source hashes accompany the fixtures.

Simulator video software decoding uses FFmpeg n9.0.2 at
946fcce07b6dcd0331c8cc609192aeff5e1924f8 (FFmpeg contributors), licensed under
LGPL-2.1-or-later. Source: https://github.com/FFmpeg/FFmpeg/tree/946fcce07b6dcd0331c8cc609192aeff5e1924f8
Only native AVC/HEVC decoders, avutil and swscale are enabled; no external codec,
network, muxer, encoder, GPL or nonfree component is enabled. Upstream source is
unchanged. Build configuration and Android JNI source are provided in this repo:
scripts/build-simulator-codecs.py, scripts/build-simulator-video-jni.py and
app/src/main/c/simulator_video_jni.c. License texts are bundled under
licenses/simulator-video and included in the app's open-source licenses.

`SshTmuxProtocol.kt` and `SshTmuxControl.kt` adapt control-mode parsing, grouped
session attachment, pane seeding and geometry behavior from cmux's
`MobileSSHTmuxControlParser.swift` and `MobileSSHTmuxControlClient.swift` at
`204a11dfcc76280205e50406ab94270a1c152155`. Copyright (c) 2024-present Manaflow, Inc.;
GPL-3.0-or-later. Android adds bounded replies/queues, exact reply guards,
command deadlines, canceled-reply fencing and coroutine-owned stream cleanup.

The tmux workspace provider and pane UI follow MobileSSHTmuxProvider.swift at
204a11dfcc76280205e50406ab94270a1c152155 (Manaflow, GPL-3.0-or-later), with Android
account-owned sessions, server/session identity guards, coroutine ownership and
Ghostty pane rendering.

`SshCmuxControl.kt` adapts the cmux-tui wire, attachment and geometry contract from
`Packages/iOS/CmuxMobileSSH/Sources/CmuxMobileSSH/CmuxTUI/CmuxTUIControl.swift`
and `cmux-tui/spec/commands.md` at
`204a11dfcc76280205e50406ab94270a1c152155`. Copyright (c) 2024-present Manaflow,
Inc.; GPL-3.0-or-later. Android adds bounded framing and queues, strict numeric
identity checks, deadlines, coroutine ownership and explicit cancellation fences.

`SshCmuxRemote.kt` and `SshCmuxInventory.kt` adapt discovery commands and workspace
wire/model shapes from `CmuxTUIRemote+Discovery.swift`, `CmuxTUIModels.swift` and
`CmuxTUIWire.swift` in that same upstream directory/revision. Copyright (c)
2024-present Manaflow, Inc.; GPL-3.0-or-later. Android adds bounded snapshots,
hashed-owner verification, ordered typed layouts and conservative durable
selection restoration across owner generations.

`SshCmuxProvider.kt`, `SshCmuxHosts.kt` and `SshCmuxTerminal.kt` follow the same
revision's `CmuxTUIControl.swift` and `MobileSSHCmuxTUIProvider.swift` lifecycle,
creation, termination, replay and geometry behavior (Manaflow, GPL-3.0-or-later).
Android adds account/coroutine ownership, bounded retained renderers and input,
content-set confirmation checks and exact resource-session resolution.


`SshCmuxInstall.kt` and phone-owned session creation follow the explicit-install
workflow in `MobileSSHCmuxTUIProvider.swift` / `MobileSSHHostProviders.swift` at
`204a11dfcc76280205e50406ab94270a1c152155` (Manaflow, GPL-3.0-or-later). Android adds
application-pinned platform SHA512 digests, bounded cancellable downloads, SFTP
staging, version verification and activation that refuses to overwrite an existing
binary. Downloaded cmux-tui npm platform packages identify their license as MIT;
they are fetched on demand and are not embedded in this repository or APK.

TerminalSizing.kt, TerminalSizingControls.kt and TerminalSizeSheet.kt adapt shared
sizing wire models, MobileTerminalSizingSurface, MobileTerminalSizingPresentation
and the iOS TerminalSizeSheet
from cmux revision 0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc:
Packages/Shared/CmuxTerminalSizing, Packages/iOS/CmuxMobileShellModel and
Packages/iOS/CmuxMobileShellUI.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android adaptation uses Kotlin/JSONObject and conservative unknown-detach handling.

TerminalSizingChrome.kt and TerminalSizingOverlay.kt adapt TerminalSizingChromeGate,
TerminalSizingBoundsGeometry, TerminalSizingBorderEdges, TerminalSizingChipPlacement
and GhosttySurfaceView+SharedSizing from Packages/iOS/CmuxMobileTerminalKit and
Packages/iOS/CmuxMobileTerminal at the same 0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc
revision. Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android uses Compose drawing, its actual fitted render rectangle, theme contrast
adjustment and connection/viewport-scoped confirmation before displaying chrome.

TerminalSharedGridLayout.kt and the shared-grid pinch path in TerminalZoomView.kt
adapt TerminalGridFit.swift, TerminalLetterboxGeometry.swift and
GhosttySurfaceView+ScaledGrid.swift from the same cmux revision above.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android adapts the layout to Compose pixels and shares geometry with Canvas and
input hit-testing. scripts/generate-terminal-layout-fixtures.py compiles the
unmodified upstream math to produce JVM test-only reference data with source hashes.

TerminalKeyboardLayout.kt adapts TerminalKeyboardViewport and the blank-space and
top-reveal functions in TerminalLetterboxGeometry at that same cmux revision.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later. Android translates the
geometry into its clipped viewport and converts the scroll axis to distance from
the live bottom. The reference fixture generator also compiles these Swift functions.

TerminalViewportGeometryFence.kt adapts TerminalViewportGeometryFence.swift at
cmux revision 0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android uses Compose frame callbacks and Android's IME animation target insets.

KeyboardTransitionPresentationFreeze.kt adapts the same-named Swift transaction
at cmux revision 0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android binds the milestones to viewport generations, output revisions and
Compose display-list recording before atomically choosing the frame to draw.

TerminalContentBottom.kt extends the keyboard content-bottom scan from
GhosttySurfaceView.swift at cmux revision 0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android scans owned spans under a bounded work budget and includes copied Ghostty
image placement bounds, sharing the visible rows with its Canvas renderer.

SshKeyInstaller.kt adapts the one-time key installation command and separate
key-only verification workflow from SSHKeyInstaller.swift in
Packages/iOS/CmuxMobileSSH, cmux revision
0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android adds a separator for files without a final newline, sanitized stage
errors, explicit retry and Android lifecycle/host/key/account ownership checks.

SshImageNames.kt and SshFiles.uploadImage adapt the SSH image upload destination
and basename contract in MobileShellComposite+SSHPaste.swift, cmux revision
0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android resolves HOME and creates folders through SFTP on one pinned channel,
uses private file modes and collision-safe publication, and preserves uncertain
upload outcomes without reconnecting or automatically retrying.

SshComposerDrafts.kt and SshTerminalInput.kt adapt pending-image staging and
ordered submission behavior from MobileShellComposite.swift and the SSH paste
flow in MobileShellComposite+SSHPaste.swift at cmux revision
0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android uses bounded account-owned drafts, Android content grants and an ordered
input lane with explicit recovery, lifecycle cancellation and SSH route identity.


NativeComputersScreen.kt and the combined NativeScreen/SshComputersScreen
management destination adapt the Computers navigation, Mac details entry,
inline SSH section, Pair Mac/Add SSH menu and Done behavior from
DeviceTreeView.swift in Packages/iOS/CmuxMobileShellUI, cmux revision
0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android retains its scoped connection runtime, existing SSH editor and explicit
legacy TCP reconnect flow. Hidden rows and complete refresh/layout parity remain
separate work.


NativeComputerVisibility.kt and the visibility switches/hidden row section in
NativeComputersScreen.kt and NativeScreen.kt adapt local exact-computer hide and
show behavior from MobileShellComposite+HiddenMacs.swift, ComputerVisibilityToggle.swift,
HiddenComputerRow.swift and DeviceTreeView.swift, cmux revision
0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android keeps visibility in its encrypted local account state, with exact stored
origin/alias and owner/build matching, synchronous guarded persistence and
foreground/feed/terminal/browser retirement checks.

PDF annotation/destination parsing uses PdfBox-Android 2.0.27.0 (Apache-2.0),
https://github.com/TomRoush/PdfBox-Android, tag v2.0.27.0 /
45da92629dad5b3c9887eceefecc89a1423f5457. License/notice texts are included in
third_party/pdfbox-android and the in-app licenses. Rendering remains Android
PdfRenderer. No embedded-file extraction or script execution is used.

TerminalComposerDelivery.kt follows the explicit paste-submit acknowledgment
contract in MobileTerminalPasteResponse, MobileTerminalInputResponse.swift,
Packages/iOS/CmuxMobileRPC, cmux revision
c2715faa02c260b07012bc0b386597cfb333021d.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android retains its existing draft ownership, queue and no-automatic-retry rules.

TerminalDeviceIdentity.kt and shared terminal RPC identity fields adapt name
sanitization and stable device-priority identity from MobileTerminalDeviceIdentity.swift
(CmuxMobileShellModel) and MobileShellComposite+TerminalSizing.swift, cmux revision
c2715faa02c260b07012bc0b386597cfb333021d.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android uses a random installation UUID stored outside backups and retains the
upstream unknown device kind rather than claiming an Apple device identity.

CloudTerminalOutputReducer.kt adapts CloudTerminalOutputReducer.swift from
Packages/iOS/CmuxMobileCloud, cmux revision
c2715faa02c260b07012bc0b386597cfb333021d.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android splits writes at its existing renderer JNI boundary. The Cloud native
builder separately pins the same cmux Rust workspace and its Ghostty submodule;
the resulting Android C ABI/JNI libraries and platform-verifier component are
packaged with their hashed dependency notice inventory. Account-owned transport
and common-workspace mounting remain in progress. Supplemental source attribution
and exact license provenance are recorded in third_party/cloud-notices/README.md.

CloudMachineHandshake.kt adapts attach/approval ordering and lifetime behavior from
CloudMachineConnection.swift in Packages/iOS/CmuxMobileCloud, cmux revision
c2715faa02c260b07012bc0b386597cfb333021d.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android uses coroutine account ownership and closes late blocking-native results.

CloudTunnelIdentity.kt and CloudWireGuardConfig.kt adapt CloudDeviceIdentity.swift,
CloudDeviceIdentityResolver.swift, WireGuardKeyPair.swift and WireGuardQuickConfig.swift
from Packages/iOS/CmuxMobileCloud, cmux revision
c2715faa02c260b07012bc0b386597cfb333021d.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android uses a truthful installation fingerprint, Keystore encryption, atomic
no-backup storage and fail-without-replacement handling for unreadable identities.

CloudModels.kt and CloudApi.kt adapt CloudMachine.swift, CloudAPIRequestBuilder.swift,
CloudAPIResponseDecoding.swift, CloudTunnelPurpose.swift and CloudVMService.swift
from Packages/iOS/CmuxMobileCloud, cmux revision
c2715faa02c260b07012bc0b386597cfb333021d.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android uses its native account/team ownership and OkHttp transport. Source and
remaining composition/transport/UI scope are recorded in docs/CLOUD_COMPANION.md.

CloudMachinesController.kt adapts machine catalog, lifecycle, retry and pending-create
behavior from CloudSessionController.swift and CloudSessionPhase.swift in
Packages/iOS/CmuxMobileCloud, cmux revision
c2715faa02c260b07012bc0b386597cfb333021d.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
CloudCreateJournal.kt adds Android persistence for the pending request identity;
tunnel/attachment ownership and UI integration remain separate work.

NativeCloudScreen.kt, CloudCreatePresentation.kt and the Cloud primary tab adapt
machine-management UI and size/usage presentation from CloudSectionView.swift and
CloudFlowView.swift in Packages/iOS/CmuxMobileCloudUI, cmux revision
c2715faa02c260b07012bc0b386597cfb333021d.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android uses its retained account/team owner, Compose navigation, external pricing
page and explicit lifecycle controls. The remaining tutorial, transport, subscription
and UI acceptance work is recorded in docs/CLOUD_COMPANION.md.


CloudWorkspaceCatalog.kt and CloudWorkspaceController.kt adapt SessionCatalog.swift
and TerminalCatalog.swift in Packages/Shared/CmuxTerminalClient, CloudTerminalTransport.swift
in Packages/iOS/CmuxMobileCloud, and CloudAddress.swift, CloudWorkspaceProjector.swift
and catalog lifecycle behavior from CloudWorkspaceBridge.swift in
Packages/iOS/CmuxMobileCloudBridge, at cmux revision
c2715faa02c260b07012bc0b386597cfb333021d.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android uses retained account ownership, IO workers, explicit stale-read fences and
its existing workspace/terminal presentation models. Integration evidence and
remaining attachment/UI work are recorded in docs/CLOUD_COMPANION.md.


CloudTerminalAttachment.kt adapts single-slot attachment, early input, ordered output
and resize-repaint behavior from CloudWorkspaceBridge.swift in
Packages/iOS/CmuxMobileCloudBridge at cmux revision
c2715faa02c260b07012bc0b386597cfb333021d.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android adds explicit native attachment tokens and coroutine ownership fences.


CloudWorkspaceCreation.kt and the creation hooks in CloudWorkspaceController.kt
adapt workspace/terminal creation and optimistic catalog publication from
CloudWorkspaceBridge.swift in Packages/iOS/CmuxMobileCloudBridge at cmux revision
c2715faa02c260b07012bc0b386597cfb333021d.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android uses an account-owned coroutine, lifecycle epochs, explicit native C ABI
operations and the common Android create menus. Verification and remaining work
are recorded in docs/CLOUD_COMPANION.md.


CloudMachineVisibility.kt adapts hidden-machine persistence and authoritative
inventory reconciliation from CloudSessionController.swift in
Packages/iOS/CmuxMobileCloud, with the visibility scope supplied by
MobileCloudComposition.swift, at cmux revision
c2715faa02c260b07012bc0b386597cfb333021d.
Copyright (c) 2024-present Manaflow, Inc.; GPL-3.0-or-later.
Android uses account-scoped SharedPreferences and the common Computers screen.
