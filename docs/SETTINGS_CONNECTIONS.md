# Connected computers in Settings — 2026-10-12

## Scoped iOS reference

At upstream `f4b1509054949eaad5d695569ad443c4c18ed68d`,
`Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/MobileSettingsView.swift`
lines 164–217 and 846–873 show one row per live app instance (device + build tag),
its actual transport and the same Computer Details route used by Computers.
All Computers remains reachable with zero live connections. The section retains
its source footer and a host/Active Transport fallback without live snapshots.
The global parity pin is unchanged.

## Android implementation

Settings now projects only current allowed pairings with a verified CONNECTED
snapshot, including distinct sibling builds on the same Mac. Each whole row opens
the existing computer controls; raw legacy pairings reuse their existing Details
screen with a Settings return action. All Computers remains available offline.
Unknown transports remain unlabeled. Foreground connections report the actual
opened wire and do not borrow a background connection's method.

Read-only transport metadata comes from admitted Iroh/Tailscale implementations
and follows existing pool/attach-ticket leases. A raw TCP socket does not infer a
Tailscale label from its address. Direct Iroh preferences still use Iroh and do not
invent WebSocket support. Verified feed snapshots publish their own method and
clear it on reconnect/disconnect/pause. About diagnostics use the same actual wire
metadata. Opening controls uses the original connection owners and does not dial
or change a preference. Click dispatch rechecks current login/team, saved pairing
and allowed route; cached component actions also check membership and lifetime.

## Verification

All **95 focused JVM tests** passed with zero failures/errors/skips: connection
projection 9, Settings rows 4, pool/lease connections 14, feed coordinator 58,
Tailscale candidates 8 and Iroh transport 2. These include real framed loopback
feed peers, per-host method separation and disconnect/pause retirement. This is
fixture evidence, not a real Mac/Pixel network result.

The first debug/test APK build succeeded in 2m59s. Three Android cases passed
unassisted in **27.083s** on the existing API37 / 16 KiB AVD: sibling rows with
actual font scale 2, stale actions across owner/membership/lifetime changes, and
the real NativeScreen Settings → legacy Details → Settings → terminal-input flow.
The latter retained pairing and the live client without extra dialing. No new
crash/ANR events occurred; the crash buffer was empty, timeout restored and the
sole AVD stopped/reaped.

Screenshot inspection found that at 260 dp/font scale 2 the trailing method could
squeeze names into a narrow column even though the no-overflow assertion passed.
Rows now place their method below the full-width name at narrow widths or large
text scales. A stronger bounds assertion checks that placement. The final build succeeded in 42s, and the same three Android cases passed
unassisted in **26.33s**, including the stronger placement assertion and integrated
terminal-input flow. The final screenshot was inspected: names use the available
width and the method sits below them. No new crash/ANR records occurred; timeout
was restored and the sole emulator stopped/reaped. The initial result is preserved.

Initial debug APK SHA-256:
`59fea6f2b122160ac233bc9438d06130dad99dbd7852c9b4c5e7523b0d7db115`.
Initial test APK SHA-256:
`150ef1b8a466231a3254f3535d72b79e5dd5a98a7481739ee495b14decf57f19`.
Evidence: ignored `captures/runtime/settings-connections*`, including initial
build, instrumentation, APK hashes, source excerpt and inspected screenshots.
An initial install attempted before boot completion returned failure; installation
succeeded after the bounded boot wait. That preflight failure is not a test result.
Signed 650 remains the published APK; physical Settings navigation, matched iOS
appearance, TalkBack and full parity acceptance remain open.

Final debug APK SHA-256:
`7b45437d3da9b873b9ccf7917de4ae9d3743a44416cda3468bfe9efee0ce7b12`.
Final test APK SHA-256:
`6947b33ae791967bb593b99ede5b2b9d888b3cc11e079d156e686ba1a0798da7`.
The final source/APK hashes and separate initial/final results are preserved in
`captures/runtime/settings-connections-verification.json`. The JVM suite preceded
the responsive composable adjustment; its tested projection/transport/feed code
was unchanged by that adjustment.
