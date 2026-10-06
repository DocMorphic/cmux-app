import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac, randomBytes } from 'node:crypto';
import { mkdtempSync, rmSync, readFileSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PushRegistrations } from './registrations.mjs';
import { PushMaintenance, maintenanceDigest } from './maintenance.mjs';
import { enrollmentFrame } from './enrollment.mjs';

const endpoint = 'https://helper.invalid/v1/push/enroll';
const senderPublicKey = Buffer.alloc(32, 3).toString('base64');
const hmac = (key, domain, fields) => createHmac('sha256', key).update(enrollmentFrame(domain, fields)).digest('base64url');
const initial = () => ({ token: 'private-old-token', publicKey: Buffer.alloc(32, 7).toString('base64'), recipient: {
  installationID: 'private-phone', keyID: 'private-phone-key', senderKeyID: 'private-helper-key', tuple: {
    accountID: 'private-account', teamID: null, iosBuildID: 'android.fixture', iosInstallationID: 'private-phone',
    macDeviceID: 'private-mac', macInstanceTag: 'stable', macBuildID: 'mac.fixture'
  }
} });
function fixture(t, options = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'cmux-maintenance-')), key = randomBytes(32);
  let clock = 1_800_000_000_000, allowed = true, challenge;
  let registrations = new PushRegistrations({ directory, key });
  const record = registrations.replace(initial());
  const make = () => new PushMaintenance({ registrations, endpoint, senderPublicKey, senderKeyID: 'private-helper-key', now: () => clock,
    permits: (binding, action) => { assert.deepEqual(Object.keys(binding).sort(), ['recipient', 'registration', 'token']); return allowed; },
    seal: async args => {
      assert.equal(args.publicKey, initial().publicKey); assert.equal(args.senderPublicKey, senderPublicKey);
      challenge = JSON.parse(args.plaintext); return { encryptedFixture: 'opaque' };
    }, ...options });
  let service = make();
  t.after(() => { service.close(); registrations.close(); rmSync(directory, { recursive: true, force: true }); });
  return { directory, record, get service() { return service; }, get registrations() { return registrations; },
    request: (action = 'renew', extra = {}) => ({ requestID: '00000000-0000-4000-8000-000000000001',
      registration: record.registration, recipient: record.recipient, action, token: action === 'renew' ? 'private-new-token' : null, ...extra }),
    proof: () => ({ requestID: challenge.requestID, proof: hmac(Buffer.from(challenge.challenge, 'base64url'), 'cmux-app.helper.registration.finish.v1', [challenge.requestDigest]) }),
    challenge: () => challenge,
    restart: () => { service.close(); registrations.close(); registrations = new PushRegistrations({ directory, key }); service = make(); },
    advance: ms => clock += ms, deny: () => { allowed = false; }
  };
}
const binding = record => ({ registration: record.registration, recipient: record.recipient, token: record.token });

