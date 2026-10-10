import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { PushSourceDispatcher } from './source-dispatcher.mjs';
import { PushSourceJournal } from './source-journal.mjs';
import { PushForwarder } from './forwarder.mjs';
import { PushOutbox } from './outbox.mjs';
import { PushRegistrations } from './registrations.mjs';
import { FcmSender } from './fcm.mjs';

const time = 1_800_000_000_000;
const request = (id = 1) => ({ sourceEpoch: 'private-source-session', phoneEligible: true, expiresAt: time + 120_000,
  event: { kind: 'notify', correlationID: `00000000-0000-4000-8000-${id.toString().padStart(12, '0')}`,
    title: 'Private source title', subtitle: '', body: 'Private source body', badgeCount: 1,
    replyShape: 'none', retargetsToLiveSurfaceOwner: false } });

function fixture(t, { limit = 8, capacity = 128, sender: suppliedSender } = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'cmux-source-dispatcher-')), key = Buffer.alloc(32, 27);
  let now = time, nextID = 0, seals = 0, unavailable = false;
  const timers = new Map(), cancelled = [], states = [], deliveries = [];
  const nowFn = () => now;
  const schedule = stage => ({ now: nowFn,
    setTimer: (callback, delay) => { const id = ++nextID; timers.set(id, { stage, callback, at: now + delay }); return id; },
    clearTimer: id => { if (timers.has(id)) cancelled.push(timers.get(id)); timers.delete(id); } });
  let host = { state: 'ready', epoch: request().sourceEpoch,
    authority: { accountID: 'account', teamID: 'team', macDeviceID: 'mac', macInstanceTag: 'stable', macBuildID: 'build',
      senderKeyID: 'sender', macInstallationID: 'helper', publicKey: Buffer.alloc(32, 1).toString('base64') },
    settings: { forwardingEnabled: true, hideContent: false, mode: 'always', admission: 'allowed' } };
  const registrations = new PushRegistrations({ directory, key });
  function enroll() { return registrations.replace({ token: 'private-device-token', publicKey: Buffer.alloc(32, 2).toString('base64'),
    recipient: { installationID: 'phone', keyID: 'key', senderKeyID: 'sender', tuple: {
      accountID: 'account', teamID: 'team', macDeviceID: 'mac', macInstanceTag: 'stable', macBuildID: 'build',
      iosBuildID: 'android', iosInstallationID: 'phone' } } }); }
  const enrolled = enroll();
  const outbox = new PushOutbox({ directory, key, now: nowFn, random: () => 0 });
  const sender = suppliedSender ?? { send: async (delivery, { permits }) => {
    if (!permits()) return { kind: 'retired' };
    deliveries.push(structuredClone(delivery)); return { kind: 'accepted' };
  } };
  const forwarder = new PushForwarder({ registrations, outbox, sender, ...schedule('delivery'),
    readHost: () => {
      if (unavailable) throw new Error('Private host diagnostic');
      return { ...host, observedAt: now, validUntil: now + 30_000 };
    }, seal: ({ recipient, plaintext }) => {
      seals++; return { ...structuredClone(recipient), version: 2,
        encapsulatedKey: Buffer.alloc(32, seals).toString('base64'), ciphertext: Buffer.alloc(plaintext.length + 16, seals).toString('base64') };
    } });
  const journal = new PushSourceJournal({ directory, key, now: nowFn, capacity });
  const runtime = new PushSourceDispatcher({ journal, forwarder, ...schedule('source'), limit, onState: value => states.push(value) });
  t.after(async () => { await runtime.stop(); journal.close(); outbox.close(); registrations.close(); rmSync(directory, { recursive: true, force: true }); });
  return { runtime, journal, forwarder, registrations, outbox, enrolled, enroll, timers, cancelled, states, deliveries, directory, schedule,
    seals: () => seals, now: nowFn, clock: value => { now = value; }, host: () => host,
    setHost: value => { host = value; }, unavailable: value => { unavailable = value; },
    async fire(stage) {
      const [id, timer] = [...timers].filter(([, value]) => !stage || value.stage === stage)
        .sort((a, b) => a[1].at - b[1].at)[0] ?? [];
      assert.ok(timer, `No ${stage ?? ''} scheduled work`);
      timers.delete(id); now = Math.max(now, timer.at); await timer.callback();
    } };
}

