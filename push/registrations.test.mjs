import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { PushRegistrations } from './registrations.mjs';
import { PushOutbox } from './outbox.mjs';
import { FcmSender } from './fcm.mjs';

const key = Buffer.alloc(32, 37), epoch = 1_800_000_000_000;
function enrollment(token = 'private-fcm-token', teamID = 'private-team') {
  return { token, publicKey: Buffer.alloc(32, 7).toString('base64'), recipient: {
    installationID: 'private-phone', keyID: 'private-key', senderKeyID: 'private-sender', tuple: {
      accountID: 'private-account', teamID, iosBuildID: 'android-build', iosInstallationID: 'private-phone',
      macDeviceID: 'private-mac', macInstanceTag: 'stable', macBuildID: 'mac-build'
    }
  } };
}
const binding = value => ({ registration: value.registration, token: value.token, recipient: value.recipient });
function fixture(t, options = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'cmux-push-registry-'));
  const stores = [];
  const open = () => { const store = new PushRegistrations({ directory, key, ...options }); stores.push(store); return store; };
  t.after(() => { stores.forEach(store => store.close()); rmSync(directory, { recursive: true, force: true }); });
  return { directory, open };
}
function job(value) {
  return { eventID: 'event', registration: value.registration, delivery: { token: value.token, recipient: value.recipient,
    expiresAt: epoch + 900_000, envelope: { ...value.recipient, version: 2,
      encapsulatedKey: Buffer.alloc(32, 1).toString('base64'), ciphertext: Buffer.alloc(128, 2).toString('base64') } } };
}

test('encrypted enrollment survives reopen without cleartext identifiers or token on disk', t => {
  const f = fixture(t); let store = f.open(); const record = store.replace(enrollment()); store.close();
  const bytes = Buffer.concat(readdirSync(f.directory).filter(name => name.startsWith('registrations.sqlite')).map(name => readFileSync(join(f.directory, name))));
  for (const secret of ['private-fcm-token', 'private-account', 'private-team', 'private-phone', 'private-key', record.registration.id, record.registration.generation])
    assert.equal(bytes.includes(Buffer.from(secret)), false);
  store = f.open(); assert.equal(store.matches(binding(record)), true);
  const records = store.recipients({ accountID: 'private-account', teamID: 'private-team' }); assert.deepEqual(records, [record]);
  records[0].token = 'mutated-output'; assert.equal(store.matches(binding(record)), true);
});

test('compare-and-replace rejects stale enrollment, preserves identity, and rotates the generation on token or key changes', t => {
  const store = fixture(t).open(), first = store.replace(enrollment());
  assert.throws(() => store.replace(enrollment('replacement')), /superseded/);
  const second = store.replace(enrollment('replacement'), { expectedGeneration: first.registration.generation });
  assert.equal(second.registration.id, first.registration.id); assert.notEqual(second.registration.generation, first.registration.generation);
  assert.equal(store.matches(binding(first)), false); assert.equal(store.matches(binding(second)), true);
  assert.throws(() => store.replace(enrollment(), { expectedGeneration: first.registration.generation }), /superseded/);
  assert.deepEqual(store.replace(enrollment('replacement'), { expectedGeneration: second.registration.generation }), second);
  const changed = enrollment('replacement'); changed.recipient.keyID = 'new-key'; changed.publicKey = Buffer.alloc(32, 8).toString('base64');
  const third = store.replace(changed, { expectedGeneration: second.registration.generation });
  assert.notEqual(third.registration.generation, second.registration.generation); assert.equal(store.matches(binding(second)), false);
});

test('late UNREGISTERED response compares every binding field and cannot retire a replacement', t => {
  const store = fixture(t).open(), first = store.replace(enrollment());
  for (const mutate of [value => value.token = 'different-token', value => value.registration.id = 'different-registration',
    value => value.recipient.senderKeyID = 'different-sender', value => value.recipient.tuple.accountID = 'different-account']) {
    const wrong = structuredClone(binding(first)); mutate(wrong); assert.equal(store.retire(wrong), 'superseded');
    assert.equal(store.matches(binding(first)), true);
  }
  const second = store.replace(enrollment('replacement'), { expectedGeneration: first.registration.generation });
  assert.equal(store.retire(binding(first)), 'superseded'); assert.equal(store.matches(binding(second)), true);
  assert.equal(store.retire(binding(second)), 'retired'); assert.equal(store.retire(binding(second)), 'superseded');
  const third = store.replace(enrollment('replacement')); assert.notEqual(third.registration.id, second.registration.id);
  assert.equal(store.matches(binding(second)), false);
});