test('renewal uses pinned phone key, stable request and durable retry receipt across host restart', async t => {
  const f = fixture(t), request = f.request();
  assert.deepEqual(await f.service.begin(request), await f.service.begin(request));
  assert.equal(f.challenge().requestDigest, maintenanceDigest(endpoint, request));
  assert.equal(f.registrations.matches(binding(f.record)), true);
  const proof = f.proof(), challenge = f.challenge(), ack = f.service.finish(proof);
  assert.equal(ack.action, 'renew'); assert.equal(ack.registration.id, f.record.registration.id);
  assert.notEqual(ack.registration.generation, f.record.registration.generation);
  assert.equal(ack.proof, hmac(Buffer.from(challenge.challenge, 'base64url'), 'cmux-app.helper.registration.ack.v1',
    [challenge.requestDigest, ack.registration.id, ack.registration.generation]));
  assert.deepEqual(f.service.finish(proof), ack); f.restart(); assert.deepEqual(f.service.finish(proof), ack);
  assert.equal(f.registrations.recipients({ accountID: 'private-account' })[0].token, 'private-new-token');
  const raw = Buffer.concat(readdirSync(f.directory).filter(name => name.endsWith('.sqlite') || name.endsWith('-wal')).map(name => readFileSync(join(f.directory, name))));
  for (const secret of ['private-old-token', 'private-new-token', request.requestID, proof.proof, ack.registration.generation])
    assert.equal(raw.includes(Buffer.from(secret)), false);
});
test('revocation is authenticated, durable and cannot revoke a replacement registration', async t => {
  const f = fixture(t); await f.service.begin(f.request('revoke')); const proof = f.proof();
  const ack = f.service.finish(proof); assert.equal(ack.registration, null); assert.equal(ack.action, 'revoke');
  f.restart(); assert.deepEqual(f.service.finish(proof), ack);
  const replacement = f.registrations.replace(initial());
  assert.throws(() => f.service.finish(proof), /superseded/);
  assert.equal(f.registrations.matches(binding(replacement)), true);
});
test('changed token, action, account, endpoint or generation changes transcript; unknown key cannot replace pin', async t => {
  const f = fixture(t), body = f.request(); await f.service.begin(body);
  for (const change of [value => value.token = 'attacker-token', value => { value.action = 'revoke'; value.token = null; },
    value => value.recipient.keyID = 'attacker-key', value => value.recipient.tuple.accountID = 'attacker',
    value => value.registration.generation = '00000000-0000-4000-8000-000000000002']) {
    const other = structuredClone(body); change(other); await assert.rejects(f.service.begin(other));
    assert.notEqual(maintenanceDigest(endpoint, other), maintenanceDigest(endpoint, body));
  }
  assert.notEqual(maintenanceDigest(endpoint, body), maintenanceDigest('https://other.invalid/v1/push/enroll', body));
  assert.throws(() => f.service.finish({ ...f.proof(), proof: Buffer.alloc(32).toString('base64url') }));
  assert.equal(f.registrations.matches(binding(f.record)), true);
});
test('expiry or restart before commit requests a new challenge without changing registration', async t => {
  const f = fixture(t), body = f.request(); await f.service.begin(body); const proof = f.proof();
  f.advance(120_000); assert.throws(() => f.service.finish(proof), /challenge-required/);
  await f.service.begin(body); const next = f.proof(); assert.notDeepEqual(proof, next);
  f.restart(); assert.throws(() => f.service.finish(next), /challenge-required/);
  assert.equal(f.registrations.matches(binding(f.record)), true);
  await f.service.begin(body); f.service.finish(f.proof());
});
test('late provider removal or local re-pairing fences an active renewal and old receipt', async t => {
  const f = fixture(t); await f.service.begin(f.request()); const proof = f.proof();
  const replacement = f.registrations.replace({ ...initial(), token: 'replacement' }, { expectedGeneration: f.record.registration.generation });
  assert.throws(() => f.service.finish(proof), /superseded/);
  assert.equal(f.registrations.matches(binding(replacement)), true);
});
test('fresh host policy is required before challenge, commit and replayed receipt', async t => {
  const f = fixture(t); await f.service.begin(f.request()); const proof = f.proof(); f.service.finish(proof); f.deny();
  assert.throws(() => f.service.finish(proof));
  const other = fixture(t); await other.service.begin(other.request()); const rejected = other.proof(); other.deny();
  assert.throws(() => other.service.finish(rejected)); assert.equal(other.registrations.matches(binding(other.record)), true);
  const asyncPolicy = fixture(t, { permits: async () => true }); await assert.rejects(asyncPolicy.service.begin(asyncPolicy.request()));
});
test('one bounded challenge per registration, cancellation during seal cannot mutate storage', async t => {
  let release; const pending = new Promise(resolve => { release = resolve; });
  const f = fixture(t, { capacity: 1, seal: async () => { await pending; return {}; } });
  const first = f.service.begin(f.request());
  await assert.rejects(f.service.begin(f.request('renew', { requestID: '00000000-0000-4000-8000-000000000002' })));
  f.service.close(); release(); await assert.rejects(first);
  assert.equal(f.registrations.matches(binding(f.record)), true);
});
test('receipt failure rolls back token mutation and leaves original generation retryable', t => {
  const f = fixture(t), input = { requestID: f.request().requestID, digest: 'a'.repeat(64), proof: 'A'.repeat(43), action: 'renew',
    expected: binding(f.record), token: 'replacement', expiresAt: 1_800_000_060_000 };
  assert.throws(() => f.registrations.maintain(input, () => { throw Error('disk/receipt failure'); }, 1_800_000_000_000));
  assert.equal(f.registrations.matches(binding(f.record)), true);
  assert.equal(f.registrations.maintenanceReceipt(input.requestID, 1_800_000_000_000), null);
  f.registrations.maintain(input, registration => ({ registration }), 1_800_000_000_000);
  assert.equal(f.registrations.recipients({ accountID: 'private-account' })[0].token, 'replacement');
});