test('durable capture stays passive until startup, then source and delivery complete and expire to idle', async t => {
  const f = fixture(t), value = request();
  assert.equal(f.runtime.capture(value).kind, 'captured'); value.event.body = 'later mutation';
  assert.equal(f.timers.size, 0); assert.equal(f.seals(), 0);
  f.runtime.start(); f.runtime.start();
  await f.fire('source'); assert.equal(f.seals(), 1); assert.deepEqual(f.journal.status().counts, { done: 1 });
  await f.fire('delivery'); assert.equal(f.deliveries.length, 1);
  assert.equal(f.runtime.capture(request()).kind, 'duplicate');
  await f.fire('source'); assert.equal(f.seals(), 1);
  await f.fire('source'); await f.fire('delivery');
  assert.deepEqual(f.journal.status().counts, {}); assert.deepEqual(f.outbox.status().counts, {}); assert.equal(f.timers.size, 0);
  const diagnostics = JSON.stringify(f.states);
  for (const secret of ['Private source', 'private-source-session', request().event.correlationID, 'private-device-token'])
    assert.equal(diagnostics.includes(secret), false);
});

test('startup recovers a journal reopened from disk without requiring another source event', async t => {
  const f = fixture(t); f.journal.accept(request()); await f.runtime.stop(); f.journal.close();
  const reopened = new PushSourceJournal({ directory: f.directory, key: Buffer.alloc(32, 27), now: f.now });
  const runtime = new PushSourceDispatcher({ journal: reopened, forwarder: f.forwarder, ...f.schedule('source') });
  try {
    runtime.start(); await f.fire('source'); await f.fire('delivery');
    assert.equal(f.seals(), 1); assert.equal(f.deliveries.length, 1); assert.deepEqual(reopened.status().counts, { done: 1 });
  } finally { await runtime.stop(); reopened.close(); }
});

test('bounded source passes continue immediately until all due fanout reaches delivery', async t => {
  const f = fixture(t, { limit: 3 });
  for (let i = 1; i <= 11; i++) f.runtime.capture(request(i));
  f.runtime.start(); await f.fire('source'); assert.equal(f.seals(), 3);
  assert.equal([...f.timers.values()].find(value => value.stage === 'source').at, f.now());
  for (let i = 0; i < 3; i++) await f.fire('source');
  assert.equal(f.seals(), 11); assert.deepEqual(f.journal.status().counts, { done: 11 });
  await f.fire('delivery'); await f.fire('delivery'); assert.equal(f.deliveries.length, 11);
});

test('missing enrollment schedules original-expiry retries and captures during a pass are not stranded', async t => {
  const f = fixture(t);
  f.registrations.retire({ registration: f.enrolled.registration, token: f.enrolled.token, recipient: f.enrolled.recipient });
  f.runtime.capture(request()); f.runtime.start(); await f.fire('source');
  assert.equal(f.seals(), 0); assert.deepEqual(f.journal.status().counts, { pending: 1 });
  assert.equal([...f.timers.values()].find(value => value.stage === 'source').at, time + 1000);
  f.enroll();
  const prepare = f.forwarder.prepare.bind(f.forwarder); let captured = false;
  f.forwarder.prepare = async value => {
    if (!captured) { captured = true; f.runtime.capture(request(2)); }
    return prepare(value);
  };
  await f.fire('source'); assert.equal(f.seals(), 2); await f.fire('delivery'); assert.equal(f.deliveries.length, 2);
});

