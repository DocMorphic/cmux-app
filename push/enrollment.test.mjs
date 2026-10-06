import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash, createHmac, randomBytes } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PushEnrollment, enrollmentFrame, enrollmentOfferDigest } from './enrollment.mjs';
import { PushRegistrations } from './registrations.mjs';

const descriptor = (id, byte) => ({ version: 1, algorithm: 'rawX25519', installationID: id, keyID: `${id}-key`, publicKey: Buffer.alloc(32, byte).toString('base64') });
const input = () => ({ endpoint: 'https://helper.example/v1/push/enroll', project: { project: 'fixture-project', application: 'fixture-app', sender: '123456' },
  phoneBuildID: 'android.fixture', mac: { accountID: 'fixture-user', teamID: null, macDeviceID: 'fixture-mac', macInstanceTag: 'stable', macBuildID: 'fixture.mac' },
  native: descriptor('native', 1), helper: descriptor('helper', 2) });
const hmac = (key, domain, fields) => createHmac('sha256', key).update(enrollmentFrame(domain, fields)).digest('base64url');
function request(offer, override = {}) {
  const body = { offerID: offer.offerID, requestID: '00000000-0000-4000-8000-000000000001', phone: descriptor('phone', 3), token: 'fixture-fcm-token', ...override };
  const digest = enrollmentOfferDigest(offer), requestDigest = createHash('sha256').update(enrollmentFrame('cmux-app.helper.request.v1',
    [digest, body.requestID, body.phone.installationID, body.phone.keyID, body.phone.publicKey, body.token])).digest('hex');
  return { ...body, proof: hmac(Buffer.from(offer.secret, 'base64url'), 'cmux-app.helper.begin.v1', [digest, requestDigest]) };
}
function finish(challenge) {
  return { offerID: challenge.offerID, requestID: challenge.requestID,
    proof: hmac(Buffer.from(challenge.challenge, 'base64url'), 'cmux-app.helper.finish.v1', [challenge.offerDigest, challenge.requestDigest]) };
}
function fixture(t, overrides = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'cmux-enroll-'));
  const registrations = new PushRegistrations({ directory, key: randomBytes(32) });
  let clock = 1_800_000_000_000, allowed = true, latest;
  const service = new PushEnrollment({ registrations, now: () => clock, permits: offer => {
    assert.equal(offer.secret, undefined); return allowed;
  }, seal: async ({ plaintext }) => { latest = JSON.parse(plaintext); return { encryptedFixture: 'opaque' }; }, ...overrides });
  t.after(() => { service.close(); registrations.close(); rmSync(directory, { recursive: true, force: true }); });
  return { service, registrations, challenge: () => latest, advance: ms => clock += ms, deny: () => { allowed = false; } };
}

