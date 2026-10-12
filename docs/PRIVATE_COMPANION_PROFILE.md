# cmux-app Mac notification companion profile

## Implemented — 2026-10-12

The selected private Firebase/custom Mac workflow uses tag `android-push`, bundle
`com.cmuxterm.app.debug.android.push`, authenticated namespace
`mac:com.cmuxterm.app.debug.android.push` and companion source baseline `0.65.0`.

`NativeMacBuildAudience.application` explicitly supports this companion alongside
stable, nightly and RC Macs. Both Android package variants use it for compatibility,
discovery/presence, saved/cached presentation, FCM and reply workers. The separate
`consumer` policy retains the public iOS rules. Android debuggability grants no
development-build access.

Discovery can show the companion tag; live admission requires its exact namespace.
Missing/legacy namespaces, other development bundles, prefix lookalikes and public
tags paired with the private namespace are rejected. Existing account, team,
expected Mac/device/build, grant and ticket checks remain. Cached presentation
cannot mint connection authority. Push/reply pins require the matching tag/bundle.

The companion version gate requires the greater of its 0.65.0 source baseline and
the downloaded stable protocol minimum. Missing/malformed/older versions fail even
with an empty remote policy. Refresh retires outdated wires; update guidance links
to the companion build instructions. Public version/legacy behavior is unchanged.

The Iroh descriptor keeps the actual Android package/display name. Its existing
`ios` discriminator remains a server wire profile; no iOS bundle identity is adopted
and no enrollment identity is rewritten.

## Scoped source reference

Upstream `f4b1509054949eaad5d695569ad443c4c18ed68d`:

- `MobileMacBuildCompatibilityPolicy.swift`: an explicit development expected tag
  and authenticated namespace boundary, separate from the public audience.
- `MobileIOSAppNamespace.swift`: the complete installed bundle defines isolation.
- `MobileHostIdentity.swift`: launch metadata supplies the Mac instance tag.
- `CmxIrohMacBundleNamespace.swift` and `MobileHostService.swift`: authenticated
  status carries the namespace derived from the complete Mac bundle.

This project-specific profile limits the additional identity to the chosen
companion. It does not implement mutable sibling-tag grants or broadly admit
development/staging builds. The global source pin remains unchanged.

## Verification and limits

All **84 JVM cases pass**: five companion-policy, 11 consumer, nine compatibility
gate, 14 version-policy, five cached-computer, four signed-descriptor and 36 runtime
cases. The new runtime case checks mixed discovery, rejection of another bundle
before observation, then correct companion admission. Debug/test APKs build in 42s.

All **six Android worker cases pass in 8.95s** on the existing Android 17 / 16 KB
AVD: companion ciphertext posts/dismisses, companion replies submit encrypted
bodies after fresh membership, another bundle cannot post/relay using the tag, and
both public-consumer rejection regressions pass. The cases use encrypted device
storage, real cryptography and local membership/relay servers; no Firebase or
official relay is contacted. Seven affected debug classes pass ART loading.
The owned emulator is stopped/reaped; no new AVD or physical-device change.

Exact source/APK, XML, instrumentation and lifecycle evidence is ignored under
`captures/runtime/private-companion-audience-*`. Signed 652 predates this source.
Native Mac account/key-store/listener, broker/QUIC pairing, the trusted private
endpoint, actual Firebase delivery and Mac reply execution still need Pixel/Mac
acceptance. The existing sign-in question remains pending. Full parity is unverified.
