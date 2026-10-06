import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { PushForwarder } from './forwarder.mjs';
import { PushOutbox } from './outbox.mjs';
import { PushRegistrations } from './registrations.mjs';
import { FcmSender } from './fcm.mjs';
import { readPushHost, hostAdmitsEvent, hostPermitsDelivery, PushHostUnavailable } from './policy.mjs';

const time = 1_800_000_000_000;
const authority = { accountID: 'account', teamID: 'team', macDeviceID: 'mac', macInstanceTag: 'stable', macBuildID: 'build',
  senderKeyID: 'helper-key', macInstallationID: 'helper-installation', publicKey: Buffer.alloc(32, 2).toString('base64') };
const host = () => ({ state: 'ready', epoch: 'session-one', observedAt: time, validUntil: time + 30_000, authority: { ...authority },
  settings: { forwardingEnabled: true, mode: 'always', admission: 'allowed', hideContent: false } });
const event = () => ({ kind: 'notify', correlationID: '00000000-0000-4000-8000-000000000001', title: 'Private title', subtitle: '',
  body: 'Private content', badgeCount: 1, replyShape: 'text', workspaceId: 'workspace', surfaceId: 'surface', notificationId: 'notice',
  retargetsToLiveSurfaceOwner: false, hideContent: false });
const request = () => ({ event: event(), expiresAt: time + 120_000, sourceEpoch: 'session-one', phoneEligible: true });
function seal({ recipient, plaintext }) {
  return { ...structuredClone(recipient), version: 2, encapsulatedKey: Buffer.alloc(32, 3).toString('base64'),
    ciphertext: Buffer.alloc(plaintext.length + 16, 4).toString('base64') };
}
function fixture(t, { encrypt = seal, sender, capacity = 128 } = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'cmux-forwarder-')), key = Buffer.alloc(32, 9), timers = new Map();
  let snapshot = host(), now = time, next = 0, outbox;
  const registrations = new PushRegistrations({ directory, key });
  const queues = [];
  function open() { outbox = new PushOutbox({ directory, key, now: () => now, random: () => 0, capacity }); queues.push(outbox); return outbox; }
  open(); const sent = [], payloads = [];
  const enroll = (phone = 'phone', overrides = {}) => registrations.replace({ token: `${phone}-token`, publicKey: Buffer.alloc(32, 5).toString('base64'),
    recipient: { installationID: phone, keyID: `${phone}-key`, senderKeyID: authority.senderKeyID,
      tuple: { accountID: authority.accountID, teamID: authority.teamID, macDeviceID: authority.macDeviceID,
        macInstanceTag: authority.macInstanceTag, macBuildID: authority.macBuildID, iosBuildID: 'android', iosInstallationID: phone, ...overrides } } });
  const first = enroll(); const runtimes = [];
  function runtime() {
    const f = new PushForwarder({ registrations, outbox, sender: sender ?? { send: async delivery => { sent.push(delivery); return { kind: 'accepted' }; } },
      readHost: () => snapshot, now: () => now, seal: args => { payloads.push(JSON.parse(args.plaintext)); return encrypt(args); },
      setTimer: (callback, delay) => { const id = ++next; timers.set(id, { callback, at: now + delay }); return id; }, clearTimer: id => timers.delete(id) });
    runtimes.push(f); return f;
  }
  t.after(async () => { await Promise.all(runtimes.map(r => r.stop())); queues.forEach(q => q.close()); registrations.close(); rmSync(directory, { recursive: true, force: true }); });
  return { directory, key, runtime, outbox: () => outbox, open, registrations, enroll, first, sent, payloads,
    snapshot: () => snapshot, setHost: value => { snapshot = value; }, clock: value => { now = value; }, now: () => now,
    async fire() { const entry = [...timers].sort((a, b) => a[1].at - b[1].at)[0]; assert.ok(entry, 'scheduled work');
      const [id, item] = entry; timers.delete(id); now = Math.max(now, item.at); await item.callback(); } };
}

test('eligible event fans out only to this host/account/team and carries host-owned privacy', async t => {
  const f = fixture(t); f.enroll('second'); f.enroll('other', { macDeviceID: 'other' }); f.enroll('other-team', { teamID: 'other-team' });
  f.snapshot().settings.hideContent = true;
  const r = f.runtime(), input = request(); input.event.hideContent = false;
  const prepared = await r.prepare(input);
  assert.equal(prepared.jobs.length, 2);
  assert.ok(f.payloads.every(p => p.title === 'cmux' && p.body === 'New terminal activity'));
  assert.ok(prepared.jobs.every(j => j.admission.hideContent && j.admission.hostEpoch === 'session-one'));
  assert.equal(JSON.stringify(prepared).includes('Private content'), false);
  assert.deepEqual(r.enqueue(prepared), { kind: 'queued', queued: 2, duplicates: 0 });
  assert.deepEqual(r.enqueue(prepared), { kind: 'queued', queued: 0, duplicates: 2 });
  assert.equal(f.sent.length, 0); r.start(); await f.fire(); assert.equal(f.sent.length, 2);
  assert.ok(f.sent.every(delivery => !Object.hasOwn(delivery, 'admission')));
});

