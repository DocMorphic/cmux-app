# Typed failure diagnostics — 2026-10-03

## Upstream contract

Scoped reference: `Packages/Shared/CMUXMobileCore/Sources/CMUXMobileCore/DiagnosticTaxonomy.swift`
at `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`. Its stable `DiagnosticFailureKind`
vocabulary uses 0–30 and 255, including separate timeout, cancellation, identity,
admission and protocol categories. Android now reserves those exact numeric codes;
this does not advance the global parity pin or imply every event producer exists.

## Implementation

`DiagnosticFailure` classifies typed Java networking/security failures and the
existing typed `IrxWire.AdmissionRejected` close code. DNS, refused connections,
unreachable hosts, TLS, access denied, protocol errors, EOF, operation timeout and
caller cancellation receive distinct categories. Iroh admission failures retain
identity/protocol/admission distinctions; keepalive timeout is separate from an
operation timeout, and superseded/user-requested closure remains distinguishable.

Ambiguous `IOException`, generic `SocketException`, RPC/HTTP errors and native
library errors stay **UNKNOWN**. The classifier does not inspect messages, causes,
addresses, codes supplied as arbitrary server strings or raw native close text.
A generic exception containing `irx:identity-mismatch` cannot become an identity
failure merely because of its message. There is no retry, routing or authorization
policy change: the original throwable continues to reach its original caller.

`MobileDebugLog.trace`, explicit RPC connection failure and disconnection now pass
the category into both the debug ring and durable recorder. Thus existing traced
SSH and browser operations also receive typed categories. Started/success records
use NONE; outcome-only failure producers use UNKNOWN, TIMEOUT or CANCELLED as
appropriate. Both existing ZIP members retain their names; new lines append
`failure=<stable code>:<fixed label>`. Old generations need no migration. Coalescing
includes the category, so adjacent DNS and TLS errors cannot collapse into one.
The bounded queue, rotation, verbose preference and shared clear barrier are reused.

## Verification

23 JVM tests passed: five new classification/export checks, eight existing storage
and recorder checks, six ring/tracing checks, and four RPC client checks. Coverage
includes exact throwable propagation, timeout versus cancellation, hostile message
exclusion, typed admission codes, coalescing boundaries and reopening ZIP history.
The ring's line-count fixture now allows 512 characters for its three enriched
lines; its separate 256-character hard-cap check is retained.

Debug APK assembly and release Kotlin compilation succeeded in **1m 48s**.
No UI or Android framework behavior changed, so no emulator was started for this
checkpoint. Evidence is ignored under `captures/runtime/diagnostic-failures/`.
Debug APK SHA-256: `5164c3c235c9d3cd264d18c969d4770e681bf0e8613773c255371ac633846c4b`.

## Remaining scope

Raw native Iroh telemetry, untyped native failure decoding, the rest of the iOS
diagnostic event/cancellation/path vocabulary and physical export acceptance are
still open. Reserved categories with no typed producer remain unused. A test
fixture proves classification and persistence, not that the Pixel has experienced
or exported each real network failure. Signed build 481 predates this change.
