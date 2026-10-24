# SDK JSON response bytes and cache extensions

SDK responses now pass their bytes through `NativeAuthJson` before user/team
projection. The previous UTF-8 text conversion and platform JSONObject parser
could accept a different user, eligibility flag or membership list from the
original Foundation reader. This decoder applies to NativeOAuthClient response
and error-code JSON, and to the SDK portion of NativeAccountTeams. Backend create
responses retain their separate existing decoder/bounds. Terminal/cloud cursor
JSON continues to use its existing exact-integer protocol decoder.

## Measured reader behavior

The reference executes the pinned SDK's complete CurrentUser/Team initializers
and listTeams declaration, with whole original User, CMUXAuthUser and shared-cache
sources. An inert APIClient supplies exact bytes. The user parse guard calls the
same JSONSerialization API; it does not execute the complete original getUser,
API transport, retries or timers.

The measured host is macOS 26.5.2, build 25F84, CoreFoundation 5026.5. This machine
has Command Line Tools but no Xcode/iOS Simulator SDK installed, so the fixture
does not prove iOS-runtime equivalence or behavior across Foundation versions.
The host binding is included in the generated fixture. The separate open-source
[Swift corelibs implementation](https://github.com/swiftlang/swift-corelibs-foundation/blob/main/Sources/Foundation/JSONSerialization.swift)
is supplementary reading, not the executed Darwin oracle.

The exercised grammar permits one trailing comma and keeps the first duplicate
key, while validating later duplicate values too. Bare keys, single quotes,
comments, trailing material, raw controls and malformed Unicode strings are
rejected. Byte decoding detects the measured UTF-8/16/32 forms independently of
HTTP Content-Type. It also reproduces the measured partial final UTF-16/32 unit
handling and rejected UTF-32LE BOM form. Nesting and number boundaries come from
executed cases, including exponent syntax, decimal precision/range and rounding
that changes NSNumber-to-Bool eligibility projections near one.

The Darwin reader accepts certain negative overflows. JSONObject cannot store a
nonfinite java.lang.Number, so this adapter retains an opaque numeric marker
rather than supplying a string or Boolean to typed SDK fields. The matrix checks
input acceptance and the app's typed user/team/eligibility projections. It does
not assert general NSNumber bit-pattern equivalence, arbitrary parsed-value
re-serialization or full metadata-consumer parity.

## Caller and cache behavior

Invalid primary-user JSON follows the existing nil-user or interactive error
projection. A failed membership-user read preserves only its already-loaded
current-session membership. Invalid JSON in a successful team-list response
counts as loaded empty membership, retiring prior connection scope and proofs.
It does not become a transport failure/recovery loop. Existing phase, operation,
session-generation, mutation and credential-owner fences still guard publication.

The legacy `selected` field in the Android cached-user record is an extension to
the original shared cached user. A malformed extension now supplies no SDK
selected id and cannot discard otherwise valid cached account fields. The
independent remembered team preference remains authoritative for presentation.
Core cache-field types, schema/environment checks and absence of cached connection
authority remain unchanged. Eight executed original cache cases cover this
extension boundary, including continued rejection of malformed core fields.

## Verification

`scripts/generate-native-sdk-json-reference.py` produces 368 raw-byte cases and
eight shared-cache cases. The corpus includes encodings/BOMs, control and surrogate
handling, duplicate flags/rows, malformed syntax, depth, number boundaries, a
deterministic 128-number corpus and 64 near-one decimal cases. Independent
regeneration is byte-identical. The same fixture is packaged only into the test
APK's auth assets; its asset hash is checked against the JVM fixture.

Full JVM: 3,454 total in 458 suites, 3,450 passed, four existing SSH integration
skips, zero failures/errors. Eight new methods consume the original byte/cache
corpus and exercise real HTTP user/list/error consumers, eligibility duplicates,
authority retirement and interactive/restored error projection. The first decoder
attempts exposed decimal conversion, negative overflow and leading-zero precision
differences; their logs remain separate. A broader run exposed an existing test
that required membership to be the third concurrent request. That fixture now
routes by endpoint and holds the membership response behind an explicit barrier,
retaining stale-principal/cache/proof assertions without assuming request order.
Both debug APKs build.

Owned API 36: 201/201 in 357.094 seconds under
`captures/recovery-20261023/sdk-json-runtime-v1`, comprising six new cases and 195
prior regressions. Six new cases execute the full wire corpus on ART, actual encoded
account HTTP and cold encrypted reconstruction, trailing-material list retirement,
first-duplicate eligibility through restore/recovery, malformed cached selection
and primary-user nil clearing with pairing/key retention. Reply bytes are isolated
loopback fixtures; these are not live provider or physical-device checks.
The production 60-second phase returned at 60,015ms with fixture ciphertext/pairing
intact and no PATCH. Crash log empty, no preflight System UI ANR, restored
settings/window and owned emulator exit0. The final receipt binds 72 local inputs,
41 whole original source files, both installed APKs and the exact test-asset hash.

Evidence under `captures/recovery-20261023`: `sdk-json-build-v6.log`,
`sdk-json-jvm-v6`, raw-reference regeneration, runtime evidence and
`sdk-json-checkpoint.json`.

## Remaining acceptance

Full parity remains active. Actual iOS Foundation equivalence, full SDK
transport/error/retry/nonce and backend/token/mutation bounds, remaining identity/
metadata/worker consumers, downstream image/demo UI, full auth-project reset,
consent-aware reporting/rate integration, actual locked storage/OS sleep/network
loss/process death, physical Pixel/Mac/provider/helper/Firebase, matched full UI/
accessibility and final signed-release acceptance remain open. This scoped adapter
is not a general replacement for every parser or Codable path in the app. No
physical install, real account/pairing/signing material, global source pin or
frozen signed 722 changed.
