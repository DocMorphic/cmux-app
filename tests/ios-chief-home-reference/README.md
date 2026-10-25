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
The next artifact also retains the built app ZIP and hashes every regular bundle
file and link, including the Debug implementation dylib. Independent verification
must compare those bytes before accepting its built-product identity; a launcher
hash alone does not identify the compiled Swift implementation.
`scripts/verify-ios-reference-bundle.py --capture <download> --output <fresh.json>`
rehashes the retained bundle without executing or extracting it. Seven checks
cover changed/missing implementation files, injected files, altered archives and
contained/escaping links. This narrow bundle receipt still requires separate
artifact/source/result binding and visual review.

Run `.github/workflows/ios-chief-home.yml` manually. Local preparation, Swift
syntax parsing and eight generator/guard tests pass. Native compilation/execution,
independent artifact/result verification, screenshot review and matched Android
acceptance are still required. This host omits the surrounding authenticated app
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
run40217714743 is active at9aa995fe.
