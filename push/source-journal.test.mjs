import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import { PushSourceJournal } from './source-journal.mjs';
import { PushForwarder } from './forwarder.mjs';
import { PushOutbox } from './outbox.mjs';
import { PushRegistrations } from './registrations.mjs';

const time = 1_800_000_000_000, key = Buffer.alloc(32, 21);
function request() {
  return { sourceEpoch: 'source-private-session', phoneEligible: true, expiresAt: time + 120_000,
    event: { kind: 'notify', correlationID: '00000000-0000-4000-8000-000000000001', title: 'Source private title',
      subtitle: '', body: 'Source private body', badgeCount: 1, replyShape: 'none', retargetsToLiveSurfaceOwner: false } };
}
function fixture(t, options = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'cmux-source-journal-')); let now = time;
  const journals = [];
  function open() { const j = new PushSourceJournal({ directory, key, now: () => now, ...options }); journals.push(j); return j; }
  t.after(() => { journals.forEach(j => j.close()); rmSync(directory, { recursive: true, force: true }); });
  return { directory, open, now: () => now, clock: value => { now = value; } };
}
function pipeline(t, f) {
  let host = { state: 'ready', epoch: request().sourceEpoch, observedAt: time, validUntil: time + 30_000,
    authority: { accountID: 'account', teamID: 'team', macDeviceID: 'mac', macInstanceTag: 'stable', macBuildID: 'build',
      senderKeyID: 'sender', macInstallationID: 'helper-install', publicKey: Buffer.alloc(32, 1).toString('base64') },
    settings: { forwardingEnabled: true, hideContent: false, mode: 'always', admission: 'allowed' } };
  const registrations = new PushRegistrations({ directory: f.directory, key });
  registrations.replace({ token: 'private-token', publicKey: Buffer.alloc(32, 2).toString('base64'),
    recipient: { installationID: 'phone', keyID: 'phone-key', senderKeyID: 'sender', tuple: {
      accountID: 'account', teamID: 'team', macDeviceID: 'mac', macInstanceTag: 'stable', macBuildID: 'build',
      iosBuildID: 'android', iosInstallationID: 'phone' } } });
  const outbox = new PushOutbox({ directory: f.directory, key, now: f.now }); let seals = 0;
  const forwarder = new PushForwarder({ registrations, outbox, sender: { send: () => assert.fail('not started') }, readHost: () => host, now: f.now,
    seal: ({ recipient, plaintext }) => { seals++; return { ...structuredClone(recipient), version: 2,
      encapsulatedKey: Buffer.alloc(32, seals).toString('base64'), ciphertext: Buffer.alloc(plaintext.length + 16, seals).toString('base64') }; } });
  t.after(async () => { await forwarder.stop(); outbox.close(); registrations.close(); });
  return { forwarder, outbox, seals: () => seals, setHost: value => { host = value; } };
}

test('accepted source survives reopening, is encrypted on disk, and finishes through the real pipeline', async t => {
  const f = fixture(t), p = pipeline(t, f), j = f.open(), input = request();
  const captured = j.accept(input); assert.equal(captured.kind, 'captured');
  input.event.body = 'later mutation'; j.close();
  const restored = f.open();
  for (const file of readdirSync(f.directory)) if (file.startsWith('source.sqlite')) {
    const bytes = readFileSync(join(f.directory, file));
    for (const secret of ['Source private body', 'Source private title', 'source-private-session']) assert.equal(bytes.includes(Buffer.from(secret)), false);
  }
  assert.deepEqual(await restored.drain({ forwarder: p.forwarder }), [{ kind: 'queued', id: captured.id }]);
  assert.equal(p.seals(), 1); assert.deepEqual(p.outbox.status().counts, { pending: 1 });
  assert.deepEqual(restored.accept(request()), { kind: 'duplicate', id: captured.id, outcome: 'queued' });
});

