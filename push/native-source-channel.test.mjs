import test from 'node:test';
import assert from 'node:assert/strict';
import { PassThrough, Writable } from 'node:stream';
import { readFileSync } from 'node:fs';
import { NativePushSourceChannel } from './native-source-channel.mjs';
import { NativePushSourceAdapter, encodeNativePushFrame } from './native-source.mjs';

const key = Buffer.alloc(32, 41), sessionID = '00000000-0000-4000-8000-000000000010';
const vectors = JSON.parse(readFileSync(new URL('./test-support/native-source-frames.json', import.meta.url)));
const bootstrap = { type: 'bootstrap', version: 1, sessionID, key: key.toString('base64') };
const line = value => Buffer.from(JSON.stringify(value) + '\n');
const tick = () => new Promise(resolve => setImmediate(resolve));
function fixture(t, { output: suppliedOutput, create: suppliedCreate } = {}) {
  const input = new PassThrough(), replies = [], states = [], captured = []; let adapter, provisionedKey;
  const output = suppliedOutput ?? new Writable({ write(chunk, encoding, callback) { replies.push(JSON.parse(chunk)); callback(); } });
  // An owning process retains stream error supervision after channel shutdown.
  input.on('error', () => {}); output.on('error', () => {});
  const channel = new NativePushSourceChannel({ input, output, onState: value => states.push(value),
    createAdapter: suppliedCreate ?? (options => {
      provisionedKey = options.key;
      adapter = new NativePushSourceAdapter({ ...options, now: () => 1_800_000_000_000,
        helper: { senderKeyID: 'helper-key', macInstallationID: 'helper-install', publicKey: Buffer.alloc(32, 1).toString('base64') },
        capture: value => { captured.push(value); return { kind: 'captured' }; }, changed: () => {} });
      return adapter;
    }) });
  t.after(async () => { await channel.stop(); input.destroy(); output.destroy(); });
  return { channel, input, output, replies, states, captured, adapter: () => adapter, provisionedKey: () => provisionedKey };
}
test('private bootstrap is passive until start and Swift frames receive durable/coarse receipts', async t => {
  const f = fixture(t); assert.equal(f.input.listenerCount('data'), 0); assert.equal(f.adapter(), undefined);
  const done = f.channel.start(); assert.equal(f.channel.start(), done);
  f.input.write(Buffer.concat([line(bootstrap), ...vectors.map(line)])); await tick();
  assert.deepEqual(f.replies.map(r => [r.sequence, r.kind, r.acknowledged]), [[0, 'ready', true], [1, 'state-updated', true], [2, 'captured', true], [3, 'captured', true]]);
  assert.equal(f.captured.length, 2); assert.equal(f.provisionedKey().equals(Buffer.alloc(32)), true);
  assert.equal(f.adapter().readHost().authority.accountID, 'fixture-account');
  const receipts = JSON.stringify(f.replies), diagnostics = JSON.stringify(f.states);
  for (const secret of [key.toString('base64'), sessionID, 'fixture-account', 'Ready λ', 'fixture-notice']) {
    assert.equal(receipts.includes(secret), false); assert.equal(diagnostics.includes(secret), false);
  }
  f.input.end(); assert.deepEqual(await done, { kind: 'closed' }); assert.equal(f.adapter().readHost(), null);
});
test('fragmented bytes and multiple lines preserve UTF-8 and frame ordering', async t => {
  const f = fixture(t); f.channel.start(); const bytes = Buffer.concat([line(bootstrap), ...vectors.map(line)]);
  for (let i = 0; i < bytes.length; i += 7) { f.input.write(bytes.subarray(i, i + 7)); await tick(); }
  assert.equal(f.replies.length, 4); assert.equal(f.captured[0].event.title, 'Ready λ 中 👩🏽‍💻');
  f.input.end(); await tick(); assert.equal(f.adapter().readHost(), null);
});
test('frames before bootstrap, malformed keys and added bootstrap authority cannot establish a source', async t => {
  for (const value of [vectors[0], { ...bootstrap, key: 'bad' }, { ...bootstrap, accountID: 'injected' },
    { ...bootstrap, sessionID: 'bad' }, { ...bootstrap, version: 2 }]) {
    const f = fixture(t), done = f.channel.start(); f.input.end(line(value));
    assert.equal((await done).kind, 'source-rejected'); assert.equal(f.adapter(), undefined); assert.equal(f.replies.length, 0);
  }
});
test('tampered source closes the channel and immediately invalidates the prior host', async t => {
  const f = fixture(t), done = f.channel.start(); f.input.write(Buffer.concat([line(bootstrap), line(vectors[0])])); await tick();
  assert.equal(f.adapter().readHost().state, 'ready');
  f.input.end(line({ ...vectors[1], proof: Buffer.alloc(32).toString('base64') }));
  assert.equal((await done).kind, 'source-rejected'); assert.equal(f.adapter().readHost(), null); assert.equal(f.captured.length, 0);
});
test('oversize, invalid UTF-8 and truncated EOF fail without leaking input', async t => {
  for (const value of [Buffer.alloc(1_400_000, 120), Buffer.from([0xff, 10]), Buffer.from('{"key":"private'), Buffer.from('\n')]) {
    const f = fixture(t), done = f.channel.start(); f.input.end(value);
    assert.equal((await done).kind, 'source-rejected'); assert.equal(f.replies.length, 0);
    assert.equal(JSON.stringify(f.states).includes('private'), false);
  }
});
test('output backpressure prevents acknowledging/capturing subsequent frames prematurely', async t => {
  const callbacks = [], received = [];
  const output = new Writable({ write(chunk, encoding, callback) { received.push(JSON.parse(chunk)); callbacks.push(callback); } });
  const f = fixture(t, { output }); f.channel.start(); f.input.write(Buffer.concat([line(bootstrap), ...vectors.map(line)])); await tick();
  assert.equal(received.length, 1); assert.equal(f.adapter().readHost(), null);
  callbacks.shift()(); await tick(); assert.equal(received.length, 2); assert.equal(f.captured.length, 0);
  callbacks.shift()(); await tick(); assert.equal(received.length, 3); assert.equal(f.captured.length, 1);
  f.input.emit('end'); assert.equal(f.adapter().readHost(), null);
  assert.deepEqual(await f.channel.stop(), { kind: 'closed' });
  callbacks.shift()(); await tick(); assert.equal(received.length, 3); assert.equal(f.captured.length, 1);
});
test('write failures invalidate host authority and report no private error text', async t => {
  let writes = 0;
  const output = new Writable({ write(chunk, encoding, callback) { callback(++writes === 2 ? new Error('private pipe diagnostic') : undefined); } });
  const f = fixture(t, { output }), done = f.channel.start(); f.input.write(Buffer.concat([line(bootstrap), ...vectors.map(line)]));
  const result = await done; assert.ok(['source-rejected', 'channel-unavailable'].includes(result.kind));
  assert.equal(f.adapter().readHost(), null); assert.equal(f.captured.length, 0); assert.equal(JSON.stringify(f.states).includes('private'), false);
});
test('an explicitly unacknowledged capture can refresh its lease without changing its original request', async t => {
  const f = fixture(t); f.channel.start(); f.input.write(Buffer.concat([line(bootstrap), line({ ...vectors[1], sequence: 1 })]));
  // Changing the sequence without recomputing authentication must be rejected.
  await tick(); assert.equal(f.adapter().readHost(), null); assert.equal(f.captured.length, 0);
  const g = fixture(t); g.channel.start(); const body = JSON.parse(Buffer.from(vectors[1].body, 'base64'));
  const capture = encodeNativePushFrame({ sessionID, key, sequence: 1, body });
  g.input.write(Buffer.concat([line(bootstrap), line(capture)])); await tick();
  assert.deepEqual(g.replies[1], { sequence: 1, kind: 'source-unavailable', acknowledged: false });
  g.input.write(Buffer.concat([line(vectors[0]), line(vectors[1])])); await tick();
  assert.equal(g.captured[0].expiresAt, body.request.expiresAt); assert.equal(g.replies.at(-1).kind, 'captured');
});
