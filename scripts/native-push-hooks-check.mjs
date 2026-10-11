import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { NativePushSourceAdapter, encodeNativePushFrame } from '../push/native-source.mjs';
import { PushSourceJournal } from '../push/source-journal.mjs';

const cases = JSON.parse(readFileSync(process.argv[2])), time = 1_800_000_000_000;
assert.deepEqual(Object.keys(cases).sort(), ['first', 'notify', 'dismiss', 'repeat', 'unknown', 'recovered', 'teamB', 'teamA',
  'signOut', 'afterSignOut', 'admittedWhileActive', 'rejected'].sort());
const directory = mkdtempSync(join(tmpdir(), 'cmux-native-hooks-'));
const journal = new PushSourceJournal({ directory, key: Buffer.alloc(32, 28), now: () => time });
const key = Buffer.alloc(32, 41), sessionID = '00000000-0000-4000-8000-000000000010';
const adapter = new NativePushSourceAdapter({ key, sessionID, helper: { senderKeyID: 'fixture-helper',
  macInstallationID: 'fixture-install', publicKey: Buffer.alloc(32, 1).toString('base64') }, capture: value => journal.accept(value),
  changed: () => {}, now: () => time });
let sequence = 0;
const send = body => adapter.accept(encodeNativePushFrame({ key, sessionID, sequence: ++sequence, body }));
try {
  const first = cases.first.snapshot; assert.equal(send(cases.first).kind, 'state-updated');
  assert.equal(first.authority.accountID, 'fixture-account'); assert.equal(first.authority.teamID, 'fixture-team');
  assert.equal(first.validUntil, time + 30_000);
  for (const name of ['notify', 'dismiss']) {
    assert.equal(send(cases[name][0]).kind, 'state-updated'); assert.equal(send(cases[name][1]).kind, 'captured');
    assert.equal(cases[name][1].request.expiresAt, time + 120_000);
    assert.equal(cases[name][1].request.sourceEpoch, first.epoch);
  }
  const notify = cases.notify[1].request;
  assert.equal(notify.phoneEligible, true); assert.equal(notify.event.replyShape, 'none');
  assert.equal(notify.event.title, 'Ready λ 中 👩🏽‍💻'); assert.equal(notify.event.body, 'Line one\n"next" \\ /');
  assert.equal(notify.event.surfaceId, null); assert.equal(notify.event.workspaceId, 'fixture-workspace');
  assert.equal(notify.event.retargetsToLiveSurfaceOwner, true); assert.equal(notify.event.badgeCount, 3);
  assert.deepEqual(cases.dismiss[1].request.event.notificationIds, ['notice-1', 'other-中']);
  assert.equal(cases.dismiss[1].request.phoneEligible, false);
  assert.equal(cases.repeat.snapshot.epoch, first.epoch); send(cases.repeat);
  assert.equal(send(cases.unknown).kind, 'state-unavailable'); assert.equal(adapter.readHost(), null);
  assert.equal(cases.recovered.snapshot.epoch, first.epoch); send(cases.recovered);
  send(cases.teamB); assert.notEqual(cases.teamB.snapshot.epoch, first.epoch);
  send(cases.teamA); assert.notEqual(cases.teamA.snapshot.epoch, first.epoch);
  assert.notEqual(cases.teamA.snapshot.epoch, cases.teamB.snapshot.epoch);
  send(cases.signOut); assert.equal(adapter.readHost().state, 'signed-out');
  send(cases.afterSignOut); assert.notEqual(cases.afterSignOut.snapshot.epoch, cases.teamA.snapshot.epoch);
  send(cases.admittedWhileActive[0]); assert.equal(send(cases.admittedWhileActive[1]).kind, 'captured');
  assert.equal(cases.admittedWhileActive[1].request.phoneEligible, true);
  assert.equal(cases.admittedWhileActive[1].request.event.replyShape, 'text');
  assert.equal(cases.admittedWhileActive[0].snapshot.settings.hideContent, true);
  assert.equal(cases.rejected, 6); assert.deepEqual(journal.status().counts, { pending: 3 });
  console.log('Native payload mapping, preserved expiry, six invalid inputs, unknown recovery, team ABA, sign-out and durable admission passed');
} finally { adapter.close(); journal.close(); rmSync(directory, { recursive: true, force: true }); }
