# Whole original Chief Home and question reference

This host links the unchanged pinned `CmuxMobileShellUI`, `CmuxMobileShell`,
`CmuxMobileRPC` and `CMUXMobileCore` packages. It instantiates the original
`ChiefHomeView` through a testable import. Navigation into a conversation uses
its actual original representable, `ChiefConversationBackend`, conversation
store/controller and private SwiftUI question sheet. The main app catalog is
copied byte for byte and compiled as a host resource.

The only service seam is a `CmxByteTransport` supplied through the real RPC
runtime protocol, using the original RPC tests' admitted-Iroh fixture route.
The factory accepts only the fixed fixture peer with no path hints; token
providers throw and any request carrying auth is rejected. This models transport
admission locally without opening an Iroh connection or proving live auth. It decodes/encodes the original frame format, holds change-wait
requests, returns authored data and rejects unexpected operations. It never
opens a socket, signs in, pairs or uses a real account. An explicit question
submission must carry exactly the reference message and default qualified choice.
The persisted RPC trace contains only fixture methods/parameters, excluding auth.

Four UI tests cover eight Home states (loading, empty, startup error, pinned-only,
recent, light, Arabic and enlarged text), actual expanded-case search and original
question entry, keyboard editing, cancellation, reopening and submission through
the whole backend. Seven additional scenes place Home inside the unchanged original
MobilePrimaryTabScaffold/search coordinator with explicitly authored unused sibling
destinations. They capture dark/light/Arabic tab context and visible1234/Int.max
badges in English and Arabic; they do not verify the authenticated WorkspaceShellView
or those sibling destinations. Tests retain compositor screenshots and accessibility trees. A clear one-point
observation node reports authored RPC methods/parameters. The exported observation
binds the actual PNG and AX tree digests; it does not generate view pixels.
The original conversation cell merges accessibility children. The question test
therefore reads the visible option's UIKit frame through the observation node
and taps its center using XCTest's physical coordinate API. This measures the
unchanged view; it does not invoke its callback or present the sheet directly.
The source generator records package tree identities and source/resource hashes;
the runner requires a clean committed project, preserves result exports/build
identity and shuts down/deletes only its own Simulator.
The runner also retains the built app ZIP and hashes every regular bundle
file and link, including the Debug implementation dylib. Independent verification
must compare those bytes before accepting its built-product identity; a launcher
hash alone does not identify the compiled Swift implementation.
`scripts/verify-ios-reference-bundle.py --capture <download> --output <fresh.json>`
rehashes the retained bundle without executing or extracting it. Seven checks
cover changed/missing implementation files, injected files, altered archives and
contained/escaping links. This narrow bundle receipt still requires separate
artifact/source/result binding and visual review.

Run `.github/workflows/ios-chief-home.yml` manually. Local preparation, Swift
syntax parsing and eight generator/guard tests pass. Native execution and artifact
verification are recorded below; matched Android acceptance remains required.
This host omits the surrounding authenticated app
shell and real transport; it cannot establish complete app or physical parity.

Run40210978473 at822f2f5c compiled the whole original packages and host. Its three
UI tests failed because the prior debug-loopback fixture route was rejected as
insecureManualRoute. This run is preserved as failure evidence, not accepted UI
behavior. Corrected run40213828169 at56204e94 passes the eight-state Home test and
expanded-case search test. Its question test reaches the actual conversation but
cannot find the merged option as an accessibility button. Nine frame sets now pass exact-source/template/catalog, regenerated-project and
PNG/AX/RPC digest checks and visual review. The executable is not in this artifact,
so its hash remains CI metadata. This accepts only scoped Home/search reference
frames; question and full-shell geometry remain open. Corrected physical-input
run40217714743 succeeds at9aa995fe: all three tests and 13 independently bound
frame sets, including the actual original question flow. Its omitted app binary
still limits built-product identity to CI metadata.

Run 40221580152 succeeds at c147788f: four tests and 20 independently bound
PNG/AX/RPC frame sets, with successful owned Simulator shutdown/deletion.
Artifact 12610303390 matches SHA256
33e4ea2963a9de9a6751d24d0bd9cb8d0f7c051236c7cda1b9d76c3f21799c75.
The complete archived app has 418 independently rehashed files, including its
ARM64 Debug implementation dylib; source/package trees, templates, catalog,
regenerated project and runner are independently bound to the exact commit.
Seven new tab-context/badge frames have been visually reviewed. Visible count
strings for 2, 1234 and Int.max in English and ar-SA are extracted from those
verified AX trees into
`app/src/androidTest/assets/parity/chief-home-visible-badges.json`. Arabic uses
U+066C grouping separators; its separate spoken unread summary follows L10n.
This reference does not establish full-shell, real-transport or physical parity.
