import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createHash, createHmac } from 'node:crypto';
import { mkdtempSync, writeFileSync, readFileSync, existsSync, rmSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { request } from 'node:https';
import { PassThrough } from 'node:stream';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { NativePushHelperService } from './helper-service.mjs';
import { encodeNativePushFrame } from './native-source.mjs';
import { enrollmentFrame, enrollmentOfferDigest } from './enrollment.mjs';

const time = 1_800_000_000_000;
const desc = (id, byte) => ({ version: 1, algorithm: 'rawX25519', installationID: id, keyID: `${id}-key`, publicKey: Buffer.alloc(32, byte).toString('base64') });
const native = desc('native', 1), helper = desc('helper', 2), phone = desc('phone', 3);
const key = Buffer.alloc(32, 41), sessionID = '00000000-0000-4000-8000-000000000010';
const hostMessage = () => ({ type: 'host', snapshot: { state: 'ready', epoch: 'fixture-epoch', observedAt: time, validUntil: time + 30_000,
  trustedPhones: [{ phoneBuildID: 'android.fixture', installationID: phone.installationID, keyID: phone.keyID, publicKey: phone.publicKey }],
  authority: { accountID: 'fixture-account', teamID: null, macDeviceID: 'fixture-mac', macInstanceTag: 'stable', macBuildID: 'fixture-build' },
  settings: { forwardingEnabled: true, mode: 'always', admission: 'allowed', hideContent: false } } });
const capture = () => ({ type: 'capture', request: { sourceEpoch: 'fixture-epoch', expiresAt: time + 120_000, phoneEligible: true,
  event: { kind: 'notify', correlationID: '00000000-0000-4000-8000-000000000011', title: 'Sensitive fixture title', subtitle: '',
    body: 'Sensitive fixture body λ', replyShape: 'none', badgeCount: 1, hideContent: false, retargetsToLiveSurfaceOwner: false,
    workspaceId: null, surfaceId: null, notificationId: 'fixture-notice' } } });
let temporary, tls;
before(() => {
  temporary = mkdtempSync(join(tmpdir(), 'cmux-helper-tls-'));
  const config = join(temporary, 'openssl.cnf');
  writeFileSync(config, '[req]\ndistinguished_name=dn\nx509_extensions=ext\nprompt=no\n[dn]\nCN=localhost\n[ext]\nsubjectAltName=DNS:localhost,IP:127.0.0.1\n');
  const built = spawnSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-config', config,
    '-keyout', join(temporary, 'key.pem'), '-out', join(temporary, 'cert.pem')], { timeout: 10000, stdio: 'ignore' });
  assert.equal(built.status, 0);
  tls = { key: readFileSync(join(temporary, 'key.pem')), cert: readFileSync(join(temporary, 'cert.pem')) };
});
after(() => rmSync(temporary, { recursive: true, force: true }));
const wait = async predicate => {
  for (let i = 0; i < 200; i++) { if (predicate()) return; await new Promise(r => setTimeout(r, 5)); }
  assert.fail('Expected helper state did not arrive');
};
function post(endpoint, step, body) {
  return new Promise((resolve, reject) => {
    const req = request(endpoint, { method: 'POST', ca: tls.cert, agent: false,
      headers: { 'content-type': 'application/json' }, timeout: 2000 }, res => {
      const chunks = []; res.on('data', c => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, body: JSON.parse(Buffer.concat(chunks)) }));
    });
    req.on('error', reject); req.on('timeout', () => req.destroy(Error('fixture timeout')));
    req.end(JSON.stringify({ step, request: body }));
  });
}
function fixture(t, overrides = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'cmux-helper-service-')), root = join(directory, 'state');
  const input = new PassThrough(), output = new PassThrough(), receipts = [], challenges = [], sent = [], events = [];
  let sequence = 0, clock = time, nativeDescriptor = structuredClone(native);
  const sourceKeys = { registrations: Buffer.alloc(32, 7), outbox: Buffer.alloc(32, 8), source: Buffer.alloc(32, 9) };
  const service = new NativePushHelperService({ directory: root, keys: sourceKeys, tls, helper,
    readNativeDescriptor: () => nativeDescriptor, project: { project: 'cmux-fixture', sender: '123456' },
    clients: [{ phoneBuildID: 'android.fixture', application: 'fixture-app' }], endpoint: 'https://127.0.0.1:0/v1/push/enroll',
    now: () => clock, onState: value => events.push(value), tokens: { get: async () => 'fixture-oauth', invalidate() {} },
    seal: async ({ recipient, plaintext }) => {
      challenges.push(JSON.parse(plaintext));
      return { ...structuredClone(recipient), version: 2, encapsulatedKey: Buffer.alloc(32, 4).toString('base64'),
        ciphertext: Buffer.alloc(plaintext.length + 16, 5).toString('base64') };
    }, fetch: async (url, options) => {
      assert.equal(url, 'https://fcm.googleapis.com/v1/projects/cmux-fixture/messages:send');
      sent.push(JSON.parse(options.body)); return new Response(JSON.stringify({ name: 'projects/cmux-fixture/messages/fixture' }));
    }, ...overrides });
  let line = '';
  output.on('data', chunk => { line += chunk.toString(); let split; while ((split = line.indexOf('\n')) >= 0) {
    receipts.push(JSON.parse(line.slice(0, split))); line = line.slice(split + 1);
  } });
  input.on('error', () => {}); output.on('error', () => {});
  t.after(async () => { await service.stop(); input.destroy(); output.destroy(); rmSync(directory, { recursive: true, force: true }); });
  async function send(message, bootstrap = false) {
    const count = receipts.length;
    input.write(JSON.stringify(bootstrap ? message : encodeNativePushFrame({ sessionID, key, sequence: ++sequence, body: message })) + '\n');
    await wait(() => receipts.length > count); return receipts[count];
  }
  async function start(initialHost = hostMessage()) {
    const ready = await service.start({ input, output, host: '127.0.0.1', port: 0 });
    await send({ type: 'bootstrap', version: 1, sessionID, key: key.toString('base64') }, true);
    assert.equal((await send(initialHost)).kind, 'state-updated');
    return ready.endpoint;
  }
  return { service, root, sourceKeys, input, output, receipts, challenges, sent, events, send, start,
    advance: ms => { clock += ms; }, replaceNative: value => { nativeDescriptor = value; } };
}
const hmac = (key, domain, fields) => createHmac('sha256', key).update(enrollmentFrame(domain, fields)).digest('base64url');
async function enroll(f, endpoint) {
  const offer = f.service.issueOffer('android.fixture');
  const digest = enrollmentOfferDigest(offer), requestID = '00000000-0000-4000-8000-000000000020';
  const token = 'fixture-fcm-token';
  const requestDigest = createHash('sha256').update(enrollmentFrame('cmux-app.helper.request.v1',
    [digest, requestID, phone.installationID, phone.keyID, phone.publicKey, token])).digest('hex');
  const body = { offerID: offer.offerID, requestID, phone, token,
    proof: hmac(Buffer.from(offer.secret, 'base64url'), 'cmux-app.helper.begin.v1', [digest, requestDigest]) };
  assert.equal((await post(endpoint, 'begin', body)).status, 200);
  const challenge = f.challenges.find(c => c.kind === 'cmux-app.helper.enrollment' && c.requestID === requestID);
  const finish = { offerID: offer.offerID, requestID,
    proof: hmac(Buffer.from(challenge.challenge, 'base64url'), 'cmux-app.helper.finish.v1', [digest, requestDigest]) };
  const ack = await post(endpoint, 'finish', finish); assert.equal(ack.status, 200);
  return { offer, body, finish, ack };
}