test('cancelling a pass retains prepared ciphertext and leaves the next source record unclaimed', async t => {
  const f = fixture(t), j = f.open();
  j.accept(request());
  const next = request(); next.event.correlationID = '00000000-0000-4000-8000-000000000002'; j.accept(next);
  let active = true, preparations = 0;
  const outcomes = await j.drain({ shouldContinue: () => active, forwarder: {
    prepare: async value => { preparations++; active = false; return { kind: 'prepared', jobs: [{ event: value.event.correlationID }] }; },
    enqueue: () => assert.fail('cancelled pass must not enqueue')
  } });
  assert.equal(outcomes[0].kind, 'unavailable'); assert.equal(preparations, 1);
  assert.deepEqual(j.status().counts, { pending: 1, prepared: 1 });
  f.clock(time + 1000); let admissions = 0;
  await j.drain({ forwarder: {
    prepare: async value => { preparations++; return { kind: 'prepared', jobs: [{ event: value.event.correlationID }] }; },
    enqueue: prepared => { assert.ok(prepared.jobs[0].event); admissions++; return { kind: 'queued' }; }
  } });
  assert.equal(preparations, 2); assert.equal(admissions, 2); assert.deepEqual(j.status().counts, { done: 2 });
});

test('equally due source captures replay in durable arrival order across bounded passes and reopen', async t => {
  const f = fixture(t), j = f.open(), inputs = [request(), request(), request()];
  inputs.forEach((value, index) => { value.event.correlationID = `00000000-0000-4000-8000-00000000000${index + 1}`; j.accept(value); });
  j.close(); const restored = f.open(), observed = [];
  const forwarder = {
    prepare: async value => { observed.push(value.event.correlationID); return { kind: 'prepared', jobs: [] }; },
    enqueue: () => ({ kind: 'queued' })
  };
  await restored.drain({ forwarder, limit: 1 }); await restored.drain({ forwarder, limit: 2 });
  assert.deepEqual(observed, inputs.map(value => value.event.correlationID));
});

test('legacy source schema migrates insertion order without changing encrypted requests or deduplication', async t => {
  const f = fixture(t), j = f.open(), values = [request(), request(), request()];
  values.forEach((value, index) => { value.event.correlationID = `00000000-0000-4000-8000-00000000000${index + 1}`; j.accept(value); });
  j.close(); const db = new DatabaseSync(join(f.directory, 'source.sqlite'));
  const before = db.prepare('SELECT id,fingerprint,payload,expires FROM source ORDER BY rowid').all();
  db.exec('DROP INDEX source_capture_order; ALTER TABLE source DROP COLUMN capture_order'); db.close();
  const migrated = f.open(), inspect = new DatabaseSync(join(f.directory, 'source.sqlite'));
  assert.deepEqual(inspect.prepare('SELECT id,fingerprint,payload,expires FROM source ORDER BY capture_order').all(), before);
  inspect.close(); assert.equal(migrated.accept(values[0]).kind, 'duplicate');
  const observed = [];
  await migrated.drain({ forwarder: {
    prepare: async value => { observed.push(value.event.correlationID); return { kind: 'prepared', jobs: [] }; },
    enqueue: () => ({ kind: 'queued' })
  } });
  assert.deepEqual(observed, values.map(value => value.event.correlationID));
});

test('queue commit with a lost result recovers identical ciphertext after restart without resealing', async t => {
  const f = fixture(t), p = pipeline(t, f), j = f.open(); j.accept(request());
  const uncertain = { prepare: value => p.forwarder.prepare(value), enqueue: value => {
    p.forwarder.enqueue(value); throw new Error('lost local queue acknowledgment');
  } };
  const first = await j.drain({ forwarder: uncertain }); assert.equal(first[0].kind, 'unavailable');
  assert.deepEqual(j.status().counts, { prepared: 1 }); assert.equal(p.seals(), 1); j.close();
  f.clock(first[0].retryAt); const restored = f.open();
  assert.equal((await restored.drain({ forwarder: p.forwarder }))[0].kind, 'queued');
  assert.equal(p.seals(), 1); assert.deepEqual(p.outbox.status().counts, { pending: 1 });
});