test('account/team/build slots remain separate and explicit revocation does not erase neighbors', t => {
  const store = fixture(t).open(); const first = store.replace(enrollment());
  const personal = store.replace(enrollment('personal-token', null));
  const other = enrollment('other-account'); other.recipient.tuple.accountID = 'other-account'; const neighbor = store.replace(other);
  const build = enrollment('nightly-token'); build.recipient.tuple.macBuildID = 'nightly'; const nightly = store.replace(build);
  assert.equal(store.recipients({ accountID: 'private-account' }).length, 1);
  assert.equal(store.recipients({ accountID: 'private-account', teamID: 'private-team' }).length, 2);
  assert.equal(store.revoke({ accountID: 'private-account', teamID: 'private-team' }), 2);
  assert.equal(store.matches(binding(first)), false); assert.equal(store.matches(binding(nightly)), false);
  assert.equal(store.matches(binding(personal)), true); assert.equal(store.matches(binding(neighbor)), true);
});

test('separate database connections observe replacements immediately and only one stale writer can win', t => {
  const f = fixture(t), a = f.open(), b = f.open(); const original = a.replace(enrollment());
  const current = b.replace(enrollment('connection-two'), { expectedGeneration: original.registration.generation });
  assert.equal(a.matches(binding(original)), false); assert.equal(a.matches(binding(current)), true);
  assert.throws(() => a.replace(enrollment('connection-one'), { expectedGeneration: original.registration.generation }), /superseded/);
});

test('wrong keys, altered ciphertext, and swapped rows fail without sending or silently clearing state', t => {
  const f = fixture(t); const a = f.open(), first = a.replace(enrollment());
  const second = a.replace(enrollment('other-team-token', 'other-team')); a.close();
  assert.throws(() => new PushRegistrations({ directory: f.directory, key: Buffer.alloc(32, 38) }), /key-mismatch/);
  const db = new DatabaseSync(join(f.directory, 'registrations.sqlite'));
  const rows = db.prepare('SELECT * FROM registrations').all();
  db.prepare('UPDATE registrations SET payload=? WHERE slot=?').run(rows[0].payload, rows[1].slot); db.close();
  const b = f.open(); assert.throws(() => b.recipients({ accountID: 'private-account', teamID: 'private-team' }), /corrupt-record/);
  assert.throws(() => b.revoke({ accountID: 'private-account', teamID: 'private-team' }), /corrupt-record/);
  const observed = [first, second].map(value => { try { return b.matches(binding(value)); } catch (error) { return error.kind; } });
  assert.deepEqual(observed.sort(), [true, 'corrupt-record'].sort());
});

test('capacity and malformed input never replace admitted records', t => {
  const store = fixture(t, { capacity: 1 }).open(); const first = store.replace(enrollment());
  assert.throws(() => store.replace(enrollment('other-team', 'other')), /full/);
  const invalid = enrollment(); invalid.recipient.installationID = 'unrelated-phone';
  assert.throws(() => store.replace(invalid), /invalid-input/);
  assert.throws(() => store.replace({ ...enrollment(), publicKey: 'not-a-key' }), /invalid-input/);
  assert.throws(() => store.replace({ ...enrollment(), token: 'with whitespace' }), /invalid-input/);
  assert.equal(store.matches(binding(first)), true);
});

test('real queue/sender admission rereads durable enrollment after OAuth, retiring old work without a provider request', async t => {
  const f = fixture(t), store = f.open(), other = f.open(); const first = store.replace(enrollment());
  const outbox = new PushOutbox({ directory: f.directory, key, now: () => epoch }); t.after(() => outbox.close());
  outbox.enqueue(job(first));
  const sender = new FcmSender({ projectId: 'cmux-fixture', now: () => epoch,
    tokens: { get: async () => { other.replace(enrollment('rotated-during-oauth'), { expectedGeneration: first.registration.generation }); return 'oauth'; }, invalidate() {} },
    fetch: () => assert.fail('A superseded registration reached FCM') });
  assert.deepEqual(await outbox.drain({ sender, permits: value => store.matches(value), retire: value => store.retire(value) }), [{ kind: 'retired' }]);
});

test('provider retirement does not erase a token replaced during the provider call', async t => {
  const f = fixture(t), store = f.open(), first = store.replace(enrollment());
  const outbox = new PushOutbox({ directory: f.directory, key, now: () => epoch }); t.after(() => outbox.close());
  outbox.enqueue(job(first)); let replacement;
  const sender = { send: async () => { replacement = store.replace(enrollment('new-token'), { expectedGeneration: first.registration.generation }); return { kind: 'unregistered' }; } };
  assert.deepEqual(await outbox.drain({ sender, permits: value => store.matches(value), retire: value => store.retire(value) }), [{ kind: 'unregistered' }]);
  assert.equal(store.matches(binding(replacement)), true);
});

test('provider-retired trust counts toward capacity and fresh pairing replaces that same slot', t => {
  const store = fixture(t, { capacity: 1 }).open();
  const first = store.replace(enrollment()); store.retire(binding(first));
  const neighbor = enrollment(); neighbor.recipient.tuple.macDeviceID = 'different-mac';
  assert.throws(() => store.replace(neighbor), /full/);
  const replacement = store.replace(enrollment('fresh-token'));
  assert.notEqual(first.registration.id, replacement.registration.id);
  assert.equal(store.maintenanceMatches(binding(first)), false);
  assert.equal(store.matches(binding(replacement)), true);
});
