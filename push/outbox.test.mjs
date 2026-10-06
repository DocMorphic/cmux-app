import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fork } from 'node:child_process';
import { once } from 'node:events';
import { DatabaseSync } from 'node:sqlite';
import { PushOutbox } from './outbox.mjs';
import { FcmSender } from './fcm.mjs';

const epoch = 1_800_000_000_000;
const key = Buffer.alloc(32, 17); // Synthetic test key; never a provisioned sender key.
function job(eventID = 'event-one') {
  const recipient = { installationID: 'private-phone', keyID: 'recipient-key', senderKeyID: 'sender-key', tuple: {
    accountID: 'private-account', teamID: 'private-team', iosBuildID: 'android-build', iosInstallationID: 'private-phone',
    macDeviceID: 'private-mac', macInstanceTag: 'stable', macBuildID: 'mac-build'
  } };
  return { eventID, registration: { id: 'private-registration', generation: 'generation-one' }, delivery: {
    token: 'private-device-token', recipient, expiresAt: epoch + 900_000,
    envelope: { ...structuredClone(recipient), version: 2, encapsulatedKey: Buffer.alloc(32, 1).toString('base64'),
      ciphertext: Buffer.alloc(128, 2).toString('base64') }
  } };
}
function fixture(t, options = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'cmux-outbox-'));
  let now = epoch; const queues = [];
  const open = () => { const q = new PushOutbox({ directory, key, now: () => now, random: () => 0, ...options }); queues.push(q); return q; };
  t.after(() => { queues.forEach(q => q.close()); rmSync(directory, { recursive: true, force: true }); });
  return { directory, open, advance: ms => { now += ms; }, time: () => now };
}
const permitted = { permits: () => true };
const accept = { send: async () => ({ kind: 'accepted' }) };

test('batch admission rolls back earlier inserts on capacity or late identity conflict', t => {
  const f = fixture(t, { capacity: 2 }); const q = f.open(); q.enqueue(job('existing'));
  assert.throws(() => q.enqueueBatch([job('first'), job('second')]), /full/);
  assert.deepEqual(q.status().counts, { pending: 1 });
  const conflict = job('existing'); conflict.delivery.token = 'different';
  assert.throws(() => q.enqueueBatch([job('first'), conflict]), /identity-conflict/);
  assert.deepEqual(q.status().counts, { pending: 1 });
  assert.deepEqual(q.enqueue(job('first')), { kind: 'queued' });
});

test('batch validation and expiration cannot leave a valid prefix in storage', t => {
  const q = fixture(t).open(); const expired = job('expired'); expired.delivery.expiresAt = epoch;
  assert.deepEqual(q.enqueueBatch([job('valid'), expired]), { kind: 'expired' });
  assert.deepEqual(q.status().counts, {});
  const invalid = job('invalid'); invalid.delivery.envelope.keyID = 'wrong';
  assert.throws(() => q.enqueueBatch([job('valid'), invalid]), /Invalid push delivery/);
  assert.deepEqual(q.status().counts, {});
});

test('a later SQLite insert failure rolls back a whole batch and preserves existing jobs', t => {
  const f = fixture(t); const q = f.open(); q.enqueue(job('existing'));
  const db = new DatabaseSync(join(f.directory, 'outbox.sqlite'));
  db.exec(`CREATE TRIGGER fail_late BEFORE INSERT ON jobs WHEN (SELECT COUNT(*) FROM jobs) >= 2
    BEGIN SELECT RAISE(ABORT, 'fixture storage failure'); END;`);
  try {
    assert.throws(() => q.enqueueBatch([job('first'), job('second')]), { message: 'Push outbox storage-unavailable' });
    assert.deepEqual(q.status().counts, { pending: 1 });
  } finally { db.exec('DROP TRIGGER fail_late'); db.close(); }
  assert.deepEqual(q.enqueueBatch([job('first'), job('second')]), { kind: 'queued', queued: 2, duplicates: 0 });
});