test('a preparation persistence failure cannot admit ciphertext to the delivery queue', async t => {
  const f = fixture(t), p = pipeline(t, f), j = f.open(); j.accept(request());
  const db = new DatabaseSync(join(f.directory, 'source.sqlite'));
  db.exec("CREATE TRIGGER fail_prepare BEFORE UPDATE OF state ON source WHEN NEW.state='prepared' BEGIN SELECT RAISE(ABORT,'fixture'); END;");
  const result = await j.drain({ forwarder: p.forwarder });
  assert.equal(result[0].kind, 'unavailable'); assert.deepEqual(p.outbox.status().counts, {});
  assert.deepEqual(j.status().counts, { pending: 1 });
  db.exec('DROP TRIGGER fail_prepare'); db.close(); f.clock(result[0].retryAt);
  assert.equal((await j.drain({ forwarder: p.forwarder }))[0].kind, 'queued');
});

test('failure after delivery admission replays the persisted batch and repairs completion', async t => {
  const f = fixture(t), p = pipeline(t, f), j = f.open(); j.accept(request());
  const db = new DatabaseSync(join(f.directory, 'source.sqlite'));
  db.exec("CREATE TRIGGER fail_done BEFORE UPDATE OF state ON source WHEN NEW.state='done' BEGIN SELECT RAISE(ABORT,'fixture'); END;");
  const result = await j.drain({ forwarder: p.forwarder });
  assert.equal(result[0].kind, 'unavailable'); assert.deepEqual(p.outbox.status().counts, { pending: 1 });
  assert.equal(p.seals(), 1); db.exec('DROP TRIGGER fail_done'); db.close(); f.clock(result[0].retryAt);
  assert.equal((await j.drain({ forwarder: p.forwarder }))[0].kind, 'queued'); assert.equal(p.seals(), 1);
});

test('source ID reuse with changed payload/expiry is rejected and capacity never evicts fresh records', t => {
  const f = fixture(t, { capacity: 1 }), j = f.open(); j.accept(request());
  for (const mutate of [r => { r.event.body = 'different'; }, r => { r.expiresAt += 1000; }, r => { r.phoneEligible = false; }]) {
    const value = request(); mutate(value); assert.throws(() => j.accept(value), /identity-conflict/);
  }
  const next = request(); next.event.correlationID = '00000000-0000-4000-8000-000000000002';
  assert.throws(() => j.accept(next), /full/); assert.deepEqual(j.status().counts, { pending: 1 });
  f.clock(time + 120_000); assert.deepEqual(j.status().counts, {}); assert.equal(j.accept(next).kind, 'expired');
});

test('source unavailable and no recipients retain original deadline; explicit suppression completes capture', async t => {
  const f = fixture(t), j = f.open(); j.accept(request()); let reason = 'no-recipients';
  const forwarder = { prepare: async () => ({ kind: reason }), enqueue: () => assert.fail('not prepared') };
  const retry = (await j.drain({ forwarder }))[0]; assert.equal(retry.kind, 'unavailable');
  f.clock(retry.retryAt); reason = 'suppressed'; assert.equal((await j.drain({ forwarder }))[0].kind, 'suppressed');
  assert.deepEqual(j.status().counts, { done: 1 }); assert.equal(j.status().nextDueAt, time + 120_000);
});

test('another process owner may reclaim an expired preparation lease; late encryption cannot overwrite it', async t => {
  const f = fixture(t), j = f.open(), other = f.open(); j.accept(request());
  let release, started; const waiting = new Promise(resolve => { release = resolve; });
  const entered = new Promise(resolve => { started = resolve; }); let enqueues = 0;
  const forwarder = { prepare: async () => { started(); await waiting; return { kind: 'prepared', jobs: ['stale'] }; },
    enqueue: () => { enqueues++; return { kind: 'queued' }; } };
  const old = j.drain({ forwarder }); await entered;
  assert.throws(() => j.close(), /busy/); assert.deepEqual(await other.drain({ forwarder, limit: 1 }), []);
  f.clock(time + 60_001);
  const replacement = { prepare: async () => ({ kind: 'prepared', jobs: ['current'] }), enqueue: value => {
    assert.deepEqual(value.jobs, ['current']); enqueues++; return { kind: 'queued' };
  } };
  assert.equal((await other.drain({ forwarder: replacement }))[0].kind, 'queued');
  release(); assert.equal((await old)[0].kind, 'lease-lost'); assert.equal(enqueues, 1);
});