test('temporary host loss retries, while signed-out retirement and history-only alerts finish without delivery', async t => {
  const f = fixture(t); f.unavailable(true); f.runtime.capture(request()); f.runtime.start(); await f.fire('source');
  assert.deepEqual(f.journal.status().counts, { pending: 1 }); assert.equal(f.seals(), 0);
  f.unavailable(false); f.setHost({ state: 'signed-out' }); f.runtime.changed(); await f.fire('source'); await f.fire('source');
  assert.deepEqual(f.journal.status().counts, { done: 1 }); assert.equal(f.seals(), 0);
  const g = fixture(t), value = request(); value.phoneEligible = false;
  g.runtime.capture(value); g.runtime.start(); await g.fire('source');
  assert.deepEqual(g.journal.status().counts, { done: 1 }); assert.equal(g.seals(), 0);
});

test('an uncertain queue acknowledgment recovers the saved batch through the scheduler without resealing', async t => {
  const f = fixture(t); const enqueue = f.forwarder.enqueue.bind(f.forwarder); let first = true;
  f.forwarder.enqueue = prepared => {
    const result = enqueue(prepared);
    if (first) { first = false; throw new Error('Private lost queue acknowledgment'); }
    return result;
  };
  f.runtime.capture(request()); f.runtime.start(); await f.fire('source');
  assert.deepEqual(f.journal.status().counts, { prepared: 1 }); assert.equal(f.seals(), 1);
  await f.fire('delivery'); assert.equal(f.deliveries.length, 1);
  await f.runtime.stop(); f.runtime.start(); await f.fire('source'); await f.fire('source'); await f.fire('delivery');
  assert.equal(f.seals(), 1); assert.equal(f.deliveries.length, 1); assert.deepEqual(f.journal.status().counts, { done: 1 });
  assert.equal(JSON.stringify(f.states).includes('Private lost'), false);
});

test('stop waits for preparation, fences provider work and avoids claiming the rest of the source batch', async t => {
  const f = fixture(t); let enter, release;
  const entered = new Promise(resolve => { enter = resolve; });
  const held = new Promise(resolve => { release = resolve; });
  const prepare = f.forwarder.prepare.bind(f.forwarder);
  f.forwarder.prepare = async value => { enter(); await held; return prepare(value); };
  f.runtime.capture(request()); f.runtime.capture(request(2)); f.runtime.start();
  const pass = f.fire('source'); await entered; f.runtime.start();
  let stopped = false; const stop = f.runtime.stop().then(() => { stopped = true; });
  assert.throws(() => f.runtime.start(), /stopping/); await Promise.resolve(); assert.equal(stopped, false);
  release(); await stop; await pass;
  assert.equal(f.timers.size, 0); assert.equal(f.seals(), 0); assert.equal(f.deliveries.length, 0);
  assert.deepEqual(f.journal.status().counts, { pending: 2 });
  f.forwarder.prepare = prepare; f.runtime.start(); await f.fire('source'); await f.fire('source'); await f.fire('delivery');
  assert.equal(f.seals(), 2); assert.equal(f.deliveries.length, 2);
});

test('cancelled callbacks cannot consume a replacement timer across a stop/start or capture wake', async t => {
  const f = fixture(t); f.runtime.capture(request()); f.runtime.start();
  const old = [...f.timers.values()].find(value => value.stage === 'source');
  await f.runtime.stop(); f.runtime.start();
  const replacement = [...f.timers.values()].find(value => value.stage === 'source');
  await old.callback(); assert.ok([...f.timers.values()].includes(replacement)); assert.equal(f.seals(), 0);
  f.runtime.capture(request(2)); const newest = [...f.timers.values()].find(value => value.stage === 'source');
  await replacement.callback(); assert.ok([...f.timers.values()].includes(newest));
  await f.fire('source'); assert.equal(f.seals(), 2);
});

