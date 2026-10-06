# Android release notices

`android-notices.json` is the Android-owned What's New feed, served directly from
this public repository's `main` branch:

https://raw.githubusercontent.com/DocMorphic/cmux-app/main/distribution/android-notices.json

The initial feed only enables the two existing compiled Android catalog entries.
It contains no new release claims, remote web announcements or production-channel
overrides. Do not copy iOS version numbers or catalog identities into this feed.

## Editing

- `visibleEntryIds` is the authoritative list of eligible compiled pages. IDs are
  permanent once shipped. When adding a catalog page, review this list in the same
  change. An empty list explicitly retracts all compiled pages; use deliberately.
- `entryChannels` optionally replaces an entry's compiled channel list. Absent
  targeting uses the existing development/beta/internal policy. Production must
  be explicitly opted in after release acceptance.
- `announcements` holds remote notices with permanent `id`, inclusive `minVersion`
  and `maxVersion`, optional channel targeting and a body. A body can reference a
  visible `nativeEntryId`, a permitted cmux web URL, or inline `features` containing
  `title`, `detail`, and optional `symbol`. Native resolution and version/channel
  filtering happen on device. Identity must remain the same across translations.
- Keep payloads below 1 MiB UTF-8. Review changes and commit to `main`; normal
  GitHub raw-content caching can delay delivery. The app checks once per notice
  ViewModel lifetime (normally a cold app start), not on every screen or keystroke.
- Existing installed APKs predating the feed integration do not fetch this file.
  Ship the client at a chosen signed milestone before relying on remote notices.

Run `:app:testDebugUnitTest --tests '*NativeWhatsNew*Test'` with the project's JDK
and SDK configured. The shipped-feed fixture intentionally checks the initial
no-announcement/no-channel-override state; update that reviewed expectation when
publishing the first real announcement. Feed-only changes do not count as APK
changes in the preview batching policy. Scheduled APK builds remain opt-in.

## Client behavior

The dedicated HTTP client sends no credentials, cookies, device identifier or
account information. It accepts only a 200 response from the configured URL,
rejects redirects, limits responses to 1 MiB and has a ten-second total deadline.
Cancellation closes the pending request. Both network work and cache persistence
run off the main thread. Invalid or unavailable feeds retain cached/compiled
native behavior rather than becoming an empty retraction.

Cache identity includes the complete endpoint. Serving JSON from GitHub does not
allow announcement HTML from GitHub or add GitHub to the authenticated web-session
policy. Web announcements keep the existing cmux-host allowlist and isolated
renderer. This feed does not install APKs or promote GitHub/Play releases.
