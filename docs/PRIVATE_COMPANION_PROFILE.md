# cmux-app Mac notification companion profile

## Active registration/admission source review — 2026-10-13

Android's account connection uses `NativeIrohBackend` / `IrohV2ControlSession`
against the production v2 worker. The scoped website `/api/devices/iroh` broker
has a different build-lane policy and does not determine this v2 connection's
acceptance. No legacy policy workaround or server mutation was made.

Read-only public `/v2/health` returned HTTP 200 over normally verified HTTPS,
reported production revision `b9e28cf91e80c613a119580d9353d4c38e698ce9`, the
`cmux.mac-peer-inbound.v1` rule and storage maximum/write versions 7/6. Exact
Git comparisons find the v2 `contracts/common.ts`, `storage/team-store.ts`,
`broker.ts` and `auth.ts` byte-identical at that reported revision and our custom
Mac pin `f4b15090`. Source hashes and the public response are ignored under
`captures/runtime/custom-companion-registration-audit-20261013/`.

Those schemas accept the full namespace/tag as scoped identity fields and retain
the `mac`/`ios` platform enum. The existing Android compatibility profile sends
the mobile wire discriminator while retaining its own package namespace and
Android display name. The active directory's phone inbound branch requires Mac
pairing enabled, current authority and same-account or an explicit incoming
permission; its app/build equality restriction applies to Mac peers. The pinned
Mac `V2InboundAdmissionAuthority` preserves those phone opt-in, team/environment/
project, endpoint, revocation and lease checks. Source review therefore finds no
additional namespace allowlist barrier for this selected companion on the active
v2 path. This is not proof of production registration, directory publication,
QUIC admission or native key exchange. Verify all of those on the actual Pixel
after custom Mac sign-in and explicit listener enablement.

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