test('stop awaits an active OAuth exchange and restart sends the same queued ciphertext once', async t => {
  let f, enter, release, first = true, requests = 0;
  const entered = new Promise(resolve => { enter = resolve; });
  const held = new Promise(resolve => { release = resolve; });
  const sender = new FcmSender({ projectId: 'cmux-fixture', now: () => f.now(), tokens: {
    get: async () => { if (first) { first = false; enter(); await held; } return 'fixture-oauth-token'; }, invalidate() {}
  }, fetch: async () => { requests++; return new Response(JSON.stringify({ name: 'projects/cmux-fixture/messages/one' })); } });
  f = fixture(t, { sender }); f.runtime.capture(request()); f.runtime.start(); await f.fire('source');
  const pass = f.fire('delivery'); await entered;
  let stopped = false; const stop = f.runtime.stop().then(() => { stopped = true; });
  await Promise.resolve(); assert.equal(stopped, false); release(); await stop; await pass;
  assert.equal(requests, 0); assert.equal(f.outbox.status().counts.pending, 1);
  f.runtime.start(); await f.fire('delivery'); await f.fire('delivery');
  assert.equal(requests, 1); assert.equal(f.seals(), 1); assert.equal(f.outbox.status().counts.done, 1);
});

test('storage failures use bounded retries and diagnostic exceptions never stop recovery', async t => {
  const f = fixture(t); const drain = f.journal.drain.bind(f.journal); let failed = true;
  f.journal.drain = args => { if (failed) throw new Error('Private disk diagnostic'); return drain(args); };
  f.runtime.capture(request()); f.runtime.start(); await f.fire('source');
  assert.equal([...f.timers.values()].find(value => value.stage === 'source').at, time + 5000);
  assert.equal(JSON.stringify(f.states).includes('Private disk'), false);
  failed = false; await f.fire('source'); await f.fire('delivery'); assert.equal(f.deliveries.length, 1);
  let observations = 0;
  const runtime = new PushSourceDispatcher({ journal: f.journal, forwarder: f.forwarder, ...f.schedule('source'),
    onState: () => { observations++; throw new Error('diagnostic observer'); } });
  await f.runtime.stop();
  try {
    runtime.capture(request(2)); runtime.start(); await f.fire('source'); await f.fire('delivery');
    assert.ok(observations >= 2); assert.equal(f.deliveries.length, 2);
  } finally { await runtime.stop(); }
});

test('privacy and account changes wake the retained provider queue and revoke previously admitted plaintext', async t => {
  const f = fixture(t); f.runtime.capture(request()); f.runtime.start(); await f.fire('source');
  f.host().settings.hideContent = true; f.runtime.changed(); await f.fire('delivery');
  assert.equal(f.deliveries.length, 0); assert.deepEqual(f.outbox.status().counts, { done: 1 });
  f.runtime.capture(request(2)); await f.fire('source');
  f.host().epoch = 'replacement-session'; f.runtime.changed(); await f.fire('delivery');
  assert.equal(f.deliveries.length, 0); assert.equal(f.outbox.status().counts.done, 2);
});

test('expired source entries prune on startup, and full capture rejects without eviction or false acknowledgment', async t => {
  const f = fixture(t, { capacity: 1 }); f.runtime.capture(request());
  assert.throws(() => f.runtime.capture(request(2)), /full/); assert.deepEqual(f.journal.status().counts, { pending: 1 });
  f.clock(time + 120_000); f.runtime.start(); await f.fire('source'); await f.fire('delivery');
  assert.equal(f.seals(), 0); assert.equal(f.timers.size, 0); assert.deepEqual(f.journal.status().counts, {});
  assert.deepEqual(f.runtime.capture(request(2)), { kind: 'expired' });
});

test('credential recovery delegates to the delivery queue without creating another source event', async t => {
  let repaired = false, calls = 0;
  const f = fixture(t, { sender: { send: async (_, { permits }) => {
    assert.equal(permits(), true); calls++; return { kind: repaired ? 'accepted' : 'credentials' };
  } } });
  f.runtime.capture(request()); f.runtime.start(); await f.fire('source'); await f.fire('delivery');
  assert.equal(f.outbox.status().counts.blocked, 1); repaired = true;
  assert.equal(f.runtime.resumeBlocked('credentials'), 1); await f.fire('delivery');
  assert.equal(calls, 2); assert.equal(f.outbox.status().counts.done, 1); assert.equal(f.seals(), 1);
});