test('history-only, away-presence, forwarding-off and stale source events never encrypt', async t => {
  const f = fixture(t), r = f.runtime();
  const value = request(); delete value.phoneEligible;
  assert.equal((await r.prepare(value)).kind, 'suppressed');
  assert.equal((await r.prepare({ ...request(), sourceEpoch: 'old-session' })).kind, 'retired');
  f.snapshot().settings.mode = 'onlyWhenAway'; f.snapshot().settings.admission = 'suppressed_mac_active';
  assert.equal((await r.prepare(request())).kind, 'suppressed');
  f.snapshot().settings.forwardingEnabled = false;
  assert.equal((await r.prepare(request())).kind, 'suppressed');
  assert.equal(f.payloads.length, 0);
});

test('dismissals remain eligible while Mac is active and preserve all explicit IDs', async t => {
  const f = fixture(t), r = f.runtime(); f.snapshot().settings.mode = 'onlyWhenAway'; f.snapshot().settings.admission = 'suppressed_mac_active';
  const ids = Array.from({ length: 150 }, (_, i) => `${i}-${'x'.repeat(180)}`);
  const value = { ...request(), event: { ...event(), kind: 'dismiss', notificationIds: ids }, phoneEligible: false };
  const prepared = await r.prepare(value);
  assert.ok(prepared.jobs.length > 1); assert.deepEqual(f.payloads.flatMap(p => p.notificationIds), ids);
  assert.ok(prepared.jobs.every(j => j.admission.kind === 'dismiss'));
  r.enqueue(prepared); r.start(); await f.fire(); assert.ok(f.sent.length > 0);
});

test('privacy tightening during OAuth prevents any provider request', async t => {
  let enter, release, requests = 0;
  const entered = new Promise(resolve => { enter = resolve; });
  const waiting = new Promise(resolve => { release = resolve; });
  const sender = new FcmSender({ projectId: 'cmux-fixture', now: () => time, tokens: {
    get: async () => { enter(); await waiting; return 'fixture-oauth'; }, invalidate() {}
  }, fetch: async () => { requests++; return new Response('{}'); } });
  const f = fixture(t, { sender }), r = f.runtime(); r.enqueue(await r.prepare(request())); r.start();
  const pass = f.fire(); await entered; f.snapshot().settings.hideContent = true; release(); await pass;
  assert.equal(requests, 0); assert.deepEqual(f.outbox().status().counts, { done: 1 });
});

test('encrypted queue restart retains privacy metadata and rejects unredacted pending work', async t => {
  const f = fixture(t), first = f.runtime(); first.enqueue(await first.prepare(request())); await first.stop(); f.outbox().close();
  assert.equal(readFileSync(join(f.directory, 'outbox.sqlite')).includes(Buffer.from('session-one')), false);
  f.snapshot().settings.hideContent = true; f.open(); const next = f.runtime(); next.start(); await f.fire();
  assert.equal(f.sent.length, 0); assert.deepEqual(f.outbox().status().counts, { done: 1 });
});

test('temporary missing host evidence retains queued work; fresh evidence resumes without extending expiry', async t => {
  const f = fixture(t), r = f.runtime(), prepared = await r.prepare(request()); r.enqueue(prepared); f.setHost(null);
  r.start(); await f.fire(); assert.equal(f.sent.length, 0); assert.deepEqual(f.outbox().status().counts, { pending: 1 });
  f.setHost(host()); r.changed(); await f.fire(); assert.equal(f.sent.length, 0); // Original retry backoff still applies.
  await f.fire(); assert.equal(f.sent.length, 1);
  assert.equal(f.sent[0].expiresAt, prepared.jobs[0].delivery.expiresAt);
});

test('new host session and key rotation cancel old work; old metadata-free work is never inferred safe', async t => {
  for (const change of [h => { h.epoch = 'next-session'; }, h => { h.authority.publicKey = Buffer.alloc(32, 8).toString('base64'); },
      h => { h.authority.macInstallationID = 'new-helper'; }, h => { h.state = 'signed-out'; }]) {
    const f = fixture(t), r = f.runtime(); r.enqueue(await r.prepare(request())); change(f.snapshot()); r.start(); await f.fire();
    assert.equal(f.sent.length, 0); assert.deepEqual(f.outbox().status().counts, { done: 1 });
  }
  const f = fixture(t), r = f.runtime(), p = await r.prepare(request()); delete p.jobs[0].admission;
  f.outbox().enqueue(p.jobs[0]); r.start(); await f.fire(); assert.equal(f.sent.length, 0);
});

