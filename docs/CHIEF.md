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

Sends use stable client IDs. Poll/read echoes replace the optimistic row, and
lost or delayed acknowledgments never trigger automatic resending. Approval and
question actions reserve a card before suspension; a committed action with a
failed refresh remains reserved until its receipt. An unconfirmed answer can be
explicitly retried; refresh and polling do not resend it. Old history cards receive
a targeted reread. Work parts show title/status/preview. Settled cards show receipts,
and an earlier settled/unsupported part cannot hide a later pending card. Full
questions support single/multiple choices, one Other value, qualified item/option
IDs, defaults and previews; the Mac owns the final answer and respondent.

Evidence is ignored under `captures/recovery-20261020/`:

- Actual pinned Swift execution: 21 wire-model cases, nine client requests and
  eight shared question-validation cases; source/artwork hashes verified.
- 35 focused JVM cases pass, including 21 new session/form/race cases. A full
  JVM run passed 2,978, skipped four and failed zero.
- Five API 36 ARM64 Android cases pass in 15.644 s: Home search, send echo/draft
  retention, full question/receipt, owner retirement, actual root/framed RPC
  integration and incomplete-capability exclusion (some cases cover multiple
  behaviors), including an explicit retry after an uncertain answer. A targeted
  capture rerun passed in 3.976 s; transcript and question-sheet screenshots were
  inspected. No crash-buffer
  entries appeared in this isolated run.

This is an initial mounted implementation, not Chief parity acceptance. Finish
the complete generic conversation presentation comparison: dates, grouping,
reply references, unread/catch-up markers, relative times, scroll/window trimming,
locale strings and enlarged-text/TalkBack behavior. Verify cold process and
connection replacement restoration, wide-screen navigation, older-host send
correlation without echoed IDs (including clock skew), optimistic-row ordering
during concurrent incoming messages, and broader uncertainty recovery.
Compare matched iOS states and exercise a supporting real Mac with the Pixel.
The existing private host is not supporting-host evidence. Local signed candidate
704 predates this UI milestone and has not yet been installed on the Pixel.
