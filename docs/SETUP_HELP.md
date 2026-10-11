# Settings setup reference — 2026-10-12

Scoped source: `f4b1509054949eaad5d695569ad443c4c18ed68d`,
`Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/SetupHelpView.swift`,
`SetupHelpGateContent.swift` and the entry in `MobileSettingsView.swift`.
The global parity pin is unchanged.

Settings now opens **Set Up Computer** before View Introduction Again. Like the
iOS Settings entry, this is a plain reference in setup order, without a current
blocker marker: Sign in; Run cmux on your computer; Wake the computer; Match the
account; then How it connects and the same-account footer. It has a fixed Done
header, scrolling content, heading semantics, decorative icons and wrapping text.
Opening the screen does not create an account, change pairing or start discovery.

Mac pairing wording names the existing Enable iOS pairing toggle and explains
that it also enables Android. Minimum-version guidance reads the same live policy
as admission and disappears when that requirement is absent. Network guidance
follows the Iroh/private-network reference. The phone download uses
[Tailscale's official Android link](https://tailscale.com/download/android),
verified to target `com.tailscale.ipn` on Google Play; the computer link targets
the existing official download page. A failed browser launch shows a retryable
inline error, and a later successful launch clears it.

## Evidence

Only the existing API37/16 KiB AVD was used. Two distinct Android cases have
passing results, across the recorded runs:

- The production NativeScreen Settings route opens/closes the reference,
  preserves its saved pairing and the same open transport, then opens a terminal
  and sends actual `terminal.paste` input. This passed in the **18.792 s** batch.
- The final component case passed in **11.250 s**: open-dialog restoration;
  all sections in source order; live minimum-policy changes; fixed header while
  scrolling; both exact download targets; failed launch and recovery; Done
  dismissal; and large-font text. It directly verifies the renderer's font scale
  is **2**, rather than assuming a parent test override reached the Dialog.

Original failures remain in ignored captures: the first connected test omitted
the notification data its fixture expects (**34.200 s** batch); the stronger text
layout assertion then proved Dialog content rendered at scale 1 instead of 2
(**18.792 s** batch). Passing the host density into the Dialog fixed rendering.
The next ordering assertion incorrectly used clipped off-screen bounds at the
larger size (**5.995 s**); unclipped bounds fixed the test without changing UI.
The inspected final screenshot shows actual enlarged text and a visible Done
button; it is not a matched-iOS screenshot or a physical Pixel check.

Builds succeeded in 1m35s, 30s, 27s and 19s. Original and repaired runtime/build
logs are retained as `captures/runtime/settings-setup-help-*`. Final crash/ANR
buffers are empty. The screen timeout was restored and the sole emulator and
Gradle processes were stopped/reaped. No physical Pixel data changed.

Final debug APK SHA-256:
`c411a2478d0c4e491593d1112f891397c0b82ffd91af94fe04cac5b99ab94a9e`.
Final test APK SHA-256:
`e9de65cd2dd5a80bcf6f7aaa29df3e1a7f6924f36c97c99fccdb584c06f9bbf3`.
The connected test was not repeated after the text-density change; the final
renderer case used these APKs. Signed 650 is unchanged.

The existing connection-action guide remains separately available from sign-in
and Computers. Highlighted setup gates on disconnected/onboarding screens,
matched-iOS grouped styling, TalkBack, localization and physical workflow
acceptance remain open. This checkpoint does not complete overall Settings or
connection parity.