test('retirement during encryption prevents admission; disabled forwarding blocks later enqueue', async t => {
  let release; const wait = new Promise(resolve => { release = resolve; });
  const f = fixture(t, { encrypt: async args => { await wait; return seal(args); } }), r = f.runtime();
  const pending = r.prepare(request()); await new Promise(resolve => setImmediate(resolve));
  f.snapshot().epoch = 'replaced'; release(); assert.equal((await pending).kind, 'retired');
  f.snapshot().epoch = 'session-one'; const prepared = await r.prepare(request()); f.snapshot().settings.forwardingEnabled = false;
  assert.equal(r.enqueue(prepared).kind, 'retired'); assert.deepEqual(f.outbox().status().counts, {});
});

test('already admitted away alerts may retry after Mac becomes active, matching native queue semantics', async t => {
  const f = fixture(t), r = f.runtime(); f.snapshot().settings.mode = 'onlyWhenAway';
  r.enqueue(await r.prepare(request())); f.snapshot().settings.admission = 'suppressed_mac_active';
  r.start(); await f.fire(); assert.equal(f.sent.length, 1);
});

test('shutdown awaits encryption, rejects late preparation and never starts a provider', async t => {
  let release; const wait = new Promise(resolve => { release = resolve; });
  const f = fixture(t, { encrypt: async args => { await wait; return seal(args); } }), r = f.runtime();
  const first = r.prepare(request()), second = r.prepare(request());
  assert.equal((await r.prepare(request())).kind, 'busy');
  let stopped = false; const stop = r.stop().then(() => { stopped = true; });
  await new Promise(resolve => setImmediate(resolve)); assert.equal(stopped, false);
  assert.throws(() => r.start(), /stopping/); release(); await stop;
  assert.equal((await first).kind, 'stopped'); assert.equal((await second).kind, 'stopped');
  assert.equal(f.sent.length, 0); assert.equal((await r.prepare(request())).kind, 'stopped');
});

test('capacity failure leaves an entire fanout unqueued and allows the same sealed batch to be retained', async t => {
  const f = fixture(t, { capacity: 1 }), r = f.runtime(); f.enroll('second');
  const prepared = await r.prepare(request());
  assert.throws(() => r.enqueue(prepared), /full/); assert.deepEqual(f.outbox().status().counts, {});
  assert.equal(prepared.jobs.length, 2); assert.equal(f.sent.length, 0);
});

test('invalid/stale host snapshots are unavailable rather than authorization; unknown away state never admits a new alert', () => {
  for (const mutate of [h => { h.validUntil = time; }, h => { h.observedAt = time + 1; }, h => { h.validUntil = time + 30_001; },
      h => { h.settings.hideContent = 'true'; }, h => { h.authority.publicKey = 'bad'; }, h => { h.state = 'unknown'; }]) {
    const value = host(); mutate(value); assert.throws(() => readPushHost(() => value, () => time), PushHostUnavailable);
  }
  const h = host(); h.settings.mode = 'onlyWhenAway'; h.settings.admission = 'unknown';
  assert.throws(() => hostAdmitsEvent(h, 'notify'), PushHostUnavailable);
  assert.equal(hostAdmitsEvent(h, 'dismiss'), true);
  assert.equal(hostPermitsDelivery(host(), { recipient: {} }, null), false);
});


test('admission metadata is validated atomically and cannot be modified by a drain callback', async t => {
  const f = fixture(t), r = f.runtime(), prepared = await r.prepare(request());
  const malformed = structuredClone(prepared.jobs[0]); malformed.eventID = 'another-event'; malformed.admission.hideContent = 'true';
  assert.throws(() => f.outbox().enqueueBatch([prepared.jobs[0], malformed]), /invalid-input/);
  assert.deepEqual(f.outbox().status().counts, {});
  f.outbox().enqueue(prepared.jobs[0]);
  let seen;
  await f.outbox().drain({ sender: { send: async () => ({ kind: 'accepted' }) }, permits: (_binding, admission) => {
    seen = admission; assert.throws(() => { admission.hideContent = true; }, TypeError); return true;
  } });
  assert.equal(seen.hideContent, false); assert.equal(seen.hostEpoch, 'session-one');
});


test('stopping a split dismissal stops after the in-flight encryption, not every remaining part', async t => {
  let release, calls = 0; const wait = new Promise(resolve => { release = resolve; });
  const f = fixture(t, { encrypt: async args => { calls++; await wait; return seal(args); } }), r = f.runtime();
  const pending = r.prepare({ ...request(), event: { ...event(), kind: 'dismiss',
    notificationIds: Array.from({ length: 100 }, (_, i) => `${i}-${'x'.repeat(180)}`) } });
  await new Promise(resolve => setImmediate(resolve)); assert.equal(calls, 1);
  const stopped = r.stop(); release(); await stopped;
  assert.equal((await pending).kind, 'stopped'); assert.equal(calls, 1);
});
