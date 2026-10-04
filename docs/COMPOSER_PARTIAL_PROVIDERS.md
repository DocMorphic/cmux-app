# Partial attachment provider failures — 2026-10-04

## Source contract

The scoped iOS reference is `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`:
[MobilePasteboardAttachments.swift](https://github.com/manaflow-ai/cmux/blob/0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc/Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/MobilePasteboardAttachments.swift).
Its `materializeAttachments` visits providers in order, skipping a provider whose
image/file cannot be materialized and keeping the other readable items.
This continues [system Paste support](COMPOSER_SYSTEM_PASTE.md); the global upstream
parity pin has not moved.

## Android behavior

- Explicit composer Paste skips a failed provider MIME lookup and reports it.
  Other content URIs stay ordered; captions and URI fallback text stay out of the
  prompt. If every attachment fails, the action remains consumed.
- Native terminal, SSH and New Task composer staging recover from individual
  preparation failures. The New Task attachment menu shares the same clipboard
  path, and its photo/file picker shares the preparation behavior. New Task reports
  unreadable items after successful staging so its draft-change callback cannot
  immediately erase that notice.
- Account/terminal/editor changes and cancellation stop staging. Draft writes and
  attachment capacity checks remain outside the recoverable preparation boundary.
  A storage failure stops the batch instead of being misclassified as an unreadable
  provider. Existing successful attachments remain in the draft.
- Direct terminal paste keeps its existing stop-on-error ordering. This change
  does not retry or skip uncertain remote uploads/input. SSH still accepts images,
  with document upload available through Files.

## Verification

Five new JVM cases pass: ordered partial preparation, retired-owner rejection for
both late success/failure, explicit cancellation, a noncooperative provider after
coroutine cancellation, and draft-storage failure stopping subsequent reads.

The initial Android run passed 13 cases and failed two fixture preconditions
(75.731 s). Android's ContentResolver returns null for the invalid FileProvider
root's MIME lookup instead of propagating its exception. Correcting that assertion
allowed both real task paste/upload cases to pass (35.352 s). Their clipboard
contains a readable first file, an invalid provider root, a missing file with a
known MIME type, and a readable last file. They assert prompt preservation,
encrypted staging contents, no upload before Create Task, and exact upload order
and bytes. This does not establish the exceptional MIME-lookup branch itself.

The other 13 cases cover seven SSH input cases (including new partial image paste
and deferred ordered upload), five shared composer editor regressions, and native
terminal direct-image ordering with an unchanged composer draft.

After fixing the draft-change callback's error clearing, both task cases passed
again in **35.903 s**, now asserting the partial-failure notice remains present.
In total, **15 distinct Android cases pass across runs**; only these two task
cases were rerun after the final task-notice change. Debug and test assembly pass
(initial 1m 32s, test-only fixture rebuild 24s, final 33s), and final debug engine
packaging checks pass. No release/ART or signed build was run. Evidence is retained
under ignored `captures/runtime/composer-partial-providers/`.

The single existing API 37 / 16 KB arm64 emulator is stopped. ADB showed no physical
Pixel before or after the checks; no phone installation, data clearing or live Mac
operation was performed.

Physical Pixel/Mac acceptance, external provider grant lifetime, drag/drop,
whole-process recreation, authenticated upgrade and the broader parity audit remain
open. Android feed and push configuration remain open. Build 494 remains the last
signed milestone; no new signed APK is published for this change.
