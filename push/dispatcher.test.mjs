import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PushDispatcher } from './dispatcher.mjs';
import { PushRegistrations } from './registrations.mjs';
import { PushOutbox } from './outbox.mjs';
import { FcmSender } from './fcm.mjs';

function fixture(t, sender, policy = () => true) {
  const directory = mkdtempSync(join(tmpdir(), 'cmux-dispatcher-')), key = Buffer.alloc(32, 32);
  let now = 1_800_000_000_000, id = 0; const timers = new Map(), states = [];
  const registrations = new PushRegistrations({ directory, key });
  const outbox = new PushOutbox({ directory, key, now: () => now, random: () => 0 });
  const enrolled = registrations.replace({ token: 'fixture-token', publicKey: Buffer.alloc(32, 7).toString('base64'), recipient: {
    installationID: 'phone', keyID: 'key', senderKeyID: 'sender', tuple: { accountID: 'account', teamID: 'team',
      iosBuildID: 'android', iosInstallationID: 'phone', macDeviceID: 'mac', macInstanceTag: 'stable', macBuildID: 'build' }
  } });
  const createJob = (eventID = 'event') => ({ eventID, registration: enrolled.registration, delivery: { token: enrolled.token,
    recipient: enrolled.recipient, expiresAt: 1_800_000_900_000, envelope: { ...enrolled.recipient, version: 2,
      encapsulatedKey: Buffer.alloc(32, 1).toString('base64'), ciphertext: Buffer.alloc(128, 2).toString('base64') } } });
  const runtime = new PushDispatcher({ outbox, registrations, sender, ...(policy ? { policy } : {}), now: () => now,
    setTimer: (callback, delay) => { const key = ++id; timers.set(key, { callback, at: now + delay }); return key; },
    clearTimer: key => timers.delete(key), onState: value => states.push(value) });
  t.after(async () => { await runtime.stop(); outbox.close(); registrations.close(); rmSync(directory, { recursive: true, force: true }); });
  return { runtime, outbox, registrations, enrolled, createJob, states, timers, now: () => now,
    async fire() { const [key, timer] = [...timers].sort((a,b) => a[1].at-b[1].at)[0] ?? []; assert.ok(timer, 'No scheduled work');
      timers.delete(key); now = Math.max(now, timer.at); await timer.callback(); } };
}

test('explicit startup drains durable work, schedules receipt cleanup, and reaches idle without polling', async t => {
  let sends = 0; const f = fixture(t, { send: async () => { sends++; return { kind: 'accepted' }; } });
  f.runtime.enqueue(f.createJob()); assert.equal(f.timers.size, 0); assert.equal(sends, 0);
  f.runtime.start(); await f.fire(); assert.equal(sends, 1);
  assert.equal([...f.timers.values()][0].at, 1_800_000_900_000);
  await f.fire(); assert.deepEqual(f.outbox.status().counts, {}); assert.equal(f.timers.size, 0);
});

test('a later batch policy denial or temporary failure admits none of the earlier authorized jobs', t => {
  let calls = 0;
  const f = fixture(t, { send: () => assert.fail('not started') }, () => ++calls !== 2);
  assert.deepEqual(f.runtime.enqueueBatch([f.createJob('first'), f.createJob('second')]), { kind: 'retired' });
  assert.deepEqual(f.outbox.status().counts, {}); assert.equal(f.timers.size, 0);
  let attempts = 0;
  const g = fixture(t, { send: () => assert.fail('not started') }, () => {
    if (++attempts === 2) throw new Error('policy temporarily unavailable'); return true;
  });
  assert.throws(() => g.runtime.enqueueBatch([g.createJob('first'), g.createJob('second')]), /temporarily unavailable/);
  assert.deepEqual(g.outbox.status().counts, {}); assert.equal(g.timers.size, 0);
});

test('provider retry delay is honored and a later enqueue wakes a pending timer', async t => {
  let sends = 0; const f = fixture(t, { send: async () => ++sends === 1 ? { kind: 'unavailable', retryAfterMs: 60_000 } : { kind: 'accepted' } });
  f.runtime.enqueue(f.createJob()); f.runtime.start(); await f.fire();
  assert.equal([...f.timers.values()][0].at, f.now()+60_000);
  f.runtime.enqueue(f.createJob('new-event')); await f.fire(); assert.equal(sends, 2);
  assert.equal([...f.timers.values()][0].at, f.now()+60_000);
  await f.fire(); assert.equal(sends, 3);
});

test('stop during OAuth prevents a new request and keeps the original work for restart', async t => {
  let release; let entered;
  const started = new Promise(resolve => { entered = resolve; });
  let first = true, requests = 0; let now;
  const sender = new FcmSender({ projectId: 'cmux-fixture', now: () => now(), tokens: {
    get: async () => { if (first) { first = false; entered(); await new Promise(resolve => { release = resolve; }); } return 'oauth'; }, invalidate() {}
  }, fetch: async () => { requests++; return new Response(JSON.stringify({ name: 'projects/cmux-fixture/messages/one' })); } });
  const f = fixture(t, sender); now = f.now; f.runtime.enqueue(f.createJob()); f.runtime.start();
  const pass = f.fire(); await started; const stopped = f.runtime.stop();
  assert.throws(() => f.runtime.start(), /stopping/); release(); await stopped; await pass;
  assert.equal(requests, 0); assert.equal(f.outbox.status().counts.pending, 1); assert.equal(f.timers.size, 0);
  f.runtime.start(); await f.fire(); await f.fire(); assert.equal(requests, 1);
});

test('missing policy and revoked enrollment cannot send; diagnostics expose only coarse queue state', async t => {
  const denied = fixture(t, { send: () => assert.fail('default-denied send') }, null);
  assert.deepEqual(denied.runtime.enqueue(denied.createJob()), { kind: 'retired' });
  const f = fixture(t, { send: () => assert.fail('revoked send') }); f.runtime.enqueue(f.createJob());
  f.registrations.retire({ registration: f.enrolled.registration, token: f.enrolled.token, recipient: f.enrolled.recipient });
  f.runtime.start(); await f.fire(); assert.equal(f.outbox.status().counts.done, 1);
  assert.equal(JSON.stringify(f.states).includes('fixture-token'), false);
});

test('new work during an active bounded pass is not stranded by its scheduling decision', async t => {
  let f, sends = 0;
  f = fixture(t, { send: async () => { if (++sends === 1) f.runtime.enqueue(f.createJob('during-send')); return { kind: 'accepted' }; } });
  f.runtime.enqueue(f.createJob()); f.runtime.start(); await f.fire();
  assert.equal(sends, 2); await f.fire(); assert.equal(sends, 2);
});

test('parked credentials require explicit recovery, and policy failures retain the retry deadline', async t => {
  let policyAvailable = false, repaired = false;
  const f = fixture(t, { send: async () => ({ kind: repaired ? 'accepted' : 'credentials' }) }, () => {
    if (!policyAvailable) throw new Error('synthetic private diagnostics'); return true;
  });
  f.outbox.enqueue(f.createJob()); f.runtime.start(); await f.fire();
  assert.equal(f.outbox.status().counts.pending, 1); policyAvailable = true;
  await f.fire(); assert.equal(f.outbox.status().counts.blocked, 1);
  repaired = true; assert.equal(f.runtime.resumeBlocked('credentials'), 1); await f.fire();
  assert.equal(f.outbox.status().counts.done, 1); assert.equal(JSON.stringify(f.states).includes('private diagnostics'), false);
});