test('construction is passive and owner credentials remain owned by caller after stop', async t => {
  const f = fixture(t); assert.equal(existsSync(f.root), false); assert.equal(f.sent.length, 0);
  assert.throws(() => f.service.issueOffer('android.fixture'), { message: 'Push helper unavailable' });
  await f.service.stop(); assert.equal(existsSync(f.root), false);
  assert.ok(f.sourceKeys.registrations.every(byte => byte === 7));
  await assert.rejects(f.service.start({ input: f.input, output: f.output, host: '127.0.0.1', port: 0 }));
});
test('authenticated TLS enrollment wakes real source/storage/FCM pipeline and preserves exact event expiry', async t => {
  const f = fixture(t), endpoint = await f.start(); await enroll(f, endpoint);
  assert.equal((await f.send(capture())).kind, 'captured');
  await wait(() => f.sent.length === 1);
  const message = f.sent[0].message;
  assert.equal(message.token, 'fixture-fcm-token'); assert.equal(message.android.ttl, '120s');
  assert.equal(message.android.priority, 'HIGH'); assert.deepEqual(Object.keys(message.data), ['cmux']);
  assert.equal(JSON.stringify(message).includes('Sensitive fixture'), false);
  assert.equal(f.challenges.find(c => c.kind === 'notify').body, 'Sensitive fixture body λ');
  assert.equal(readFileSync(join(f.root, 'source', 'source.sqlite')).includes(Buffer.from('Sensitive fixture')), false);
});
test('unknown/expired host, undeclared client and substituted native descriptor cannot issue offers', async t => {
  const f = fixture(t); await f.start();
  assert.throws(() => f.service.issueOffer('other-client'));
  f.replaceNative(helper); assert.throws(() => f.service.issueOffer('android.fixture'));
  f.replaceNative(native); await f.send({ type: 'invalidate' });
  assert.throws(() => f.service.issueOffer('android.fixture'));
  await f.send(hostMessage()); f.advance(30_000);
  assert.throws(() => f.service.issueOffer('android.fixture')); assert.equal(f.sent.length, 0);
});
test('same account with a new native epoch rejects an old enrollment completion', async t => {
  const f = fixture(t), endpoint = await f.start(); const { finish } = await enroll(f, endpoint);
  const next = hostMessage(); next.snapshot.epoch = 'new-session'; await f.send(next);
  assert.equal((await post(endpoint, 'finish', finish)).status, 403);
  assert.equal((await post(endpoint, 'issue', {})).status, 400);
});
test('native privacy tightening during OAuth prevents provider contact', async t => {
  let enter, release;
  const entered = new Promise(r => { enter = r; }), waiting = new Promise(r => { release = r; });
  const f = fixture(t, { tokens: { get: async () => { enter(); await waiting; return 'fixture-oauth'; }, invalidate() {} } });
  const endpoint = await f.start(); await enroll(f, endpoint); await f.send(capture()); await entered;
  const privateHost = hostMessage(); privateHost.snapshot.settings.hideContent = true; await f.send(privateHost);
  release(); await new Promise(r => setTimeout(r, 20)); assert.equal(f.sent.length, 0);
});
test('helper enrollment requires a freshly observed native phone pin', async t => {
  const f = fixture(t), initial = hostMessage(); delete initial.snapshot.trustedPhones;
  const endpoint = await f.start(initial), offer = f.service.issueOffer('android.fixture');
  const digest = enrollmentOfferDigest(offer), requestID = '00000000-0000-4000-8000-000000000020', token = 'fixture-token';
  const requestDigest = createHash('sha256').update(enrollmentFrame('cmux-app.helper.request.v1',
    [digest, requestID, phone.installationID, phone.keyID, phone.publicKey, token])).digest('hex');
  const reply = await post(endpoint, 'begin', { offerID: offer.offerID, requestID, phone, token,
    proof: hmac(Buffer.from(offer.secret, 'base64url'), 'cmux-app.helper.begin.v1', [digest, requestDigest]) });
  assert.equal(reply.status, 403, 'Owner offer possession alone must not replace the native phone pin');
});
for (const change of ['unavailable', 'removed', 'rotated-public-key', 'rotated-key-id', 'changed-build']) {
  test(change === 'unavailable' ? 'loss of native phone trust during OAuth prevents provider contact' :
    `native phone ${change} during OAuth prevents provider contact`, async t => {
    let enter, release;
    const entered = new Promise(r => { enter = r; }), waiting = new Promise(r => { release = r; });
    const f = fixture(t, { tokens: { get: async () => { enter(); await waiting; return 'fixture-oauth'; }, invalidate() {} } });
    try {
      const endpoint = await f.start(); await enroll(f, endpoint); await f.send(capture()); await entered;
      const next = hostMessage();
      if (change === 'unavailable') delete next.snapshot.trustedPhones;
      else if (change === 'removed') next.snapshot.trustedPhones = [];
      else if (change === 'rotated-public-key') next.snapshot.trustedPhones[0].publicKey = Buffer.alloc(32, 9).toString('base64');
      else if (change === 'rotated-key-id') next.snapshot.trustedPhones[0].keyID = 'replacement-key';
      else next.snapshot.trustedPhones[0].phoneBuildID = 'other-build';
      await f.send(next); release(); await new Promise(r => setTimeout(r, 25)); assert.equal(f.sent.length, 0);
    } finally { release(); }
  });
}
test('native phone removal rejects enrollment retries and renewal but permits authenticated cleanup', async t => {
  const f = fixture(t), endpoint = await f.start(), { offer, finish, ack } = await enroll(f, endpoint);
  const next = hostMessage(); next.snapshot.trustedPhones = []; await f.send(next);
  assert.equal((await post(endpoint, 'finish', finish)).status, 403);
  const recipient = { installationID: phone.installationID, keyID: phone.keyID, senderKeyID: helper.keyID,
    tuple: { ...offer.mac, iosBuildID: offer.phoneBuildID, iosInstallationID: phone.installationID } };
  const request = { requestID: '00000000-0000-4000-8000-000000000030', registration: ack.body.registration,
    recipient, action: 'renew', token: 'replacement-fixture-token' };
  assert.equal((await post(endpoint, 'maintain.begin', request)).status, 403);
  assert.equal((await post(endpoint, 'maintain.begin', { ...request, action: 'revoke', token: null })).status, 200);
});
test('native Mac descriptor rotation during OAuth prevents provider contact', async t => {
  let enter, release;
  const entered = new Promise(r => { enter = r; }), waiting = new Promise(r => { release = r; });
  const f = fixture(t, { tokens: { get: async () => { enter(); await waiting; return 'fixture-oauth'; }, invalidate() {} } });
  try {
    const endpoint = await f.start(); await enroll(f, endpoint); await f.send(capture()); await entered;
    f.replaceNative(desc('rotated-native', 6)); release();
    await new Promise(r => setTimeout(r, 25)); assert.equal(f.sent.length, 0);
  } finally { release(); }
});
test('native Mac rotation after restart cannot reuse an older helper enrollment', async t => {
  const original = fixture(t), endpoint = await original.start(); await enroll(original, endpoint); await original.service.stop();
  const restarted = fixture(t, { directory: original.root, readNativeDescriptor: () => desc('rotated-native', 6) });
  try {
    await restarted.start(); assert.equal((await restarted.send(capture())).kind, 'captured');
    await new Promise(r => setTimeout(r, 25)); assert.equal(restarted.sent.length, 0);
  } finally { await restarted.service.stop(); }
});
test('stop fences admission and waits for existing encryption before returning', async t => {
  let enter, release;
  const entered = new Promise(r => { enter = r; }), waiting = new Promise(r => { release = r; });
  const f = fixture(t, { seal: async () => { enter(); await waiting; return {}; } });
  const endpoint = await f.start(); const offer = f.service.issueOffer('android.fixture');
  const digest = enrollmentOfferDigest(offer), requestID = '00000000-0000-4000-8000-000000000020', token = 'fixture-token';
  const requestDigest = createHash('sha256').update(enrollmentFrame('cmux-app.helper.request.v1',
    [digest, requestID, phone.installationID, phone.keyID, phone.publicKey, token])).digest('hex');
  const request = post(endpoint, 'begin', { offerID: offer.offerID, requestID, phone, token,
    proof: hmac(Buffer.from(offer.secret, 'base64url'), 'cmux-app.helper.begin.v1', [digest, requestDigest]) }).catch(() => null);
  await entered;
  let stopped = false; const closing = f.service.stop().then(() => { stopped = true; });
  assert.throws(() => f.service.issueOffer('android.fixture'));
  await new Promise(r => setTimeout(r, 10)); assert.equal(stopped, false);
  release(); await closing; await request; assert.equal(stopped, true); assert.equal(f.sent.length, 0);
});
test('stop waits for an already-issued provider operation before closing storage', async t => {
  let enter, release;
  const entered = new Promise(r => { enter = r; }), waiting = new Promise(r => { release = r; });
  const f = fixture(t, { fetch: async () => {
    enter(); await waiting;
    return new Response(JSON.stringify({ name: 'projects/cmux-fixture/messages/fixture' }));
  } });
  const endpoint = await f.start(); await enroll(f, endpoint); await f.send(capture()); await entered;
  let stopped = false; const closing = f.service.stop().then(() => { stopped = true; });
  await new Promise(r => setTimeout(r, 10)); assert.equal(stopped, false);
  assert.throws(() => f.service.issueOffer('android.fixture'));
  release(); await closing; assert.equal(stopped, true);
});
test('source EOF stops the listener and prevents further host-local offers', async t => {
  const f = fixture(t); const endpoint = await f.start(); f.input.end();
  await wait(() => f.events.some(e => e.kind === 'closed'));
  assert.throws(() => f.service.issueOffer('android.fixture'));
  await assert.rejects(post(endpoint, 'begin', {}));
});
test('stop during startup and invalid source streams close partial listeners/stores', async t => {
  const f = fixture(t);
  const opening = f.service.start({ input: f.input, output: f.output, host: '127.0.0.1', port: 0 });
  const failure = assert.rejects(opening, { message: 'Push helper unavailable' });
  await f.service.stop(); await failure;
  const invalid = fixture(t); await assert.rejects(invalid.service.start({ input: {}, output: {}, host: '127.0.0.1', port: 0 }));
  assert.equal(invalid.sent.length, 0);
});
