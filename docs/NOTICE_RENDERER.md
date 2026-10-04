# Private notice renderer

The Settings announcement detail now uses the production `NativeNoticeRenderer`
instead of the development placeholder. `NativeNoticeArchiveOwner` belongs to
the notice ViewModel: activity recreation and theme changes retain the same page;
Retry creates a new private page and exchange. Dismissing the archive or replacing
the login retires all owned pages. A remote content replacement with the same ID
also receives a fresh document. The Android feed is still unconfigured, so normal
builds currently contain only native catalog entries.

## Engine and ownership

The main app pins Mozilla GeckoView `157.0.20260924084938` from Mozilla's restricted
Maven group. Its POM identifies source revision
`8eb25af4acf031ab1e06abf1a912275083c820ed` in `releases/mozilla-release`.
`assets/licenses/GeckoView.txt` reproduces the engine's bundled license page as
readable text; its original HTML remains inside the packaged `assets/omni.ja`.
The source link and third-party notices appear in the app's license dialog.

There is one lazy engine per app process and a random named **private** context
per page. No cookie, account token or activity goes into saved state. The runtime
uses no external configuration file, remote debugging or console output. Theme
is set before navigation and updated while the retained page is visible. The
engine's clipboard provider is not exported; explicit URI grants remain possible.

The bundled extension exposes only acquire, seed and clear over a checked native
background port. There are no content scripts, page messages, certificate trust
operations, fixture DNS changes, arbitrary scripts or remote code. The renderer
first opens its unique blank marker. Acquisition records that exact tab's actual
private origin attributes. Cookie batches are validated before mutation and can
only be seeded once, while the tab is still at its marker. Native tokens stay in
the existing out-of-band broker; only its accepted web cookies enter the engine.
Errors returned from the extension contain no cookie values or URLs.

Native command ordering is preserved even when a caller cancels its waiter.
Retirement closes the tab first, clears its cookies using explicit private and
context attributes, then requests per-context web-storage clearing. Cookie
clearing deliberately omits host/partition keys so it covers the whole owned
context. It never clears another context or the default browser profile. Cleanup
runs in a scope that outlives the presentation. A noncooperative late exchange
still has to pass the owner check before seeding or navigation.

Enabling private extension access can restart the extension during first setup.
Queued setup connections can replace each other before the engine is used. Once
pages have used the engine, losing the extension connection invalidates all
leases and closes the pages. The current fail-closed recovery requires an app
process restart; transparent recovery from extension failure remains open.

Navigation uses the existing cmux/configured-host policy for every top-level and
subframe navigation. Popups are denied. Rejected initial navigation fails the
load, and rejected later links retain an already loaded page. The exact owned blank marker is exempted during setup; plain `about:blank`
retains the existing internal-navigation exemption. Archive timeout covers engine setup,
exchange, cookie seeding and page load; it uses the existing 20-second owner.

## Verification and remaining integration

The production extension has five Node tests, included with the CI helper checks:

```sh
node --test scripts/tests/notice-session.test.mjs
```

They cover private-tab ownership, one-shot seeding, atomic rejection of mixed
cookie batches, context replacement, insecure Secure-cookie rejection, and a
modeled partition-aware clear that preserves other/private-mode contexts. The expiry test rejects seconds-valued timestamps: pinned Gecko 157 accepts
milliseconds in `nsICookieManager.add`. The
modeled partition test does not prove browser partitioned-storage behavior.

`NativeNoticeRendererTest` uses the actual renderer and bundled extension with
synthetic loopback pages. Final runtime results are recorded below.
Evidence lives in `captures/runtime/notice-production/` (ignored). Initial fixture
and setup failures remain separate from final results.

Launch preloading is still excluded from `NativeWhatsNewPresentation`; it must be
connected to the retained load owner, with concurrent ten-second deadlines,
content/owner revalidation and no acknowledgement for failed pages. Full archive
Compose acceptance, real HTTPS session exchange, browser partitioned-storage
cleanup, extension-loss recovery, physical Pixel acceptance and a new signed
milestone remain required. This checkpoint is not full What's New parity.

### Integration failures retained as evidence

The first run exposed a test-only reverse-DNS call on the main thread and queued
extension connections from the deliberate private-permission setup restart. The
fixture now uses literal loopback addresses; setup connections can replace each
other only before any page uses the engine. A later seed failure was corrected
by using Gecko's own URI parser rather than assuming a browser `URL` global.

The next page loaded anonymously despite an accepted seed operation. The isolated
case reproduced this without preceding cancellation, ruling out cross-test cleanup.
A cookie readback guard exposed that the accepted cookie was absent. Inspection of
**the actual pinned engine's** `ext-cookies.js` established its expiry parameter is
in **milliseconds**. Passing seconds made the cookie expire in the past; this is
now corrected on both sides of the protocol. Native cookie-write validation and
readback remain mandatory. Error categories contain no cookie values.

An isolated test invocation immediately after emulator boot also ended in a bind
application startup ANR before instrumentation began. The unchanged retry ran and
reproduced the cookie assertion. This is retained in `exit-info.txt`; it is not a
successful startup/performance acceptance and remains relevant to device testing.

### Final verified checkpoint — 2026-10-04

- Final debug/test/unsigned-release build and **45 focused JVM tests passed** in
  1 min 44 s. An earlier full dependency-integration run passed 1,532 app JVM tests
  with four external-fixture skips, before the final cookie expiry correction.
- **Nine Node tests passed**: five extension boundary cases and four helper cases.
- Production renderer: **2 Android tests passed in 26.436 s** on the existing
  API 37 / 16 KB arm64 AVD. Requests carry each context's correct synthetic
  HttpOnly cookie; JavaScript sees neither cookie. The same page retains storage
  across activity recreation and updates its resolved theme. Reopening the exact
  retired context finds no cookie/localStorage, while the second context retains
  both. A deliberately noncooperative account exchange cannot seed/navigate after
  its owner is retired. These are local HTTP fixtures, not real account/HTTPS tests.
- Existing notice UI and terminal/browser process restoration: **6 cases passed
  in 52.128 s**. The renderer fixture screenshot was visually inspected; it is not
  a full archive Compose web acceptance screenshot.
- Unsigned release ART gate passed (`NativeScreenKt`, 1,316 methods). All **19**
  native libraries and 16 KB ZIP alignment pass in debug/release. Engine pin,
  original/readable notices and extension assets match; test CA/key are absent.
  All six debug fixture activities are excluded from release. CI's stable verifier
  now expects the larger library/fixture inventory while retaining the same signer.
- Main debug cold launch reached sign-in in 2.318 s with `pageSizeCompat=0`. The
  earlier post-boot bind ANR is retained; this successful launch does not erase it.
- The existing AVD was stopped and reaped. No new emulator or signed milestone was
  created. Pixel was absent; its app/data and signed build 494 were not modified.

Package cost is material: debug is **241,300,217 bytes** and unsigned release is
**227,873,398 bytes**, compared with 46,448,982 / 34,652,872 bytes at the prior
main-toolchain checkpoint. These are APK sizes, not measured installed storage.
`third_party/geckoview/manifest.json` records the source/AAR/license pin;
`scripts/verify-notice-engine.py` checks packaged metadata, licenses, extension,
native inventory and exclusion of fixture TLS material. Future engine upgrades
must repeat the actual runtime contract checks, including expiry units.
