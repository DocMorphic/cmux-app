# Computer appearance

Reference: cmux `4c5272e9153eca2033c9f40ac749f0c3a5bcb291`:

- `MacComputerDetailView.swift`: name submit, eight palettes, custom RGB color,
  ten symbols, ten emoji choices, custom emoji and independent Auto choices.
- `MachineAvatarColors.swift`, `MacAvatarIcon.swift`, `MachineAvatarPalette.swift`:
  shared machine color, custom palette/RGB precedence and stable scalar hash.
- `MobilePairedMacStore.swift` and `BackingUpPairedMacStore.swift`: exact account,
  team and Mac/build customization; explicit customization updates are also
  mirrored to account backup by iOS.

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
distinct visible Mac IDs receive ordered palette slots shared across current
surfaces, with djb2 scalar hashing as the standalone-preview fallback. Sibling
builds share the automatic machine color and retain independent overrides.

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

- **Account backup/restore is not yet implemented.** These local customizations
  currently stay on this Android installation. This is remaining implementation
  work, not an unavoidable Android difference. Port the explicit customization
  authority, exact-instance/account/team scope and tombstone rules from
  `BackingUpPairedMacStore`/`PairedMacBackupClient`; routine discovery refresh must
  not overwrite newer remote customizations or resurrect revoked Macs.
  The reference client uses `GET`/`POST /v1/sync/paired-macs` on the presence
  service, with captured-user Bearer authentication and `X-Cmux-Team-Id`.
  `PairedMacBackupRecordWire` distinguishes omitted customization fields from
  explicit null clears and carries `instanceTagWriteMode: preserve` for these
  edits. Resolve the configured presence base URL, server team echo, revision
  checks, route-disclosure policy and migration/tombstone handling before adding
  a writer; do not assume the Iroh V2 enrollment base is the backup service.
- Live Pixel/Mac acceptance, including rename during a live terminal session,
  background notification delivery, account/team switching and process restart.
- Legacy Tailscale detail editing, keep-awake list indicators, complete connection
  roles/routes, and account-wide Forget/revoke remain tracked separately.
- Full visual comparison with the running iOS app remains open. Native Android
  symbols, system emoji and the RGB picker provide corresponding controls;
  fixture screenshots alone do not establish complete iOS visual parity.