test('proof commits one durable registration and exact retries preserve generation and acknowledgment', async t => {
  const f = fixture(t), offer = f.service.issue(input()), body = request(offer);
  const a = await f.service.begin(body), b = await f.service.begin(body); assert.deepEqual(a, b);
  assert.deepEqual(f.registrations.recipients({ accountID: 'fixture-user' }), []);
  const proof = finish(f.challenge()), ack = f.service.finish(proof);
  assert.deepEqual(f.service.finish(proof), ack);
  const record = f.registrations.recipients({ accountID: 'fixture-user' })[0];
  assert.equal(record.token, body.token); assert.equal(record.recipient.senderKeyID, offer.helper.keyID);
  assert.equal(ack.proof, hmac(Buffer.from(f.challenge().challenge, 'base64url'), 'cmux-app.helper.ack.v1',
    [f.challenge().offerDigest, f.challenge().requestDigest, ack.registration.id, ack.registration.generation]));
});
test('wrong offer secret and changed request fields cannot request a challenge or replace the enrolled phone', async t => {
  const f = fixture(t), offer = f.service.issue(input()), body = request(offer);
  await assert.rejects(f.service.begin({ ...body, token: 'substituted' }));
  await assert.rejects(f.service.begin({ ...body, proof: Buffer.alloc(32).toString('base64url') }));
  await f.service.begin(body);
  await assert.rejects(f.service.begin(request(offer, { token: 'different-valid-request' })));
  assert.throws(() => f.service.finish({ ...finish(f.challenge()), proof: body.proof }));
  assert.equal(f.registrations.recipients({ accountID: 'fixture-user' }).length, 0);
});
test('expiry and revoked host authority reject delayed proofs and do not create registrations', async t => {
  const f = fixture(t), offer = f.service.issue(input()); await f.service.begin(request(offer)); const proof = finish(f.challenge());
  f.advance(120_000); assert.throws(() => f.service.finish(proof));
  const next = f.service.issue(input()); await f.service.begin(request(next)); f.deny();
  assert.throws(() => f.service.finish(finish(f.challenge())));
  assert.equal(f.registrations.recipients({ accountID: 'fixture-user' }).length, 0);
});
test('expiry or cancellation while sealing cannot publish a usable challenge', async t => {
  let release; const blocked = new Promise(resolve => { release = resolve; });
  const f = fixture(t, { seal: async () => { await blocked; return {}; } });
  const offer = f.service.issue(input()); const pending = f.service.begin(request(offer));
  f.service.cancel(offer.offerID); release(); await assert.rejects(pending);
  await assert.rejects(f.service.begin(request(offer)));
});
test('overlapping offers use compare-and-replace so the later completion cannot overwrite a newer token', async t => {
  const f = fixture(t), a = f.service.issue(input()), b = f.service.issue(input());
  await f.service.begin(request(a, { token: 'first' })); const proofA = finish(f.challenge());
  await f.service.begin(request(b, { token: 'second' })); const proofB = finish(f.challenge());
  f.service.finish(proofA); assert.throws(() => f.service.finish(proofB), /superseded/);
  assert.equal(f.registrations.recipients({ accountID: 'fixture-user' })[0].token, 'first');
  const c = f.service.issue(input()); await f.service.begin(request(c, { token: 'recovered' })); f.service.finish(finish(f.challenge()));
  assert.equal(f.registrations.recipients({ accountID: 'fixture-user' })[0].token, 'recovered');
  assert.throws(() => f.service.finish(proofA));
});
test('failed encryption retires an offer without registering an unproven key', async t => {
  const f = fixture(t, { seal: async () => { throw Error('private failure'); } });
  const offer = f.service.issue(input());
  await assert.rejects(f.service.begin(request(offer)), { message: 'Push enrollment rejected' });
  assert.equal(f.registrations.recipients({ accountID: 'fixture-user' }).length, 0);
});
test('offers are bounded, HTTPS-only, and require a synchronous affirmative host policy', t => {
  const f = fixture(t, { capacity: 1 }); const offer = f.service.issue(input());
  assert.throws(() => f.service.issue(input())); f.service.cancel(offer.offerID);
  for (const endpoint of ['http://helper.example/v1/push/enroll', 'https://helper.example/v1/push/enroll?secret=x', 'https://user:pass@helper.example/v1/push/enroll'])
    assert.throws(() => f.service.issue({ ...input(), endpoint }));
  assert.throws(() => f.service.issue({ ...input(), helper: input().native }));
  const asyncPolicy = fixture(t, { permits: async () => true }); assert.throws(() => asyncPolicy.service.issue(input()));
});

test('a fresh confirmed offer rotates identical registration content to fence older cleanup proofs', async t => {
  const f = fixture(t), offer = f.service.issue(input()); await f.service.begin(request(offer));
  const first = f.service.finish(finish(f.challenge()));
  const renewed = f.service.issue(input()); await f.service.begin(request(renewed));
  const second = f.service.finish(finish(f.challenge()));
  assert.equal(first.registration.id, second.registration.id);
  assert.notEqual(first.registration.generation, second.registration.generation);
});