test('committed batch survives reopen with exact ciphertext and accepts retained partial-duplicate retries', async t => {
  const f = fixture(t); let q = f.open(); const jobs = [job('first'), job('second'), job('third')];
  q.enqueue(jobs[0]);
  assert.deepEqual(q.enqueueBatch(jobs), { kind: 'queued', queued: 2, duplicates: 1 });
  q.close(); q = f.open();
  assert.deepEqual(q.enqueueBatch(jobs), { kind: 'queued', queued: 0, duplicates: 3 });
  const observed = [];
  const results = await q.drain({ ...permitted, sender: { send: async value => { observed.push(value); return { kind: 'accepted' }; } } });
  assert.equal(results.length, 3); assert.ok(results.every(value => value.kind === 'accepted'));
  // Delivery content is identical here; IDs are bound inside their encrypted queue records.
  assert.deepEqual(observed, jobs.map(value => value.delivery));
  assert.deepEqual(q.enqueueBatch(jobs), { kind: 'queued', queued: 0, duplicates: 3 });
});

test('encrypted records survive reopen, retain exact ciphertext/expiry and deduplicate completed delivery', async t => {
  const f = fixture(t); let q = f.open(); const value = job();
  assert.deepEqual(q.enqueue(value), { kind: 'queued' });
  value.delivery.token = 'mutated-after-enqueue';
  assert.deepEqual(q.enqueue(job()), { kind: 'duplicate' });
  for (const name of readdirSync(f.directory)) {
    const bytes = readFileSync(join(f.directory, name));
    for (const secret of ['private-device-token', 'private-account', 'private-phone', 'event-one'])
      assert.equal(bytes.includes(Buffer.from(secret)), false, `${name} exposed ${secret}`);
  }
  q.close(); q = f.open();
  assert.deepEqual(await q.drain({ ...permitted, sender: { send: async delivery => {
    assert.deepEqual(delivery, job().delivery); return { kind: 'accepted' };
  } } }), [{ kind: 'accepted' }]);
  assert.deepEqual(q.enqueue(job()), { kind: 'duplicate' });
  assert.deepEqual(await q.drain({ ...permitted, sender: accept }), []);
  assert.deepEqual(q.status(), { counts: { done: 1 }, nextDueAt: epoch + 900_000 });
});

test('duplicate identity cannot replace ciphertext, token, recipient or expiry; bounds never evict fresh jobs', t => {
  const f = fixture(t, { capacity: 1 }); const q = f.open(); q.enqueue(job());
  for (const mutate of [v => v.delivery.token = 'rotated-token', v => v.delivery.expiresAt--,
    v => v.delivery.envelope.ciphertext = Buffer.alloc(128, 3).toString('base64')]) {
    const value = job(); mutate(value); assert.throws(() => q.enqueue(value), /identity-conflict/);
  }
  assert.throws(() => q.enqueue(job('second')), /full/);
  assert.equal(q.status().counts.pending, 1);
  f.advance(900_000); assert.deepEqual(q.status(), { counts: {}, nextDueAt: null });
  assert.deepEqual(q.enqueue(job()), { kind: 'expired' });
});

test('wrong key and authenticated-record corruption fail closed without destroying queued work', async t => {
  const f = fixture(t); let q = f.open(); q.enqueue(job()); q.close();
  assert.throws(() => new PushOutbox({ directory: f.directory, key: Buffer.alloc(32, 18) }), /key-mismatch/);
  q = f.open(); assert.equal(q.status().counts.pending, 1); q.close();
  const db = new DatabaseSync(join(f.directory, 'outbox.sqlite'));
  db.prepare('UPDATE jobs SET payload=?').run(Buffer.alloc(64)); db.close();
  q = f.open(); let sends = 0;
  await assert.rejects(q.drain({ ...permitted, sender: { send: async () => { sends++; } } }), /corrupt-record/);
  assert.equal(sends, 0); assert.equal(q.status().counts.pending, 1);
});

