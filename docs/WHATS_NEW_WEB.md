# What's New web notices — transport and lifetime checkpoint

2026-10-04. Source contract: upstream `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`,
`MobileWhatsNewWebPageLoad.swift`, `WebSession/MobileWebAppSessionBroker.swift`
and `WebSession/MobileWebPagePolicy.swift`. The global parity pin is unchanged.

## Implemented

`NativeWebSessionBroker` exchanges the native session through an independent,
cookie-less/cache-less OkHttp client. It has no account-client interceptors,
authenticators, redirect following or automatic connection retries. The destination
must pass the same notice URL policy before tokens are acquired. The POST goes
to `/handler/app-session-handoff` on that destination's own scheme/host/port;
`access_token`, `refresh_token` and relative `after` are form encoded. No fragment,
Authorization header or previous exchange cookie is included.

Only a same-origin **204** with `X-Cmux-App-Session-Handoff: ready` can return
cookies. The broker filters to unexpired Stack/Hexclave cookies for the exact
host and the configured project's refresh namespace, retaining the upstream
prefix/chunk conventions. It requires both access and project refresh cookie
families. Unknown/foreign cookies, missing pairs, redirects and errors return
an empty anonymous session. Cancellation propagates and cancels the network call;
a cancellation/response race closes the response body.

`NativeAccount.webSessionSnapshot` captures the same login before/after access
refresh and verifies the stored access token before returning the snapshot.
`isWebSessionCurrent` compares login/access/refresh on completion. The snapshot
has a redacted `toString` and no generated data-class destructuring/copy methods.
Native tokens belong only in this exchange; a renderer must never put them in
JavaScript, page URLs, intent extras or diagnostic logs.

`NativeWhatsNewWebLoad` is a renderer-neutral owner. Its deadline covers **all**
steps supplied by the renderer: session exchange, cookie seeding and initial page
load. It settles exactly once, releases concurrent and late outcome waiters, and
cancels a stalled startup. Main-frame rejection before load fails immediately;
rejected links/later failures preserve an already loaded page. Closing or retiring
the parent disposes the renderer once. Supply a main/UI coroutine scope when
callbacks operate Android views. Launch uses 10 seconds; archive uses 20 seconds.
Creating a new load is the Retry contract, including a fresh exchange.

These components are not yet connected to a WebView or to the presentation owner.
No real account exchange was made, and no Android notice feed is configured.
The existing explicit web placeholder remains until the renderer is finished.

## Verified scope

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/dharmaydave/Library/Android/sdk \
  ./gradlew :app:testDebugUnitTest --tests '*NativeWebSessionBrokerTest' \
  --tests '*NativeWhatsNewWebLoadTest' --max-workers=1 --no-daemon
```

**16 JVM tests passed** (10 broker, 6 lifetime; no failures/skips). Gradle completed
in 16 seconds. Tests use loopback MockWebServer and synthetic fixture tokens only.
They check exact headers/form fields, no redirect delivery, cookie scope and
response origin, project pairs/chunks, absent/stale accounts, no cookie reuse,
redaction, cancellation and timeout without retry. Deterministic coroutine tests
cover all/late waiters, rejected initial redirects, post-load preservation,
parent disposal, the full-load timeout, concurrent preload windows and fresh Retry.
Evidence: `captures/runtime/whats-new-web-core/` (ignored).

The Android encrypted-account adapter, renderer/profile cleanup, cookie seeding,
visual appearance, actual server exchange and physical Pixel remain unverified.
No emulator, debug APK assembly or signed milestone was started for this checkpoint.
Build 494 remains the verified signed milestone and predates this work.

## Android storage research and required next action

The [Android WebView reference](https://developer.android.com/reference/android/webkit/WebView)
states that direct private browsing is no longer supported. Its deprecated
private-browsing constructor is not a substitute for iOS `.nonPersistent()`.
The installed **AndroidX WebKit 1.17.1** AAR was inspected with `javap`:
`ProfileStore` exposes named profile creation/access/listing/deletion, with no
in-memory profile factory. This establishes the available public API surface,
not proof that a particular renderer/storage implementation is private.

The [ProfileStore reference](https://developer.android.com/reference/androidx/webkit/ProfileStore)
says profile deletion can reject live WebViews or explicitly loaded profiles,
and some disk deletion is asynchronous. A randomly named profile therefore must
not be described as a nonpersistent store merely because it has a unique name.
Clearing default-profile cookies/storage would also disturb unrelated app content.

Next run a bounded Android profile isolation/cleanup experiment on the existing
AVD, using only synthetic cookies and uniquely owned test profiles. Determine
whether `WebViewCompat.setProfile` plus per-view cookie access allows deletion
after destruction, including process-death cleanup. Verify that unrelated/default
profiles remain untouched. Evaluate any platform difference against the original
nonpersistent requirement; do not silently replace it with a persistent default
WebView or claim in-memory privacy. No alternate rendering engine was assessed in
this checkpoint, so an Android-wide impossibility claim would be unsupported.

Then implement the actual renderer and cookie seeding, theme-before-load/live
updates, every-navigation allowlist, retained archive Back, retry, and concurrent
launch preload integration with late visibility/content checks. Keep all failed
or timed-out web pages unacknowledged. Complete measured native-sheet fitting,
debug-only replay/suppression, and the remaining physical/signed acceptance in
[WHATS_NEW.md](WHATS_NEW.md).
