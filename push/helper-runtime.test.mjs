import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, randomUUID } from 'node:crypto';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync, lstatSync, existsSync } from 'node:fs';
import { spawn, spawnSync } from 'node:child_process';
import { PassThrough } from 'node:stream';
import { createServer } from 'node:net';
import { once } from 'node:events';
import { join } from 'node:path';
import { NativePushHelperRuntime } from './helper-runtime.mjs';
import { requestPushOwner } from './owner-control.mjs';
import { encodeNativePushFrame } from './native-source.mjs';

let temporary, tls;
const native = { version: 1, algorithm: 'rawX25519', installationID: 'fixture-native', keyID: 'fixture-native-key', publicKey: Buffer.alloc(32, 31).toString('base64') };
const rsa = generateKeyPairSync('rsa', { modulusLength: 2048 }).privateKey.export({ type: 'pkcs8', format: 'pem' });
before(() => {
  temporary = mkdtempSync('/tmp/cmux-helper-runtime-tls-');
  writeFileSync(join(temporary, 'openssl.cnf'), '[req]\ndistinguished_name=dn\nx509_extensions=ext\nprompt=no\n[dn]\nCN=localhost\n[ext]\nsubjectAltName=DNS:localhost,IP:127.0.0.1\n');
  const built = spawnSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-config', join(temporary, 'openssl.cnf'),
    '-keyout', join(temporary, 'key.pem'), '-out', join(temporary, 'cert.pem')], { timeout: 10000, stdio: 'ignore' });
  assert.equal(built.status, 0); tls = { key: readFileSync(join(temporary, 'key.pem')), cert: readFileSync(join(temporary, 'cert.pem')) };
});
after(() => rmSync(temporary, { recursive: true, force: true }));
function fixture(t) {
  const root = mkdtempSync('/tmp/cmux-runtime-');
  const put = (name, bytes) => { const path = join(root, name); writeFileSync(path, bytes, { mode: 0o600 }); return path; };
  const x = generateKeyPairSync('x25519');
  const helper = { version: 1, algorithm: 'rawX25519', installationID: 'fixture-helper', keyID: 'fixture-helper-key',
    publicKey: x.publicKey.export({ type: 'spki', format: 'der' }).subarray(-32).toString('base64') };
  const c = { version: 1, stateDirectory: join(root, 'state'), helper, nativeDescriptorPath: put('native.json', JSON.stringify(native)),
    keys: { sender: put('sender.key', x.privateKey.export({ type: 'pkcs8', format: 'der' }).subarray(-32)),
      registrations: put('registrations.key', Buffer.alloc(32, 3)), source: put('source.key', Buffer.alloc(32, 4)), outbox: put('outbox.key', Buffer.alloc(32, 5)) },
    tls: { key: put('tls.key', tls.key), cert: put('tls.crt', tls.cert) },
    provider: { path: put('provider.json', JSON.stringify({ type: 'service_account', project_id: 'cmux-fixture',
      client_email: 'push@cmux-fixture.iam.gserviceaccount.com', private_key_id: 'public-fixture', private_key: rsa })),
      projectID: 'cmux-fixture', accountEmail: 'push@cmux-fixture.iam.gserviceaccount.com', sender: '123456' },
    clients: [{ phoneBuildID: 'android.fixture', application: 'fixture-app' }], endpoint: 'https://127.0.0.1:0/v1/push/enroll',
    listener: { host: '127.0.0.1', port: 0 }, sealerExecutable: '/usr/bin/false' };
  const path = put('config.json', JSON.stringify(c));
  const input = new PassThrough(), output = new PassThrough(); input.on('error', () => {}); output.on('error', () => {});
  const runtime = new NativePushHelperRuntime({ configurationPath: path, input, output,
    fetch: () => assert.fail('No provider request is authorized by fixture registration/config alone') });
  t.after(async () => { await runtime.stop(); input.destroy(); output.destroy(); rmSync(root, { recursive: true, force: true }); });
  return { root, c, path, input, output, runtime, save: () => writeFileSync(path, JSON.stringify(c)) };
}
function source(input, output) {
  const key = Buffer.alloc(32, 55), sessionID = randomUUID(); let sequence = 0, buffer = '', waiter;
  output.on('data', chunk => {
    buffer += chunk.toString(); const end = buffer.indexOf('\n'); if (end < 0) return;
    const receipt = JSON.parse(buffer.slice(0, end)); buffer = buffer.slice(end + 1); const done = waiter; waiter = null; done?.(receipt);
  });
  const write = frame => new Promise(resolve => { waiter = resolve; input.write(JSON.stringify(frame) + '\n'); });
  return { bootstrap: () => write({ type: 'bootstrap', version: 1, sessionID, key: key.toString('base64') }),
    send: body => write(encodeNativePushFrame({ sessionID, key, sequence: ++sequence, body })) };
}
function host() {
  const now = Date.now();
  return { type: 'host', snapshot: { state: 'ready', epoch: 'fixture-epoch', observedAt: now, validUntil: now + 30_000,
    authority: { accountID: 'fixture-account', teamID: null, macDeviceID: 'fixture-mac', macInstanceTag: 'fixture-tag', macBuildID: 'fixture-build' },
    settings: { forwardingEnabled: true, mode: 'always', admission: 'allowed', hideContent: false } } };
}
test('production composition issues private local offers only after native bootstrap/authority and shuts down on EOF', async t => {
  const f = fixture(t), wire = source(f.input, f.output);
  const ready = await f.runtime.start();
  assert.equal(lstatSync(ready.controlPath).mode & 0o777, 0o600);
  await assert.rejects(requestPushOwner({ path: ready.controlPath, action: 'offer', value: 'android.fixture' }));
  assert.equal((await wire.bootstrap()).kind, 'ready'); assert.equal((await wire.send(host())).kind, 'state-updated');
  const offer = await requestPushOwner({ path: ready.controlPath, action: 'offer', value: 'android.fixture' });
  assert.equal(offer.mac.accountID, 'fixture-account'); assert.deepEqual(offer.native, native);
  assert.deepEqual(offer.helper, f.c.helper); assert.equal(offer.endpoint, ready.endpoint);
  await requestPushOwner({ path: ready.controlPath, action: 'cancel', value: offer.offerID });
  await wire.send({ type: 'invalidate' });
  await assert.rejects(requestPushOwner({ path: ready.controlPath, action: 'offer', value: 'android.fixture' }));
  f.input.end(); await f.runtime.finished;
  assert.equal(existsSync(ready.controlPath), false);
});
test('sender public key and provisioned provider identity mismatch fail before binding storage/listeners', async t => {
  for (const mutate of [c => c.helper.publicKey = native.publicKey,
    c => c.provider.accountEmail = 'other@cmux-fixture.iam.gserviceaccount.com']) {
    const f = fixture(t); mutate(f.c); f.save();
    await assert.rejects(f.runtime.start(), { message: 'Push helper unavailable' });
    assert.equal(existsSync(f.c.stateDirectory), false); await f.runtime.finished;
  }
});
test('existing owner socket is retained and partial helper startup is closed', async t => {
  const f = fixture(t), path = join(f.c.stateDirectory, 'control', 'owner.sock');
  mkdirSync(join(f.c.stateDirectory, 'control'), { recursive: true, mode: 0o700 });
  const server = createServer(socket => { socket.on('error', () => {}); socket.on('data', () => socket.end('{"ok":true,"result":"original-owner"}\n')); });
  server.listen(path); await once(server, 'listening');
  const { chmodSync } = await import('node:fs'); chmodSync(path, 0o600);
  t.after(() => new Promise(resolve => server.close(resolve)));
  await assert.rejects(f.runtime.start(), { message: 'Push helper unavailable' });
  assert.equal(await requestPushOwner({ path, action: 'offer', value: 'android.fixture' }), 'original-owner');
});
test('native descriptor is reloaded from the trusted private owner file for every offer', async t => {
  const f = fixture(t), wire = source(f.input, f.output), ready = await f.runtime.start();
  await wire.bootstrap(); await wire.send(host());
  await requestPushOwner({ path: ready.controlPath, action: 'offer', value: 'android.fixture' });
  writeFileSync(f.c.nativeDescriptorPath, JSON.stringify(f.c.helper));
  await assert.rejects(requestPushOwner({ path: ready.controlPath, action: 'offer', value: 'android.fixture' }));
});
test('stop before startup is passive and stop during startup fences the partial service', async t => {
  const f = fixture(t); await f.runtime.stop();
  assert.equal(existsSync(f.c.stateDirectory), false); await assert.rejects(f.runtime.start());
  const g = fixture(t); const opening = g.runtime.start(); const failed = assert.rejects(opening);
  await g.runtime.stop(); await failed; await g.runtime.finished;
  assert.equal(existsSync(join(g.c.stateDirectory, 'control', 'owner.sock')), false);
});
test('the production CLI keeps stdout as private receipts and exits/reaps after source EOF', async t => {
  const f = fixture(t);
  const child = spawn(process.execPath, ['scripts/cmux-push-helper.mjs', '--config', f.path], { stdio: ['pipe', 'pipe', 'pipe'], env: { PATH: '/usr/bin:/bin' } });
  const closed = once(child, 'close'); let error = ''; child.stderr.on('data', chunk => { error += chunk; });
  child.stdin.on('error', () => {});
  t.after(async () => { if (child.exitCode === null) child.kill('SIGKILL'); await closed; });
  const wire = source(child.stdin, child.stdout);
  assert.equal((await wire.bootstrap()).kind, 'ready'); assert.equal((await wire.send(host())).kind, 'state-updated');
  const result = await requestPushOwner({ path: join(f.c.stateDirectory, 'control', 'owner.sock'), action: 'offer', value: 'android.fixture' });
  assert.equal(result.mac.accountID, 'fixture-account');
  child.stdin.end(); const [code, signal] = await closed;
  assert.equal(code, 0, error); assert.equal(signal, null);
  const diagnostics = error.replace(/^\(node:\d+\) ExperimentalWarning: SQLite is an experimental feature and might change at any time\n\(Use `node --trace-warnings \.\.\.` to show where the warning was created\)\n$/, '');
  assert.equal(diagnostics, '');
  assert.equal(existsSync(join(f.c.stateDirectory, 'control', 'owner.sock')), false);
});
test('a replacement production helper recovers its stale socket after the native owner kills/reaps its predecessor', async t => {
  const f = fixture(t), path = join(f.c.stateDirectory, 'control', 'owner.sock');
  async function launch() {
    const child = spawn(process.execPath, ['scripts/cmux-push-helper.mjs', '--config', f.path], { stdio: ['pipe', 'pipe', 'pipe'], env: { PATH: '/usr/bin:/bin' } });
    const closed = once(child, 'close'); let diagnostics = '';
    child.stderr.on('data', bytes => { diagnostics += bytes; }); child.stdin.on('error', () => {});
    t.after(async () => { if (child.exitCode === null) child.kill('SIGKILL'); await closed; });
    const wire = source(child.stdin, child.stdout);
    await Promise.race([wire.bootstrap(), closed.then(() => { throw Error(`Replacement exited before native bootstrap: ${diagnostics.includes('Push helper unavailable') ? 'helper unavailable' : 'unexpected exit'}`); })]);
    await wire.send(host());
    const offer = await requestPushOwner({ path, action: 'offer', value: 'android.fixture' });
    assert.equal(offer.mac.accountID, 'fixture-account');
    return { child, closed };
  }
  const original = await launch(); original.child.kill('SIGKILL');
  const [, signal] = await original.closed; assert.equal(signal, 'SIGKILL');
  assert.equal(existsSync(path), true);
  const replacement = await launch(); replacement.child.stdin.end();
  assert.equal((await replacement.closed)[0], 0); assert.equal(existsSync(path), false);
});