test('provider retry minimum persists over restart and never extends the authenticated event lifetime', async t => {
  const f = fixture(t); let q = f.open(); q.enqueue(job()); let sends = 0;
  const sender = { send: async value => { sends++; assert.deepEqual(value, job().delivery);
    return { kind: 'unavailable', retryAfterMs: 60_000, ambiguous: true }; } };
  assert.deepEqual(await q.drain({ ...permitted, sender }), [{ kind: 'unavailable', retryAt: epoch + 60_000 }]);
  q.close(); q = f.open(); f.advance(59_999);
  assert.deepEqual(await q.drain({ ...permitted, sender }), []);
  f.advance(1); await q.drain({ ...permitted, sender }); assert.equal(sends, 2);
  f.advance(840_000); assert.deepEqual(await q.drain({ ...permitted, sender }), []);
  assert.deepEqual(q.status().counts, {});
});

test('exponential backoff with jitter retains its attempt number across restart', async t => {
  const f = fixture(t, { random: () => 0.5 }); let q = f.open(); q.enqueue(job());
  const sender = { send: async () => ({ kind: 'unavailable' }) };
  assert.deepEqual(await q.drain({ ...permitted, sender }), [{ kind: 'unavailable', retryAt: epoch + 1500 }]);
  q.close(); q = f.open(); f.advance(1500);
  assert.deepEqual(await q.drain({ ...permitted, sender }), [{ kind: 'unavailable', retryAt: epoch + 4500 }]);
});

test('default denial, current generation and revocation during OAuth prevent provider sends', async t => {
  const f = fixture(t); const q = f.open(); q.enqueue(job());
  assert.deepEqual(await q.drain({ sender: { send: () => assert.fail('default denial sent') } }), [{ kind: 'retired' }]);
  q.enqueue(job('next')); let generation = 'generation-one';
  const sender = new FcmSender({ projectId: 'cmux-fixture', now: f.time,
    tokens: { get: async () => { generation = 'generation-two'; return 'oauth'; }, invalidate() {} },
    fetch: () => assert.fail('revoked generation sent') });
  assert.deepEqual(await q.drain({ sender, permits: binding => binding.registration.generation === generation }), [{ kind: 'retired' }]);
  q.enqueue(job('async-predicate'));
  assert.deepEqual(await q.drain({ sender: accept, permits: async () => true }), [{ kind: 'retired' }]);
});

test('credentials and rejection park durably until explicit repair, without retiring a registration', async t => {
  const f = fixture(t); let q = f.open(); q.enqueue(job()); let retires = 0;
  const retire = async () => { retires++; return 'retired'; };
  assert.deepEqual(await q.drain({ ...permitted, retire, sender: { send: async () => ({ kind: 'credentials' }) } }), [{ kind: 'credentials' }]);
  q.close(); q = f.open();
  assert.deepEqual(await q.drain({ ...permitted, retire, sender: accept }), []);
  assert.equal(q.resumeBlocked('rejected'), 0); assert.equal(q.resumeBlocked('credentials'), 1);
  assert.deepEqual(await q.drain({ ...permitted, retire, sender: { send: async () => ({ kind: 'rejected' }) } }), [{ kind: 'rejected' }]);
  assert.equal(retires, 0); assert.equal(q.resumeBlocked('rejected'), 1);
  assert.deepEqual(await q.drain({ ...permitted, sender: accept }), [{ kind: 'accepted' }]);
});

test('blocked and completed rows schedule expiry cleanup; subsecond remaining lifetime is not sent', async t => {
  const f = fixture(t); const q = f.open(); q.enqueue(job());
  await q.drain({ ...permitted, sender: { send: async () => ({ kind: 'credentials' }) } });
  assert.equal(q.status().nextDueAt, epoch + 900_000);
  f.advance(899_001); q.resumeBlocked('credentials');
  assert.deepEqual(await q.drain({ ...permitted, sender: { send: () => assert.fail('subsecond event sent') } }), [{ kind: 'expired' }]);
  f.advance(999); assert.deepEqual(q.status(), { counts: {}, nextDueAt: null });
});

