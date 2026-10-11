import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { NativePushSourceAdapter, NativePushSourceError, encodeNativePushFrame } from './native-source.mjs';
import { PushSourceJournal } from './source-journal.mjs';
import { PushRegistrations } from './registrations.mjs';
import { PushOutbox } from './outbox.mjs';
import { PushForwarder } from './forwarder.mjs';
import { readPushHost, hostPermitsDelivery } from './policy.mjs';

const time = 1_800_000_000_000, key = Buffer.alloc(32, 41), sessionID = '00000000-0000-4000-8000-000000000010';
const helper = { senderKeyID: 'helper-key', macInstallationID: 'helper-install', publicKey: Buffer.alloc(32, 1).toString('base64') };
const host = (now = time) => ({ type: 'host', snapshot: { state: 'ready', epoch: 'fixture-epoch', observedAt: now, validUntil: now + 30_000,
  authority: { accountID: 'fixture-account', teamID: null, macDeviceID: 'fixture-mac', macInstanceTag: 'stable', macBuildID: 'fixture-build' },
  settings: { forwardingEnabled: true, mode: 'always', admission: 'allowed', hideContent: false } } });
const request = (id = 11) => ({ type: 'capture', request: { sourceEpoch: 'fixture-epoch', phoneEligible: true, expiresAt: time + 120_000,
  event: { kind: 'notify', correlationID: `00000000-0000-4000-8000-${String(id).padStart(12, '0')}`, badgeCount: 1, hideContent: false,
    title: 'Private title λ 中', subtitle: '', body: 'Private body', replyShape: 'text', retargetsToLiveSurfaceOwner: true,
    workspaceId: 'workspace', surfaceId: null, notificationId: 'notice' } } });
const frame = (body, sequence = 1, options = {}) => encodeNativePushFrame({ sessionID, sequence, body, key, ...options });
function basic(options = {}) {
  let now = time, changes = 0; const captured = [];
  const adapter = new NativePushSourceAdapter({ key, sessionID, helper, now: () => now,
    changed: () => changes++, capture: value => { captured.push(value); return { kind: 'captured', id: 'not-exposed' }; }, ...options });
  return { adapter, captured, clock: value => { now = value; }, changes: () => changes };
}
function integration(t, capacity = 128) {
  const directory = mkdtempSync(join(tmpdir(), 'cmux-native-push-')), storageKey = Buffer.alloc(32, 28);
  let now = time, sequence = 1, journal = new PushSourceJournal({ directory, key: storageKey, now: () => now, capacity });
  const registrations = new PushRegistrations({ directory, key: storageKey });
  registrations.replace({ token: 'fixture-fcm-token', publicKey: Buffer.alloc(32, 2).toString('base64'), recipient: {
    installationID: 'phone-install', keyID: 'phone-key', senderKeyID: helper.senderKeyID, tuple: {
      ...host().snapshot.authority, iosBuildID: 'android.fixture', iosInstallationID: 'phone-install' } } });
  const outbox = new PushOutbox({ directory, key: storageKey, now: () => now, random: () => 0 });
  let forwarder; const payloads = [], sent = [];
  const adapter = new NativePushSourceAdapter({ key, sessionID, helper, now: () => now,
    changed: () => forwarder?.changed(), capture: value => journal.accept(value) });
  const sender = { send: async (delivery, { permits }) => { assert.equal(permits(), true); sent.push(delivery); return { kind: 'accepted' }; } };
  forwarder = new PushForwarder({ registrations, outbox, sender, now: () => now, readHost: () => adapter.readHost(),
    setTimer: () => 1, clearTimer: () => {}, seal: async ({ recipient, plaintext }) => {
      payloads.push(JSON.parse(plaintext)); return { ...recipient, version: 2,
        encapsulatedKey: Buffer.alloc(32, 3).toString('base64'), ciphertext: Buffer.alloc(32, 4).toString('base64') };
    } });
  const send = body => { const receipt = adapter.accept(frame(body, sequence)); if (receipt.acknowledged) sequence++; return receipt; };
  t.after(async () => { adapter.close(); await forwarder.stop(); journal.close(); outbox.close(); registrations.close(); rmSync(directory, { recursive: true, force: true }); });
  return { adapter, send, forwarder, outbox, payloads, sent, journal: () => journal, now: () => now, clock: value => { now = value; },
    reopen: () => { journal.close(); journal = new PushSourceJournal({ directory, key: storageKey, now: () => now, capacity }); },
    deliver: () => outbox.drain({ sender, permits: (binding, admission) => hostPermitsDelivery(readPushHost(() => adapter.readHost(), () => now), binding, admission), retire: () => {} }) };
}

