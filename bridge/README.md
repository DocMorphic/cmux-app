# Mac helper prototype

This is an optional route for a personal Android companion. It uses the installed cmux CLI to list workspaces, read terminal text, and send input. It must run **inside a cmux terminal**, because cmux denies socket access to processes started elsewhere.

## Local check

From a terminal pane inside cmux:

```bash
cd /Users/dharmaydave/me/cmux-app
node bridge/server.mjs
```

The default listener is `127.0.0.1:58466`, so only the Mac itself can reach it. The bridge refuses to start if the cmux CLI cannot return a workspace tree. Test it locally with `curl http://127.0.0.1:58466/v1/health`.

## Phone connection

For a phone on the same Tailscale network, start it from a cmux terminal with:

```bash
node bridge/server.mjs --bind-tailscale
```

The bridge verifies that Tailscale supplied the exact private address, binds only that address, and prints a `cmux-app://pair` URL. Paste that URL into **Connect through Mac helper** in the Android app. The URL contains a 256-bit bearer token stored outside the repository at `~/.config/cmux-app/bridge-token` with mode `0600`; keep it private. The app sends the token in an Authorization header, not in request URLs.

The helper uses HTTP because Tailscale encrypts the network path. The Android client accepts helper URLs only for Tailscale IPv4 addresses and never follows redirects. Do not expose the helper through a public port or reverse proxy.

This prototype polls plain terminal text once per second. It does not yet match the official iOS app's grid rendering, full-screen TUI support, background notifications, or automatic reconnect. Its network binding is **off by default**.