test('account retirement after prepared persistence prevents recovered queue admission', async t => {
  const f = fixture(t), p = pipeline(t, f), j = f.open(); j.accept(request());
  const held = { prepare: value => p.forwarder.prepare(value), enqueue: () => ({ kind: 'stopped' }) };
  const first = (await j.drain({ forwarder: held }))[0];
  p.setHost({ state: 'signed-out', observedAt: time, validUntil: time + 30_000 }); f.clock(first.retryAt);
  assert.equal((await j.drain({ forwarder: p.forwarder }))[0].kind, 'retired');
  assert.deepEqual(p.outbox.status().counts, {}); assert.equal(p.seals(), 1);
});

test('wrong keys and corrupted records are not acknowledged as processed', async t => {
  const f = fixture(t), j = f.open(); j.accept(request());
  assert.throws(() => new PushSourceJournal({ directory: f.directory, key: Buffer.alloc(32, 22), now: f.now }), /key-mismatch/);
  const db = new DatabaseSync(join(f.directory, 'source.sqlite')); db.exec("UPDATE source SET payload=X'00'"); db.close();
  const result = await j.drain({ forwarder: { prepare: () => assert.fail('corrupt'), enqueue: () => assert.fail('corrupt') } });
  assert.equal(result[0].kind, 'corrupt-record'); assert.deepEqual(j.status().counts, { pending: 1 });
  assert.equal(j.accept(request()).outcome, null);
});


test('SIGKILL after delivery commit recovers prepared ciphertext only after the original source lease', async t => {
  const f = fixture(t), journal = f.open(); journal.accept(request()); journal.close();
  const child = spawn(process.execPath, [fileURLToPath(new URL('./test-support/source-crash-worker.mjs', import.meta.url)), f.directory],
    { stdio: ['ignore', 'pipe', 'pipe'] });
  const exited = once(child, 'exit'); let diagnostic = '';
  child.stderr.on('data', chunk => { diagnostic += chunk; });
  try {
    let timer;
    await new Promise((resolve, reject) => {
      timer = setTimeout(() => reject(new Error('Crash fixture did not reach durable admission')), 5000);
      let output = '';
      child.stdout.on('data', chunk => { output += chunk; if (output.includes('admitted\n')) { clearTimeout(timer); resolve(); } });
      child.on('error', error => { clearTimeout(timer); reject(error); });
      child.on('exit', () => { clearTimeout(timer); reject(new Error('Early fixture exit: ' + diagnostic)); });
    });
    child.kill('SIGKILL'); const [, signal] = await exited; assert.equal(signal, 'SIGKILL');
  } finally { if (child.exitCode === null && child.signalCode === null) { child.kill('SIGKILL'); await exited; } }
  const recovered = f.open(), outbox = new PushOutbox({ directory: f.directory, key, now: f.now });
  try {
    let enqueue = 0;
    const forwarder = { prepare: () => assert.fail('must recover ciphertext'), enqueue: prepared => {
      enqueue++; return outbox.enqueueBatch(prepared.jobs);
    } };
    assert.deepEqual(await recovered.drain({ forwarder }), []); assert.equal(enqueue, 0);
    f.clock(time + 60_001);
    assert.equal((await recovered.drain({ forwarder }))[0].kind, 'queued'); assert.equal(enqueue, 1);
    assert.deepEqual(outbox.status().counts, { pending: 1 });
    assert.equal(recovered.accept(request()).outcome, 'queued');
  } finally { outbox.close(); }
});
