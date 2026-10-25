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

Three UI tests cover eight Home states (loading, empty, startup error, pinned-only,
recent, light, Arabic and enlarged text), actual expanded-case search and original
question entry, keyboard editing, cancellation, reopening and submission through
the whole backend. Tests retain compositor screenshots and accessibility trees. A clear one-point
observation node reports authored RPC methods/parameters. The exported observation
binds the actual PNG and AX tree digests; it does not generate view pixels.
The source generator records package tree identities and source/resource hashes;
the runner requires a clean committed project, preserves result exports/build
identity and shuts down/deletes only its own Simulator.

Run `.github/workflows/ios-chief-home.yml` manually. Local preparation, Swift
syntax parsing and eight generator/guard tests pass. Native compilation/execution,
independent artifact/result verification, screenshot review and matched Android
acceptance are still required. This host omits the surrounding authenticated app
shell and real transport; it cannot establish complete app or physical parity.

Run40210978473 at822f2f5c compiled the whole original packages and host. Its three
UI tests failed because the prior debug-loopback fixture route was rejected as
insecureManualRoute. This run is preserved as failure evidence, not accepted UI
behavior. The admitted fixture-route correction needs a fresh native run.
