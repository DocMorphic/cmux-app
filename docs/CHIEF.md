# Chief conversations

The first Android Chief destination is mounted only when the exact admitted
foreground Mac advertises all seven required methods. It adds Home and the
workspace toolbar action, resets to Workspaces when support is lost and keeps
the existing native account, pairing and foreground-owner admission. The current
custom Mac at upstream `f4b15090` does not advertise Chief.

This scoped port follows cmux `a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`.
`third_party/cmux-chief/manifest.json` pins the client/question source hashes and
separate Home/backend/shell/artwork hashes. The global implemented/reviewed pins
are unchanged. The avatar is copied unmodified and attributed in both notices.

Home sorts pinned/recent rows, searches title/preview, shows unread/working
state, reports real startup failures and waits on the Mac's opaque change cursor
with bounded retry. A started-but-empty host can supply its default conversation.
Transcript sessions use event-backed reads, fill missed-page gaps, retain older
action cards, page history, track working/read state and mark visible rows read.
The same owner retains query, drafts and navigation state across recomposition;
backgrounding pauses reads. Account/Mac retirement cancels operations and clears
content. Every request still uses the real client's admission checks, without a
terminal selection ticket.

Sends use stable client IDs. Poll/read echoes replace the optimistic row while
keeping its lazy-row identity, including canonical IDs first learned from a late
acknowledgment. Immediate accepted-send reads permit the source's five-second
clock skew; ordinary poll/history text fallback remains strict. Exact IDs win
across a whole page before text matching. Already-correlated rows and sequence
watermarks prevent an old identical message from absorbing a newer send. No
ambiguous write is automatically resent. In-flight sends trail acknowledged
history; failed sends retain their source sequence anchor before later replies.

Failed sends expose the source's Try Again / Delete / Cancel actions. Explicit
retry reuses the original client ID, refreshes the send time, clears the failed
anchor and moves the row to the in-flight tail. It reserves the row before
suspension, preventing repeated taps from issuing concurrent retries. Source
`ChiefService+Control.swift` maps that ID to `IdempotencyKey` for the host store;
its hash and the source gesture sheet are now in the behavior manifest. Delete
removes only a failed local row, never writes to the host and suppresses later
echoes with known deleted IDs for this retained owner session. A distinct send
with identical text remains visible. Owner retirement clears these local sets.
Long transport-error detail lives in a bounded scrolling area inside the action
sheet, keeping the row's retry control reachable with the keyboard and enlarged
text. Failed sends are never retried by polling or refresh.

Approval/question actions reserve a card before suspension. A confirmed commit
with a failed refresh stays reserved until its receipt; an unconfirmed answer
can be explicitly retried without polling resending it. Old history cards get a
targeted reread. Work parts show title/status/preview; settled cards show receipts.
Full questions support single/multiple choices, one Other value, qualified IDs,
defaults and previews. The Mac owns the final answer and respondent.

The transcript now applies first-in-run spacing, hour-gap separators and loaded
parent quote previews. Direct conversations omit redundant sender headings.
Timestamp date/time follows the device's locale, zone and clock preference; the
English/Japanese labels and date/time format are exact selected source values.
Home includes relative update stamps and the bounded pinned-only invitation;
the header uses a spoken Stop control and single-line titles at enlarged sizes.
These are scoped presentation steps, not a complete generic conversation port.

Evidence is ignored under `captures/recovery-20261020/`:

- Actual pinned Swift execution: 21 wire-model cases, nine client requests and
  eight shared question-validation cases; source/artwork hashes verified.
- 43 focused JVM cases pass, including six new send-correlation regressions and
  comparisons of 19 vectors from the actual Swift run-plan, store sort method
  and rowID property with an inert Chief-only model. Six timestamp resources
  match the pinned source catalog. The full JVM run passed 2,986, skipped four,
  zero failures (`chief-timeline-final-build-and-jvm.log`).
- Eight API 36 ARM64 Android cases pass in 20.426 s, including failed ordering,
  late-echo identity, separators/quotes, explicit answer retry, full question
  forms, owner retirement, root capability gating and a font-scale-2 probe.
  A two-case inset-capture rerun passed in 5.110 s. The isolated harness now
  mirrors the production parent's system-bar inset consumption; the actual
  root and final timeline/enlarged-text screenshots were inspected. The crash
  buffer is empty. This does not establish physical keyboard/IME or TalkBack.
- The later retry/delete batch adds four session regressions (31 session and
  two timeline cases pass) and passes the full JVM suite: 2,990 passed, four
  skipped, zero failures (`chief-retry-final-build-and-jvm.log`). The source
  checks verify eight en/ja action strings as well as the six timestamp values.
  The initial enlarged-error UI run was stopped after exposing an unreachable
  action. A later system-font run passed its partial-visibility assertion but
  visual inspection found Cancel overlapping the navigation bar; it is not
  accepted as layout proof. The final implementation fully expands the sheet,
  bounds its error detail and respects bottom insets. All eight Chief UI cases
  pass in 16.694 s (`chief-retry-android-insets-final.log`). A stronger full-button
  bounds assertion plus actual Cancel/reopen/Delete interaction passes with
  Android system font scale 2.0 in 4.494 s. The final system-font screenshot was
  inspected; the emulator font setting was restored. This remains emulator,
  not physical-device or TalkBack acceptance.

This is an initial mounted implementation, not Chief parity acceptance. Finish
exact bubble/tail/drawer geometry and colors, thread/reply interactions,
unread/catch-up behavior, history page/window trimming, remaining localization
and TalkBack. Deeper uncertainty recovery, serialized sends and send/retry scroll behavior also
remain open. Verify cold process and connection replacement restoration and
wide-screen navigation. Compare matched iOS states and exercise a supporting
real Mac with the Pixel. The current custom Mac remains at `f4b15090` without
Chief, so its successful menu update is not Chief supporting-host evidence.
Local signed candidate 705 includes this mounted port and timeline at clean
source `6f1d7446`. Its stable signer, native libraries/alignment, viewer assets,
debug-component exclusion and real Firebase resources passed strict verification.
It is prepared for a data-preserving Pixel upgrade, not installed or published;
see HANDOFF.md for the exact source/hash and receipts. Candidate 704 is retained
as an older pre-UI artifact.
The new failed-send actions postdate candidate 705 and are currently source/debug
validation work, not part of the prepared signed Pixel candidate.
