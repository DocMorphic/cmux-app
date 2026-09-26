# Android implementation plan

## Release goal

Install an APK on an Android phone, pair it with a Mac running cmux, browse workspaces, open a live terminal, send input, and see agent notifications over a private network. The app should reconnect after brief network loss without duplicating input.

## Milestones

### 0. Project foundation — in progress

- [x] Create Android Studio project and Git repository.
- [x] Record official iOS behavior and protocol boundaries.
- [x] Add offline workspace/terminal UI preview.
- [x] Add bounded v2/v3 pairing QR recognition with tests.
- [x] Build preview APK in CI.
- [ ] Build and install preview APK on an Android device.

### 1. Protocol contract

- [ ] Pin a known cmux Mac version/commit for compatibility tests.
- [ ] Port ticket, route, frame, request, response, and event models from `CMUXMobileCore` with attribution.
- [ ] Add fixtures produced by the Mac host; check version mismatch and malformed frames.
- [ ] Decide whether to use the official mobile endpoint or a small open Mac bridge if Android account registration cannot use the official flow.

**Done when:** Android decodes real Mac pairing and protocol fixtures and rejects invalid or stale authorization.

### 2. Pair and connect

- [ ] Implement QR camera scan and manual paste.
- [ ] Integrate the required same-account authentication and host pairing flow.
- [ ] Store device credentials in Android Keystore-backed storage; support unpair and revocation.
- [ ] Connect over a private Tailscale route with timeouts, host identity checks, and reconnection.
- [ ] Add the multiplexed framed RPC session and workspace/event subscription.

**Done when:** a physical Android phone lists real workspaces from a paired Mac, including after app restart.

### 3. Terminal MVP

- [ ] Stream and render terminal grid or VT data with colors, cursor, Unicode, scrollback, and resize.
- [ ] Send text and control keys, including paste, arrows, Tab, Escape, Ctrl, and Alt.
- [ ] Support workspace and terminal switching plus disconnected states.
- [ ] Test full-screen apps (vim/htop), agent prompts, long output, and network interruptions.

**Done when:** the phone can drive Claude Code or Codex in cmux and a full-screen terminal program without corrupted display or lost input.

### 4. iOS parity and delivery

- [ ] Agent notification feed and Android background notification strategy.
- [ ] Browser/pane surfaces, workspace actions, settings, and accessibility.
- [ ] Iroh route support if required for non-Tailscale use.
- [ ] Signed release APK, reproducible CI build, installation guide, license audit.

**Done when:** a signed APK is installable and the core workflow passes on a physical device against a supported Mac build.

## Immediate inputs for live testing

The Mac is running cmux `0.64.25 (106)`, signed in, and connected to Tailscale. An Android peer is online in the same tailnet. The `feature/local-mac-bridge` branch now has an opt-in Mac helper and Android screen; CI builds it, but its CLI integration and physical-phone connection still need live testing. Official Android auth/client registration details or maintainer guidance may be needed for the production mobile protocol.
