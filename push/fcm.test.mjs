import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, verify } from 'node:crypto';
import { fcmMessage, serviceAccountTokens, FcmSender, PushTransportError } from './fcm.mjs';

const now = 1_800_000_000_000;
const tuple = { accountID: 'account', teamID: 'team', iosBuildID: 'io.github.docmorphic.cmuxapp',
  iosInstallationID: 'phone', macDeviceID: 'mac', macInstanceTag: 'stable', macBuildID: 'com.cmuxterm.app' };
function input() {
  const recipient = { installationID: 'phone', keyID: 'recipient-key', senderKeyID: 'mac-key', tuple: { ...tuple } };
  return { token: 'fixture-device-token', recipient, expiresAt: now + 120_000,
    envelope: { ...structuredClone(recipient), version: 2, encapsulatedKey: Buffer.alloc(32, 1).toString('base64'),
      ciphertext: Buffer.alloc(128, 2).toString('base64') } };
}
const response = (status, body, headers = {}) => new Response(JSON.stringify(body), { status, headers });
const accepted = () => response(200, { name: 'projects/cmux-fixture/messages/123' });
const tokens = { get: async () => 'oauth-fixture', invalidate: () => {} };
const send = (fetch, options = {}) => new FcmSender({ projectId: 'cmux-fixture', tokens, fetch, now: () => now, ...options });
const permitted = { permits: () => true };

test('single-recipient encrypted data, expiry and no plaintext/collapse payload', () => {
  const value = input(); const message = fcmMessage(value, now);
  assert.deepEqual(Object.keys(message), ['token', 'data', 'android']);
  assert.deepEqual(Object.keys(message.data), ['cmux']);
  assert.deepEqual(JSON.parse(message.data.cmux), { encryptedPayloads: [value.envelope] });
  assert.deepEqual(message.android, { priority: 'HIGH', ttl: '120s' });
  assert.equal(fcmMessage({ ...value, expiresAt: now + 999 }, now), null);
  assert.equal(fcmMessage({ ...value, expiresAt: now - 1 }, now), null);
  assert.throws(() => fcmMessage({ ...value, expiresAt: now + 900_001 }, now));
});
test('all identities are bound to a separately admitted recipient', () => {
  for (const key of Object.keys(tuple)) {
    const value = input(); value.envelope.tuple[key] = 'different';
    assert.throws(() => fcmMessage(value, now), /Invalid push delivery request/);
  }
  for (const key of ['installationID', 'keyID', 'senderKeyID']) {
    const value = input(); value.envelope[key] = 'different'; assert.throws(() => fcmMessage(value, now));
  }
  const anonymous = input(); anonymous.recipient.tuple.accountID = null; anonymous.envelope.tuple.accountID = null;
  assert.throws(() => fcmMessage(anonymous, now));
});
test('rejects plaintext fields, malformed base64 and provider-size overflow before any request', async () => {
  for (const mutate of [v => v.envelope.title = 'plaintext', v => v.envelope.encapsulatedKey = 'garbage',
    v => v.envelope.ciphertext = Buffer.alloc(4000).toString('base64'), v => v.token = 'contains whitespace']) {
    const value = input(); mutate(value);
    await assert.rejects(() => send(() => assert.fail('Unexpected network call')).send(value, permitted));
  }
});
test('sends to pinned FCM endpoint and reports provider acceptance, not phone delivery', async () => {
  const transport = send(async (url, request) => {
    assert.equal(url, 'https://fcm.googleapis.com/v1/projects/cmux-fixture/messages:send');
    assert.equal(request.redirect, 'error'); assert.equal(request.headers.authorization, 'Bearer oauth-fixture');
    assert.deepEqual(JSON.parse(request.body), { message: fcmMessage(input(), now) });
    return accepted();
  });
  assert.deepEqual(await transport.send(input(), permitted), { kind: 'accepted' });
});
test('default denial and retirement during OAuth acquisition send nothing', async () => {
  assert.deepEqual(await send(() => assert.fail()).send(input()), { kind: 'retired' });
  let allowed = true;
  const transport = send(() => assert.fail('retired request sent'), { tokens: { get: async () => { allowed = false; return 'token'; }, invalidate() {} } });
  assert.deepEqual(await transport.send(input(), { permits: () => allowed }), { kind: 'retired' });
  assert.deepEqual(await send(() => assert.fail()).send(input(), { permits: async () => true }), { kind: 'retired' });
});
test('expiry is checked after slow authentication and input cannot be mutated while waiting', async () => {
  let time = now; const original = input();
  const expired = send(() => assert.fail('expired request sent'), { now: () => time,
    tokens: { get: async () => { time += 120_000; return 'token'; }, invalidate() {} } });
  assert.deepEqual(await expired.send(original, permitted), { kind: 'expired' });
  const transport = send(async (_, request) => { assert.equal(JSON.parse(request.body).message.token, 'fixture-device-token'); return accepted(); },
    { tokens: { get: async () => { original.token = 'attacker'; original.envelope.tuple.accountID = 'other'; return 'token'; }, invalidate() {} } });
  assert.deepEqual(await transport.send(original, permitted), { kind: 'accepted' });
});
test('401 refreshes once, including rechecking retirement; no indefinite retry', async () => {
  let sends = 0; let invalidations = 0;
  const transport = send(async () => { sends++; return response(401, {}); }, {
    tokens: { get: async () => 'token', invalidate: value => { assert.equal(value, 'token'); invalidations++; } } });
  assert.deepEqual(await transport.send(input(), permitted), { kind: 'credentials' });
  assert.equal(sends, 2); assert.equal(invalidations, 1);
  let allowed = true; let retiredSends = 0;
  const retired = send(async () => { retiredSends++; return response(401, {}); }, {
    tokens: { get: async () => 'token', invalidate: () => { allowed = false; } } });
  assert.deepEqual(await retired.send(input(), { permits: () => allowed }), { kind: 'retired' });
  assert.equal(retiredSends, 1);
});
test('unregistered requires the precise FCM detail; generic failures never remove registrations', async () => {
  const error = code => ({ error: { details: [{ '@type': 'type.googleapis.com/google.firebase.fcm.v1.FcmError', errorCode: code }] } });
  assert.deepEqual(await send(async () => response(404, error('UNREGISTERED'))).send(input(), permitted), { kind: 'unregistered' });
  for (const [status, body] of [[404, {}], [400, error('INVALID_ARGUMENT')], [400, error('UNREGISTERED')]])
    assert.deepEqual(await send(async () => response(status, body)).send(input(), permitted), { kind: 'rejected' });
});
test('quota and unavailable responses respect Retry-After; network failure preserves ambiguity', async () => {
  assert.deepEqual(await send(async () => response(429, {}, { 'retry-after': '3' })).send(input(), permitted),
    { kind: 'unavailable', retryAfterMs: 60_000, ambiguous: false });
  assert.deepEqual(await send(async () => response(503, {}, { 'retry-after': new Date(now + 120_000).toUTCString() })).send(input(), permitted),
    { kind: 'unavailable', retryAfterMs: 120_000, ambiguous: true });
  assert.deepEqual(await send(async () => { throw new Error('private request details'); }).send(input(), permitted),
    { kind: 'unavailable', retryAfterMs: 1000, ambiguous: true });
  assert.deepEqual(await send(async () => response(200, {})).send(input(), permitted),
    { kind: 'unavailable', retryAfterMs: 1000, ambiguous: true });
});