test('logout deletes scoped receipts; receipts expire and cannot be replayed under a different helper key', async t => {
  const f = fixture(t); await f.service.begin(f.request()); const proof = f.proof(); f.service.finish(proof);
  const rotated = new PushMaintenance({ registrations: f.registrations, endpoint, senderPublicKey, senderKeyID: 'replacement-helper',
    permits: () => true, seal: async () => ({}), now: () => 1_800_000_000_000 });
  t.after(() => rotated.close()); assert.throws(() => rotated.finish(proof));
  assert.equal(f.registrations.revoke({ accountID: 'private-account', teamID: 'other-team' }), 0);
  assert.ok(f.registrations.maintenanceReceipt(proof.requestID, 1_800_000_000_000));
  assert.equal(f.registrations.revoke({ accountID: 'private-account' }), 1);
  assert.equal(f.registrations.maintenanceReceipt(proof.requestID, 1_800_000_000_000), null);
  const next = fixture(t); await next.service.begin(next.request()); const saved = next.proof(); next.service.finish(saved);
  next.advance(86_400_000); assert.throws(() => next.service.finish(saved), /challenge-required/);
  assert.equal(next.registrations.maintenanceReceipt(saved.requestID, 1_800_086_400_000), null);
});

test('UNREGISTERED stops delivery but preserves pinned-key token recovery; re-pairing and logout retire old trust', async t => {
  const f = fixture(t);
  assert.equal(f.registrations.retire(binding(f.record)), 'retired');
  assert.equal(f.registrations.matches(binding(f.record)), false);
  assert.deepEqual(f.registrations.recipients({ accountID: 'private-account' }), []);
  f.restart(); await f.service.begin(f.request()); const ack = f.service.finish(f.proof());
  const recovered = f.registrations.recipients({ accountID: 'private-account' })[0];
  assert.deepEqual(recovered.registration, ack.registration); assert.equal(recovered.token, 'private-new-token');
  assert.equal(f.registrations.retire(binding(recovered)), 'retired');
  const replacement = f.registrations.replace(initial());
  assert.notEqual(replacement.registration.id, recovered.registration.id);
  assert.equal(f.registrations.maintenanceRegistration(recovered.recipient, recovered.registration), null);
  f.registrations.retire(binding(replacement));
  assert.equal(f.registrations.revoke({ accountID: 'private-account' }), 1);
  assert.equal(f.registrations.maintenanceRegistration(replacement.recipient, replacement.registration), null);
});

test('abort cancels an unwritten finish, or recovers an already committed receipt without applying renewal again', async t => {
  const f = fixture(t); await f.service.begin(f.request()); const proof = f.proof();
  assert.deepEqual(f.service.abort(proof), { requestID: proof.requestID, ack: null });
  assert.throws(() => f.service.finish(proof), /challenge-required/);
  assert.equal(f.registrations.matches(binding(f.record)), true);
  const next = f.request('renew', { requestID: '00000000-0000-4000-8000-000000000002' });
  await f.service.begin(next); const nextProof = f.proof(); const ack = f.service.finish(nextProof);
  f.restart(); assert.deepEqual(f.service.abort(nextProof), { requestID: nextProof.requestID, ack });
  assert.throws(() => f.service.abort({ ...nextProof, proof: 'A'.repeat(43) }));
});