test('unregistered persists retirement intent; retry compares original token/generation and preserves its replacement', async t => {
  const f = fixture(t); let q = f.open(); q.enqueue(job()); let sends = 0;
  const sender = { send: async () => { sends++; return { kind: 'unregistered' }; } };
  assert.deepEqual(await q.drain({ ...permitted, sender, retire: async () => { throw new Error('private account details'); } }),
    [{ kind: 'unavailable', retryAt: epoch + 1000 }]);
  assert.equal(q.status().counts.retiring, 1); q.close(); q = f.open(); f.advance(1000);
  const current = { token: 'replacement-token', generation: 'generation-two' };
  const retire = async binding => {
    assert.equal(binding.token, job().delivery.token); assert.deepEqual(binding.registration, job().registration);
    assert.ok(Object.isFrozen(binding.recipient.tuple));
    if (binding.token === current.token && binding.registration.generation === current.generation) {
      current.token = null; return 'retired';
    }
    return 'superseded';
  };
  assert.deepEqual(await q.drain({ ...permitted, sender, retire }), [{ kind: 'unregistered' }]);
  assert.equal(sends, 1); assert.equal(current.token, 'replacement-token');
});

test('concurrent instances claim once; expired leases recover and stale callbacks cannot consume reclaimed work', async t => {
  const f = fixture(t); const a = f.open(), b = f.open(); a.enqueue(job());
  let release; let entered;
  const started = new Promise(resolve => { entered = resolve; });
  const pending = a.drain({ ...permitted, sender: { send: async (_, options) => {
    entered(); await new Promise(resolve => { release = resolve; });
    assert.equal(options.permits(), false); return { kind: 'accepted' };
  } } });
  await started;
  assert.throws(() => a.close(), /busy/);
  assert.deepEqual(await b.drain({ ...permitted, sender: accept }), []);
  f.advance(60_000);
  assert.deepEqual(await b.drain({ ...permitted, sender: { send: async () => ({ kind: 'unavailable', retryAfterMs: 20_000 }) } }),
    [{ kind: 'unavailable', retryAt: epoch + 80_000 }]);
  release(); assert.deepEqual(await pending, [{ kind: 'lease-lost' }]);
  assert.equal(b.status().counts.pending, 1);
  f.advance(20_000); assert.deepEqual(await b.drain({ ...permitted, sender: accept }), [{ kind: 'accepted' }]);
});

// Real subprocess termination verifies committed claims survive an ungraceful restart.
test('SIGKILL during send recovers original encrypted work after its persisted lease, not before', async t => {
  const f = fixture(t); const q = f.open(); q.enqueue(job());
  const child = fork(new URL('./test-support/outbox-worker.mjs', import.meta.url), [f.directory, String(epoch), 'hang'],
    { stdio: ['ignore', 'ignore', 'pipe', 'ipc'] });
  t.after(() => { if (child.exitCode === null) child.kill('SIGKILL'); });
  const [message] = await once(child, 'message'); assert.equal(message, 'sending');
  child.kill('SIGKILL'); await once(child, 'exit');
  assert.deepEqual(await q.drain({ ...permitted, sender: accept }), []);
  f.advance(60_000);
  assert.deepEqual(await q.drain({ ...permitted, sender: { send: async value => {
    assert.deepEqual(value, job().delivery); return { kind: 'accepted' };
  } } }), [{ kind: 'accepted' }]);
});

test('separate processes drain one shared database without duplicate claims', async t => {
  const f = fixture(t); const q = f.open(); for (let i = 0; i < 20; i++) q.enqueue(job(`event-${i}`));
  const counts = await Promise.all([1, 2].map(async () => {
    const child = fork(new URL('./test-support/outbox-worker.mjs', import.meta.url), [f.directory, String(epoch), 'drain'],
      { stdio: ['ignore', 'ignore', 'pipe', 'ipc'] });
    t.after(() => { if (child.exitCode === null) child.kill('SIGKILL'); });
    const exit = once(child, 'exit'); const [message] = await once(child, 'message');
    const [code] = await exit; assert.equal(code, 0); return message;
  }));
  assert.equal(counts.reduce((a, b) => a + b, 0), 20); assert.equal(q.status().counts.done, 20);
});
