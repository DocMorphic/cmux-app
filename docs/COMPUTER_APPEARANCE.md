# Computer appearance

Reference: cmux `4c5272e9153eca2033c9f40ac749f0c3a5bcb291`:

- `MacComputerDetailView.swift`: name submit, eight palettes, custom RGB color,
  ten symbols, ten emoji choices, custom emoji and independent Auto choices.
- `MachineAvatarColors.swift`, `MacAvatarIcon.swift`, `MachineAvatarPalette.swift`:
  shared machine color, custom palette/RGB precedence and stable scalar hash.
- `MobilePairedMacStore.swift` and the active `CMUXMobileRootScene.swift`
  composition: local storage scoped by build and selected team. The separately
  implemented backup decorator is not instantiated by this pinned app.

## App-instance color stability — 2026-10-03

Included in signed development build **463**. Delivery and signed upgrade
verification are recorded in [PIXEL_INSTALL.md](PIXEL_INSTALL.md).

The targeted review at `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc` changes the
automatic color contract. [`MobileShellComposite+MacSwitchState.swift`](https://github.com/manaflow-ai/cmux/blob/0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc/Packages/iOS/CmuxMobileShell/Sources/CmuxMobileShell/MobileShellComposite+MacSwitchState.swift)
keeps additive assignments per exact Mac app instance, clears them on sign-out,
and prunes to a retained foreground instance on a team change.
[`MobileWorkspaceAggregation.swift`](https://github.com/manaflow-ai/cmux/blob/0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc/Packages/iOS/CmuxMobileShellModel/Sources/CmuxMobileShellModel/MobileWorkspaceAggregation.swift)
preserves existing slots and appends sorted new IDs after the highest existing
slot. `MacPairingKey` canonicalizes UUID spelling and trims nullable build tags.
Stable and Nightly are separate instances; a legacy untagged pairing remains separate.

Android previously sorted the current set of physical device IDs on every render.
That shared colors across builds and recolored existing computers when earlier
IDs appeared or disappeared. `NativeMacColorSlots` now retains an additive typed
table in `NativeFeedSession`'s ViewModel. Discovery and saved rows for the same
instance deduplicate; route/endpoint changes do not change the display identity.
New IDs are sorted before assigning slots, and empty inventories retain the table.
The eight gradients wrap only at rendering, so more than eight instances reuse
colors without rewriting the assignments.

Login/user changes and sign-out reset assignments. Team changes prune old entries,
retaining only a still-admitted, connected foreground instance when present.
Same-scope refresh generations preserve colors. Discovery from another scope is
excluded. All Computers, saved rows, the selector, workspace avatars and detail
previews use the same instance key; standalone preview hashing also includes the
build tag. Custom palette/RGB choices continue to take precedence. Routing and
customization persistence are unchanged. The automatic table is intentionally
in-memory, as in the reviewed iOS implementation; process restart may reassign it.

Verification: **12 focused JVM tests passed**, zero failures/errors/skips, covering
discovery churn, saved/discovered deduplication, separate build tags, UUID/tag
normalization, immutable snapshots, palette wrapping, refresh and account/team
scope transitions, plus the existing appearance storage/presentation cases. The
final debug/test build passed in **36s**, followed by a **9s** asset-only build to
include attribution. The existing four editor/UI checks passed on the single
API 37 / 16 KB emulator: **OK (4 tests), 51.783 seconds**, zero skips. Custom
RGB/emoji and integrated detail/reopen screenshots were visually reviewed.

These UI tests verify editor regressions; multi-computer color assignment is
covered by the JVM checks and inspected screen wiring. They do not establish
physical multi-Mac, Activity-recreation, or full iOS visual acceptance. The custom
editor screenshot is a scrolled component fixture with the keyboard open. The
emulator was shut down; no new AVD or signed milestone was created. Evidence:
`captures/runtime/mac-color-slots/` (ignored). Packaged attribution matches source.

- Debug APK SHA-256: `51bd9a41b3eabc5f5380ca64adf2c622321ba9504eb47bfb5422c906229db65e`.
- Test APK SHA-256: `471235a9a4cf1c7f05c3d6673f5ba76f5ae3a2cd2de4eaefcf963615d7d9ea3c`.

Signed build 456 predates this change. The broader upstream pin remains unchanged.

## Local editing and presentation

Computer Details edits the name, color and icon independently. Empty/whitespace
names restore the host's name; Auto restores the default color or icon without
resetting other fields. Symbols use original Android vectors representing the
same ten choices; SF Symbols are not bundled on Android. Emoji render using the
device font. Custom RGB selection offers a hex field, RGB sliders and preview,
with a separate confirmation/cancel dialog.

The Computers picker, Settings computer rows, computer selector, workspace rows,
detail title/preview, task options and in-app notification labels resolve the
override. New background notifications resolve the current account's name when
delivered. Existing already-posted Android notifications and historical saved
draft labels are not rewritten in place. Workspace search includes the original
and customized names. Workspace avatars share their owning Mac's color/icon;
Mac app instances receive stable ordered slots shared across current surfaces,
with djb2 scalar hashing as the standalone-preview fallback. Sibling builds have
independent automatic assignments and independent overrides, as described above.

Appearance is display metadata. It never changes `PairedMac`, discovery,
connection origins, credential records, notification routes, task destinations,
or RPC targets. Notification presentation is decorated while retaining the
original source object. Editing appearance never dials the Mac and is available
while its authenticated account/team is still selected, even if discovery is
offline. A retired account/team detail cannot save.

## Persistence and failures

One atomic no-backup JSON file is scoped to Android application ID, Stack project,
user and team. Within it, device ID and nullable build tag form the exact key;
a null tag never aliases another build. Login generations do not enter the disk
key, so signing back into the same account/team preserves the customization.
An in-process shared StateFlow updates visible consumers without reconnecting.

Read-modify-write operations serialize and re-read the current file before
changing one field. The UI disables duplicate writes and publishes only a
successful disk commit. Read failures surface an inline retry and block writes;
a malformed store is not silently replaced. Invalid values, oversized records,
duplicate identities and oversized files are rejected. Erase All Local Data
uses Android's application-data reset, which also removes these files and ends
the process holding the in-memory cache.

## Verification (2026-09-29)

Twenty-eight focused JVM tests pass: eight appearance/storage/presentation cases,
nine notification aggregation cases and eleven per-computer/runtime cases. They
cover exact device/build and account/team keys, independent field updates,
reloading persisted state, malformed/duplicate records, failed commits, retired
owners, custom values, shared colors, and preserving notification identity and
destination while changing its display label.

The same main APK passed six existing Android 17 emulator UI cases (Computer
Details and Mac Power). In that run the new appearance class failed JUnit
initialization because one test inferred a non-Unit return type. After correcting
only that test declaration, its four appearance UI cases passed in **41.307
seconds**. They exercise independent resets, RGB/emoji validation, pending and
failed writes, and the integrated detail title plus reopen/persistence without
dialing. This is ten successful UI cases across two runs, not one clean initial
ten-case run. The failed run is retained. The initial emulator install was also
rejected as still booting; the later install succeeded after boot completion.

The main and test APKs built; main APK native ELF/RELRO and 16 KiB ZIP checks pass.
The first compilation found an unavailable BuildConfig reference (replaced with
the actual application package), and the second hit the existing main-screen
method-size limit. Extracting the computer rows and selector fixed that limit.
No native library changed. Integrated detail and custom-appearance fixture
screenshots were inspected; the latter has the keyboard open and is not a full
screen-layout reference.

| Artifact | SHA-256 |
| --- | --- |
| Main debug APK (all UI runs) | `06dcc9ae1a2fa4f6d24d085b203ca944ab50b6aca8fbd880f8cc114262c7493d` |
| Final app test APK | `638d4fc6fa4db5b081aeddbbd039b20dbad5c1742f0cf3fadef5d552e75e02e0` |
| Initial app test APK (JUnit initialization failure) | `dae261368240f2bfc118a2c6ea429e041a98e5d23941056e06c19a069a240046` |

Logs, XML, screenshots and receipts are in ignored
`captures/runtime/mac-appearance/`. Signed published build 157 is unchanged.
The main APK was installed successfully on the Pixel and its installed SHA-256
matched. At the end the Pixel was awake/unlocked in another app, so its UI was
not taken over for testing. No user appearance or Mac power setting was changed;
the USB-awake setting remained `0`. Live acceptance is still pending.

## Open work toward full iOS parity

- Live Pixel/Mac acceptance, including rename during a live terminal session,
  background notification delivery, account/team switching and process restart.
- Legacy Tailscale detail editing, complete route controls and account-wide
  Forget/revoke remain tracked separately. Connection role/count presentation and
  keep-awake list indicators were implemented in subsequent checkpoints.
- Full visual comparison with the running iOS app remains open. Native Android
  symbols, system emoji and the RGB picker provide corresponding controls;
  fixture screenshots alone do not establish complete iOS visual parity.

## Source correction: inactive backup implementation (2026-09-29)

The initial checkpoint incorrectly described account backup as active iOS behavior
based on `BackingUpPairedMacStore` and `PairedMacBackupClient` alone. Following the
production call chain changes that conclusion. At the pinned revision,
`ios/cmuxPackage/Sources/cmuxFeature/CMUXMobileRootScene.swift`:

- `openPairedMacStore` opens the local SQLite store.
- `makeBackedUpPairedMacStore` (lines 332–350) applies build compatibility and
  selected-team scoping, then returns `scopedStore`. Despite its name and stale
  comment, it does **not** construct `BackingUpPairedMacStore`.
- `makeStore` (lines 502–533) adds the demo overlay and passes this store directly
  to `CMUXMobileShellStore`; there is no backup wrapper at that call site.

Local-only customization is therefore consistent with the active pinned iOS
composition. Cloud synchronization is not a verified parity requirement for this
pin. Recheck composition when updating upstream before implementing any sync.
This corrects the earlier source interpretation; it does not establish complete
UI parity or physical-device acceptance.

The dormant library still documents a possible future service contract:
`GET`/`POST /v1/sync/paired-macs` on the presence service, captured-user Bearer
authentication, `X-Cmux-Team-Id`, server team echo, revision comparisons and exact
instance tombstones. Explicit customization updates distinguish omitted fields
from null clears and use `instanceTagWriteMode: preserve`. These are research
notes, **not authority to activate that endpoint**. No sync request was sent and
no cloud writer was added.
