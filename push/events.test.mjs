import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { preparePushJob } from './events.mjs';
import { PushRegistrations } from './registrations.mjs';
import { PushOutbox } from './outbox.mjs';
import { PushDispatcher } from './dispatcher.mjs';

const epoch = 1_800_000_000_000;
function input() {
  return { event: { kind: 'notify', correlationID: '00000000-0000-4000-8000-000000000001', badgeCount: 1,
    hideContent: false, title: ' Ready λ 中 ', subtitle: ' Workspace ', body: ' Choose the next step ',
    replyShape: 'text', workspaceId: 'workspace', surfaceId: 'surface', notificationId: 'notice', retargetsToLiveSurfaceOwner: false },
  authority: { accountID: 'account', teamID: 'team', macDeviceID: 'mac', macInstanceTag: 'stable', macBuildID: 'mac-build',
    senderKeyID: 'sender-key', macInstallationID: 'mac-installation', publicKey: Buffer.alloc(32, 3).toString('base64') },
  registration: { token: 'fcm-token', publicKey: Buffer.alloc(32, 2).toString('base64'), registration: { id: 'registration', generation: 'generation' },
    recipient: { installationID: 'phone', keyID: 'phone-key', senderKeyID: 'sender-key', tuple: { accountID: 'account', teamID: 'team',
      iosBuildID: 'android', iosInstallationID: 'phone', macDeviceID: 'mac', macInstanceTag: 'stable', macBuildID: 'mac-build' } } },
  expiresAt: epoch + 120_000 };
}
function fakeSeal({ recipient, plaintext }) {
  return { ...structuredClone(recipient), version: 2, encapsulatedKey: Buffer.alloc(32, 4).toString('base64'),
    ciphertext: Buffer.alloc(plaintext.length + 16, 5).toString('base64') };
}
const options = { now: () => epoch, seal: fakeSeal };

test('notify builds the receiver wire shape from independent authority and erases its temporary byte buffer', async () => {
  const value = input(); let payload, buffer;
  const result = await preparePushJob(value, { ...options, seal: args => {
    buffer = args.plaintext; payload = JSON.parse(buffer); return fakeSeal(args);
  } });
  assert.equal(result.kind, 'prepared'); assert.equal(payload.title, 'Ready λ 中');
  assert.equal(payload.category, 'cmux.terminal.reply'); assert.equal(payload.expirationEpochSeconds, value.expiresAt / 1000);
  assert.equal(payload.macPushPublicKey, value.authority.publicKey); assert.equal(payload.macDeviceId, 'mac');
  assert.equal(payload.macInstallationID, 'mac-installation'); assert.equal(payload.notificationId, 'notice');
  assert.equal(result.job.eventID, value.event.correlationID); assert.equal(result.job.delivery.expiresAt, value.expiresAt);
  assert.ok(buffer.every(byte => byte === 0));
  assert.equal(JSON.stringify(result).includes('Choose the next step'), false);
});

test('hidden content is removed before encryption, while dismiss carries only the explicit IDs and badge', async () => {
  const value = input(); let payload;
  const seal = args => { payload = JSON.parse(args.plaintext); return fakeSeal(args); };
  value.event.hideContent = true;
  await preparePushJob(value, { ...options, seal });
  assert.equal(payload.title, 'cmux'); assert.equal(payload.subtitle, ''); assert.equal(payload.body, 'New terminal activity');
  assert.equal(JSON.stringify(payload).includes('Choose'), false);
  value.event.kind = 'dismiss'; value.event.badgeCount = 0; value.event.notificationIds = ['notice', ' second ', 'notice'];
  await preparePushJob(value, { ...options, seal });
  assert.deepEqual(payload.notificationIds, ['notice', 'second']); assert.equal(payload.badgeCount, 0);
  assert.equal(payload.notificationId, undefined); assert.equal(payload.replyShape, undefined); assert.equal(payload.body, '');
});