const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const account = { type: 'service_account', project_id: 'cmux-fixture', client_email: 'push@cmux-fixture.iam.gserviceaccount.com',
  private_key_id: 'synthetic', private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }), token_uri: 'https://oauth2.googleapis.com/token' };
test('OAuth assertions have verified RSA signatures, limited scope, fixed audience and one-hour lifetime', async () => {
  let calls = 0; let time = now;
  const provider = serviceAccountTokens(account, { now: () => time, fetch: async (url, request) => {
    calls++; assert.equal(url, 'https://oauth2.googleapis.com/token'); assert.equal(request.redirect, 'error');
    const form = new URLSearchParams(request.body);
    assert.equal(form.get('grant_type'), 'urn:ietf:params:oauth:grant-type:jwt-bearer');
    const [header, payload, signature] = form.get('assertion').split('.');
    assert.ok(verify('RSA-SHA256', Buffer.from(`${header}.${payload}`), publicKey, Buffer.from(signature, 'base64url')));
    assert.deepEqual(JSON.parse(Buffer.from(header, 'base64url')), { alg: 'RS256', typ: 'JWT', kid: 'synthetic' });
    const claims = JSON.parse(Buffer.from(payload, 'base64url'));
    assert.equal(claims.scope, 'https://www.googleapis.com/auth/firebase.messaging');
    assert.equal(claims.aud, url); assert.equal(claims.iss, account.client_email); assert.equal(claims.exp - claims.iat, 3600);
    return response(200, { access_token: `access-${calls}`, token_type: 'Bearer', expires_in: 3600 });
  } });
  assert.deepEqual(await Promise.all([provider.get(), provider.get(), provider.get()]), ['access-1', 'access-1', 'access-1']);
  provider.invalidate('unrelated'); assert.equal(await provider.get(), 'access-1');
  provider.invalidate('access-1'); assert.equal(await provider.get(), 'access-2');
  time += 3_541_000; assert.equal(await provider.get(), 'access-3'); assert.equal(calls, 3);
});
test('credentials cannot redirect private assertions and invalid keys/errors remain redacted', async () => {
  assert.throws(() => serviceAccountTokens({ ...account, token_uri: 'https://example.com/steal' }));
  assert.throws(() => serviceAccountTokens({ ...account, private_key: 'secret-private-value' }), error => !error.message.includes('secret-private-value'));
  const provider = serviceAccountTokens(account, { fetch: async () => response(400, { error: 'private details' }) });
  await assert.rejects(() => provider.get(), error => error instanceof PushTransportError && error.kind === 'credentials' && !error.message.includes('private details'));
});
