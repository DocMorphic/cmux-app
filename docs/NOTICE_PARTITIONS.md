# Partitioned notice storage — 2026-10-04

This follows [extension recovery](NOTICE_RECOVERY.md). The production extension
clears cookie scopes without specifying a partition key; the native renderer
also requests public per-context web-storage clearing. Earlier tests proved
ordinary same-origin state but modeled partition matching only in Node.

## Real browser fixture

`NativeNoticePartitionTest` uses the delivered Gecko runtime and production
renderer retirement/recovery. Two loopback top-level sites (`127.0.0.1` and
`127.0.0.2`) embed the same `localhost` origin. Its server sets a Secure, HttpOnly,
SameSite=None, Partitioned fixture cookie. Page JavaScript writes localStorage,
IndexedDB and Cache Storage. The real `/report` request independently records the
cookie header while JavaScript must see no cookie.

Mozilla documents that [partition keys depend on the top-level site](https://developer.mozilla.org/en-US/docs/Web/Privacy/Guides/Third-party_cookies/Partitioned_cookies)
and [Secure cookies have a localhost exception](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie).
The test does not assume that the pinned engine partitions everything correctly:
it first writes different values under both top-level sites, verifies the second
starts empty, then rereads each site to prove its distinct values survived.
See also Mozilla's [state partitioning description](https://developer.mozilla.org/en-US/docs/Web/Privacy/Guides/State_Partitioning).

Auxiliary **instrumentation-only** Gecko sessions populate and read the exact
context acquired by the production renderer. They are needed to create cross-site
fixture state beyond the notice URL allowlist. They do not modify the production
navigation policy, DNS, certificate trust or storage preferences. This checks the
storage boundary; it does not claim the app allows arbitrary embedded hosts.
A separate unowned private context supplies the preservation control.

One case closes the production page normally; the other shuts down its real
extension and prepares a fresh production page, invoking receipt-based recovery.
Both reopen the **old context name**, check both top-site partitions for absent
cookie/localStorage/IndexedDB/cache values, then verify the control context still
has its own values. Public storage clearing is asynchronous; observations are
retained while checking its bounded completion. This proves logical stored-data
cleanup for the fixture, not synchronous physical disk erasure.

The HTML lives only in Android test assets. Neither it nor the test sessions are
part of the debug/release app's user flow. Reports are saved after each observation
and phase so a process crash cannot erase all intermediate evidence.

## Disconnect callback crash

The initial run crashed during extension disconnection with
`NativeException NullHandle` from `EventDispatcher.shutdownNative`, reached through
`WebExtension.Port.disconnectFromExtension`. The exact pinned AAR's bytecode shows
that Gecko invokes `PortDelegate.onDisconnect` before `Port.disconnected()` shuts
down its native dispatcher. Session teardown in that callback could reenter the
native lifecycle before the port finished shutting down.

The handler now revokes the channel and every affected page's UI/acknowledgement
eligibility immediately, then posts session teardown to the next UI event. The
posted action checks its connection generation. Old pages cannot attach, navigate,
seed a session or be acknowledged in the interval. The earlier crash and bytecode
inspection are retained under `captures/runtime/notice-partitions/` (ignored).

## Verification

Final-source debug and Android test APK builds passed (`build-3.log`). All **52
focused JVM tests** passed with no failures or skips. All **five Android cases**
passed in **90.184 seconds** (`runtime-2.txt`): the two new partition cases plus
three existing renderer cases covering account replacement, scoped retirement
and extension-loss recovery. Both partition reports contain ten observations and
finish with `passed: true`. Both top-site partitions in the retired context were
empty, while the control context retained its distinct values under each site.

The unsigned release rebuilt successfully after the callback fix
(`build-release-final.log`). Debug/release engine pin, license and private-extension
packaging checks pass. The Android 17 / 16 KB ART gate also passes for `NativeScreenKt` (1,320 methods;
`art-release.txt`). The existing AVD was stopped after verification.
The partition HTML is present only in the test APK and
absent from both app APKs. No Node tests were rerun because extension JavaScript
was unchanged; the prior checkpoint's eleven passes remain historical evidence.

The initial crashing run remains in `runtime.txt` and `crash-logcat.txt`; it is not
counted as a passing run. The exact AAR's inspected bytecode is retained alongside
it. No exception was suppressed to make the rerun pass.

This checkpoint still does not prove whole-Gecko-runtime crash recovery, real
HTTPS/native-account exchange, physical Pixel acceptance, Android feed availability
or push delivery. Build 494 remains the last signed milestone.