test('text truncation keeps grapheme clusters within the upstream UTF16 limits', async () => {
  const value = input(); let payload;
  value.event.title = 'x'.repeat(118) + '👩‍💻tail'; value.event.subtitle = 'x'.repeat(119) + 'e\u0301tail';
  value.event.body = '😀'.repeat(251);
  await preparePushJob(value, { ...options, seal: args => { payload = JSON.parse(args.plaintext); return fakeSeal(args); } });
  assert.equal(payload.title, 'x'.repeat(118)); assert.equal(payload.subtitle, 'x'.repeat(119));
  assert.equal(payload.body, '😀'.repeat(250));
});

test('wrong scope/Mac/sender, invalid IDs, badges and expiry are rejected before encryption', async () => {
  const changes = [
    x => x.authority.accountID = 'other', x => x.authority.teamID = null, x => x.authority.macDeviceID = 'other',
    x => x.authority.macInstanceTag = 'nightly', x => x.authority.macBuildID = 'other', x => x.authority.senderKeyID = 'other',
    x => x.event.correlationID = 'not-uuid', x => x.event.badgeCount = -1, x => x.event.badgeCount = 2 ** 31,
    x => x.event.workspaceId = 'a'.repeat(201), x => x.event.surfaceId = '\ud800',
    x => x.expiresAt = epoch, x => x.expiresAt = epoch + 901_000, x => x.expiresAt++,
    x => { x.event.kind = 'dismiss'; x.event.notificationIds = []; }
  ];
  for (const change of changes) {
    const value = input(); change(value);
    await assert.rejects(preparePushJob(value, { ...options, seal: () => assert.fail('must reject before encryption') }), /Invalid push event/);
  }
});

test('caller mutation during encryption cannot switch identity/content; expiry is checked again afterward', async () => {
  const value = input(); let release; let started;
  const entered = new Promise(resolve => { started = resolve; });
  const pending = preparePushJob(value, { ...options, seal: async args => {
    started(); await new Promise(resolve => { release = resolve; });
    assert.equal(JSON.parse(args.plaintext).title, 'Ready λ 中'); return fakeSeal(args);
  } });
  await entered; value.registration.token = 'switched'; value.registration.recipient.tuple.accountID = 'other'; value.event.title = 'changed';
  release(); const result = await pending;
  assert.equal(result.job.delivery.token, 'fcm-token'); assert.equal(result.job.delivery.recipient.tuple.accountID, 'account');
  let now = epoch;
  const expired = await preparePushJob(input(), { now: () => now, seal: args => { now += 120_000; return fakeSeal(args); } });
  assert.deepEqual(expired, { kind: 'expired' });
});

test('wrong envelope identity and oversized provider data cannot be queued', async () => {
  await assert.rejects(preparePushJob(input(), { ...options, seal: args => ({ ...fakeSeal(args), keyID: 'wrong' }) }), /Invalid push delivery/);
  const value = input(); value.event.kind = 'dismiss'; value.event.notificationIds = Array.from({ length: 50 }, (_, i) => `${i}-${'x'.repeat(100)}`);
  await assert.rejects(preparePushJob(value, options), /Invalid push delivery/);
});

test('registered event integrates with encrypted outbox deduplication; rotation during sealing denies enqueue', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'cmux-events-')); const key = Buffer.alloc(32, 42);
  const registrations = new PushRegistrations({ directory, key }); const outbox = new PushOutbox({ directory, key, now: () => epoch });
  const dispatcher = new PushDispatcher({ registrations, outbox, sender: { send: async () => ({ kind: 'accepted' }) }, policy: () => true });
  t.after(async () => { await dispatcher.stop(); outbox.close(); registrations.close(); rmSync(directory, { recursive: true, force: true }); });
  const value = input(); const { registration: ignored, ...enrollment } = value.registration;
  value.registration = registrations.replace(enrollment);
  const first = await preparePushJob(value, options);
  assert.deepEqual(dispatcher.enqueue(first.job), { kind: 'queued' });
  const repeat = await preparePushJob(value, options);
  assert.deepEqual(dispatcher.enqueue(repeat.job), { kind: 'duplicate' });
  const stale = await preparePushJob(value, { ...options, seal: args => {
    registrations.replace({ ...enrollment, token: 'rotated' }, { expectedGeneration: value.registration.registration.generation });
    return fakeSeal(args);
  } });
  assert.deepEqual(dispatcher.enqueue(stale.job), { kind: 'retired' });
});