test('construction is passive and a verified snapshot is detached from callers and helper authority', () => {
  const f = basic(); assert.equal(f.adapter.readHost(), null); assert.equal(f.changes(), 0); assert.equal(f.captured.length, 0);
  assert.equal(f.adapter.accept(frame(host())).kind, 'state-updated'); assert.equal(f.changes(), 1);
  const snapshot = f.adapter.readHost(); assert.equal(snapshot.authority.senderKeyID, helper.senderKeyID);
  snapshot.settings.forwardingEnabled = false; snapshot.authority.accountID = 'other';
  assert.equal(f.adapter.readHost().settings.forwardingEnabled, true); assert.equal(f.adapter.readHost().authority.accountID, 'fixture-account');
});
test('actual Apple CryptoKit frames interoperate including Unicode, escapes and explicit dismissals', () => {
  const f = basic(), frames = JSON.parse(readFileSync(new URL('./test-support/native-source-frames.json', import.meta.url)));
  assert.deepEqual(frames.map(value => f.adapter.accept(value).kind), ['state-updated', 'captured', 'captured']);
  assert.equal(f.captured[0].event.title, 'Ready λ 中 👩🏽‍💻'); assert.equal(f.captured[0].event.body, 'Line one\n"next" \\ /');
  assert.deepEqual(f.captured[1].event.notificationIds, ['fixture-notice', 'other-中']); assert.equal(f.captured[1].phoneEligible, false);
});
test('wrong key, foreign session, changed body and noncanonical proof cannot publish host state', () => {
  for (const mutate of [value => { value.proof = Buffer.alloc(32, 0).toString('base64'); },
    value => { value.sessionID = '00000000-0000-4000-8000-000000000099'; },
    value => { value.body = Buffer.from(JSON.stringify({ ...host(), injected: true })).toString('base64'); },
    value => { value.proof += '\n'; }, value => { value.extra = true; }]) {
    const f = basic(), value = frame(host()); mutate(value);
    assert.throws(() => f.adapter.accept(value), NativePushSourceError); assert.equal(f.adapter.readHost(), null); assert.equal(f.changes(), 0);
  }
});
test('sequence gaps and altered acknowledged frames fail while an identical lost receipt is replayed', () => {
  for (const sequence of [0, 1.5, Number.MAX_SAFE_INTEGER])
    assert.throws(() => frame(host(), sequence), NativePushSourceError);
  const f = basic(); assert.throws(() => f.adapter.accept(frame(host(), 2)), NativePushSourceError);
  const first = frame(host()), receipt = f.adapter.accept(first); receipt.kind = 'caller-mutated';
  assert.equal(f.adapter.accept(first).kind, 'state-updated'); assert.equal(f.changes(), 1);
  const altered = host(); altered.snapshot.settings.hideContent = true;
  assert.throws(() => f.adapter.accept(frame(altered)), NativePushSourceError);
  assert.equal(f.adapter.readHost().settings.hideContent, false);
});
test('snapshot lease, account shape and helper-key substitution are rejected before policy changes', () => {
  for (const mutate of [value => { value.snapshot.observedAt++; }, value => { value.snapshot.validUntil++; },
    value => { value.snapshot.validUntil = time; }, value => { value.snapshot.authority.senderKeyID = 'injected'; },
    value => { value.snapshot.authority.teamID = 42; }, value => { value.snapshot.epoch = '\ud800'; },
    value => { value.snapshot.settings.mode = 'maybe'; }]) {
    const f = basic(), value = host(); mutate(value); assert.throws(() => f.adapter.accept(frame(value)), NativePushSourceError);
    assert.equal(f.adapter.readHost(), null);
  }
});
test('missing and expired source snapshots do not acknowledge or store a native alert', () => {
  const f = basic(); assert.equal(f.adapter.accept(frame(request())).kind, 'source-unavailable'); assert.equal(f.captured.length, 0);
  f.adapter.accept(frame(host())); f.clock(time + 30_000);
  assert.equal(f.adapter.accept(frame(request(), 2)).acknowledged, false); assert.equal(f.captured.length, 0);
  f.adapter.accept(frame(host(time + 30_000), 2));
  assert.equal(f.adapter.accept(frame(request(), 3)).kind, 'captured'); assert.equal(f.captured[0].expiresAt, time + 120_000);
});
test('sign-out and changed account epoch retire late events without source storage', () => {
  const f = basic(); f.adapter.accept(frame(host()));
  f.adapter.accept(frame({ type: 'host', snapshot: { state: 'signed-out', observedAt: time, validUntil: time + 30_000 } }, 2));
  assert.equal(f.adapter.accept(frame(request(), 3)).kind, 'retired'); assert.equal(f.captured.length, 0);
  const next = host(); next.snapshot.epoch = 'new-epoch'; f.adapter.accept(frame(next, 4));
  assert.equal(f.adapter.accept(frame(request(), 5)).kind, 'retired'); assert.equal(f.captured.length, 0);
});
test('storage errors return coarse nonacknowledgments and retry the unchanged request', () => {
  let failed = true; const captured = [];
  const f = basic({ capture: request => { if (failed) throw new Error('private diagnostic not exposed'); captured.push(request); return { kind: 'captured' }; } });
  f.adapter.accept(frame(host())); const value = frame(request(), 2), receipt = f.adapter.accept(value);
  assert.deepEqual(receipt, { sequence: 2, kind: 'storage-unavailable', acknowledged: false });
  assert.equal(JSON.stringify(receipt).includes('private'), false); failed = false;
  assert.equal(f.adapter.accept(value).kind, 'captured'); assert.equal(captured.length, 1);
  assert.equal(f.adapter.accept(value).kind, 'captured'); assert.equal(captured.length, 1);
});
test('invalid event shape and history claims cannot bypass typed admission', () => {
  const f = basic(); f.adapter.accept(frame(host()));
  for (const mutate of [v => { v.request.phoneEligible = 'true'; }, v => { v.request.event.replyShape = 'html'; },
    v => { v.request.event.badgeCount = -1; }, v => { v.request.event.workspaceId = {}; },
    v => { v.request.event.body = '\ud800'; }, v => { v.request.authority = host().snapshot.authority; }]) {
    const value = request(); mutate(value); assert.throws(() => f.adapter.accept(frame(value, 2)), NativePushSourceError);
  }
  assert.equal(f.captured.length, 0);
});
test('encrypted journal preserves capture, replay and exact content conflict across reopen', t => {
  const f = integration(t); f.send(host()); const value = request(); assert.equal(f.send(value).kind, 'captured'); f.reopen();
  assert.equal(f.send(value).kind, 'duplicate'); assert.deepEqual(f.journal().status().counts, { pending: 1 });
  const conflicting = request(); conflicting.request.event.body = 'different';
  assert.throws(() => f.send(conflicting), NativePushSourceError); assert.deepEqual(f.journal().status().counts, { pending: 1 });
  assert.equal(f.send(request(12)).kind, 'captured');
});
test('full journal reports backpressure and a fresh policy frame can precede the unchanged retry', t => {
  const f = integration(t, 1); f.send(host()); f.send(request());
  const later = request(12); later.request.expiresAt = time + 180_000;
  assert.equal(f.send(later).kind, 'backpressure'); f.clock(time + 121_000); f.send(host(f.now()));
  assert.equal(f.send(later).kind, 'captured'); assert.deepEqual(f.journal().status().counts, { pending: 1 });
});
test('native eligible alert reaches durable fanout with exact route and host-owned privacy', async t => {
  const f = integration(t); const state = host(); state.snapshot.settings.hideContent = true; f.send(state); f.send(request());
  assert.equal((await f.journal().drain({ forwarder: f.forwarder }))[0].kind, 'queued');
  assert.equal(f.payloads[0].title, 'cmux'); assert.equal(f.payloads[0].body, 'New terminal activity');
  assert.equal(f.payloads[0].workspaceId, 'workspace'); assert.equal(f.payloads[0].retargetsToLiveSurfaceOwner, true);
  await f.deliver(); assert.equal(f.sent.length, 1);
});
test('history-only alert is suppressed but dismissal forwards explicit IDs while Mac is active', async t => {
  const f = integration(t); f.send(host()); const history = request(); history.request.phoneEligible = false; f.send(history);
  assert.equal((await f.journal().drain({ forwarder: f.forwarder }))[0].kind, 'suppressed'); assert.equal(f.payloads.length, 0);
  const active = host(); active.snapshot.settings.mode = 'onlyWhenAway'; active.snapshot.settings.admission = 'suppressed_mac_active'; f.send(active);
  const dismissal = { type: 'capture', request: { sourceEpoch: 'fixture-epoch', phoneEligible: false, expiresAt: time + 120_000,
    event: { kind: 'dismiss', correlationID: request(12).request.event.correlationID, badgeCount: 0, hideContent: false, notificationIds: ['notice', 'other'] } } };
  f.send(dismissal); assert.equal((await f.journal().drain({ forwarder: f.forwarder }))[0].kind, 'queued');
  assert.deepEqual(f.payloads[0].notificationIds, ['notice', 'other']); await f.deliver(); assert.equal(f.sent.length, 1);
});
test('native privacy revocation before delivery prevents already prepared content reaching provider', async t => {
  const f = integration(t); f.send(host()); f.send(request()); await f.journal().drain({ forwarder: f.forwarder });
  const state = host(); state.snapshot.settings.hideContent = true; f.send(state);
  await f.deliver(); assert.equal(f.sent.length, 0);
});
test('closing the native source immediately invalidates host policy and rejects further frames', () => {
  const f = basic(); f.adapter.accept(frame(host())); f.adapter.close(); f.adapter.close();
  assert.equal(f.adapter.readHost(), null); assert.equal(f.changes(), 2); assert.equal(f.captured.length, 0);
  assert.throws(() => readPushHost(() => f.adapter.readHost(), () => time));
  assert.throws(() => f.adapter.accept(frame(request(), 2)), NativePushSourceError);
  assert.equal(key.equals(Buffer.alloc(32, 41)), true, 'adapter owns its key copy');
});
