import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync, rmSync, chmodSync, lstatSync, existsSync, readdirSync, symlinkSync } from 'node:fs';
import { basename, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { generateKeyPairSync } from 'node:crypto';
import { provisionPushRuntime, verifyRuntimeTls } from './runtime-setup.mjs';

let certificateDirectory, tls;
const hostname = 'fixture.example.net', project = 'cmux-fixture', sender = '123456';
const packages = ['io.github.docmorphic.cmuxapp', 'io.github.docmorphic.cmuxapp.debug'];
const error = { message: 'Private push runtime setup unavailable' };
before(() => {
  certificateDirectory = mkdtempSync('/tmp/cmux-setup-cert-');
  const openssl = args => assert.equal(spawnSync('openssl', args, {
    cwd: certificateDirectory, timeout: 10_000, stdio: 'ignore' }).status, 0);
  writeFileSync(join(certificateDirectory, 'root.cnf'), '[req]\nprompt=no\ndistinguished_name=dn\nx509_extensions=ext\n[dn]\nCN=Isolated fixture CA\n[ext]\nbasicConstraints=critical,CA:true\nkeyUsage=critical,keyCertSign,cRLSign\n');
  writeFileSync(join(certificateDirectory, 'leaf.cnf'), `[req]\nprompt=no\ndistinguished_name=dn\n[dn]\nCN=${hostname}\n`);
  writeFileSync(join(certificateDirectory, 'leaf.ext'), `basicConstraints=critical,CA:false\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:${hostname}\n`);
  openssl(['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-config', 'root.cnf', '-keyout', 'root.key', '-out', 'root.pem']);
  openssl(['req', '-new', '-newkey', 'rsa:2048', '-nodes', '-config', 'leaf.cnf', '-keyout', 'leaf.key', '-out', 'leaf.csr']);
  openssl(['x509', '-req', '-in', 'leaf.csr', '-CA', 'root.pem', '-CAkey', 'root.key', '-CAcreateserial', '-days', '1', '-extfile', 'leaf.ext', '-out', 'leaf.pem']);
  tls = { key: readFileSync(join(certificateDirectory, 'leaf.key')), cert: readFileSync(join(certificateDirectory, 'leaf.pem')),
    root: readFileSync(join(certificateDirectory, 'root.pem')) };
});
after(() => rmSync(certificateDirectory, { recursive: true, force: true }));
function fixture(t) {
  const root = mkdtempSync('/tmp/cmux-setup-'); chmodSync(root, 0o700);
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const put = (name, bytes, mode = 0o600) => {
    const path = join(root, name); writeFileSync(path, bytes, { mode }); return path;
  };
  const firebase = { project_info: { project_id: project, project_number: sender }, client: packages.map((name, index) => ({
    client_info: { android_client_info: { package_name: name }, mobilesdk_app_id: `1:${sender}:android:abc${index}` } })) };
  const provider = { type: 'service_account', project_id: project,
    client_email: `push@${project}.iam.gserviceaccount.com`, private_key: tls.key.toString() };
  const input = { keysDirectory: join(root, 'keys'), firebasePath: put('firebase.json', JSON.stringify(firebase)),
    providerPath: put('provider.json', JSON.stringify(provider)), tlsKeyPath: put('tls.key', tls.key), tlsCertPath: put('tls.pem', tls.cert),
    hostname, listenHost: '127.0.0.1', port: 8443, sealerExecutable: put('sealer', '#!/bin/sh\nexit 1\n', 0o700),
    output: join(root, 'runtime', 'config.json') };
  return { root, input, firebase, provider, put, options: { roots: [tls.root] } };
}
function reverse(value) {
  if (Array.isArray(value)) return value.map(reverse);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).reverse().map(([key, v]) => [key, reverse(v)]));
  return value;
}
function unchangedKeys(path) {
  return Object.fromEntries(readdirSync(path).sort().map(name => [name, readFileSync(join(path, name)).toString('base64')]));
}

test('creates private matching runtime metadata without authority, network, state or native descriptor', t => {
  const f = fixture(t), originalFetch = globalThis.fetch; let calls = 0;
  globalThis.fetch = () => { calls++; throw Error('Unexpected provider request'); };
  t.after(() => { globalThis.fetch = originalFetch; });
  const { created, configuration: c } = provisionPushRuntime(f.input, f.options);
  assert.equal(created, true); assert.equal(calls, 0);
  assert.equal(lstatSync(f.input.output).mode & 0o777, 0o600);
  assert.equal(lstatSync(join(f.root, 'runtime')).mode & 0o777, 0o700);
  assert.deepEqual(JSON.parse(readFileSync(f.input.output)), c);
  assert.equal(c.endpoint, `https://${hostname}:8443/v1/push/enroll`);
  assert.deepEqual(c.clients, packages.map((phoneBuildID, index) => ({ phoneBuildID, application: `1:${sender}:android:abc${index}` })));
  assert.deepEqual(c.provider, { path: f.input.providerPath, projectID: project, accountEmail: f.provider.client_email, sender });
  assert.equal(existsSync(c.stateDirectory), false); assert.equal(existsSync(c.nativeDescriptorPath), false);
  assert.deepEqual(Object.keys(c).sort(), ['clients', 'endpoint', 'helper', 'keys', 'listener', 'nativeDescriptorPath', 'provider', 'sealerExecutable', 'stateDirectory', 'tls', 'version']);
  const publicFile = readFileSync(f.input.output, 'utf8');
  assert.equal(publicFile.includes('PRIVATE KEY'), false);
  for (const path of Object.values(c.keys)) assert.equal(publicFile.includes(readFileSync(path).toString('base64')), false);
});

test('rerun preserves key identities, configuration bytes and inode including reordered nested JSON', t => {
  const f = fixture(t), first = provisionPushRuntime(f.input, f.options), keys = unchangedKeys(f.input.keysDirectory);
  writeFileSync(f.input.output, JSON.stringify(reverse(first.configuration)));
  const bytes = readFileSync(f.input.output), inode = lstatSync(f.input.output).ino;
  const second = provisionPushRuntime(f.input, f.options);
  assert.equal(second.created, false); assert.deepEqual(second.configuration, first.configuration);
  assert.deepEqual(readFileSync(f.input.output), bytes); assert.equal(lstatSync(f.input.output).ino, inode);
  assert.deepEqual(unchangedKeys(f.input.keysDirectory), keys);
});

test('refuses changed existing configuration without rotating or overwriting any existing material', t => {
  const f = fixture(t); provisionPushRuntime(f.input, f.options);
  const bytes = readFileSync(f.input.output), keys = unchangedKeys(f.input.keysDirectory);
  assert.throws(() => provisionPushRuntime({ ...f.input, port: 8444 }, f.options), error);
  assert.deepEqual(readFileSync(f.input.output), bytes); assert.deepEqual(unchangedKeys(f.input.keysDirectory), keys);
});

test('filesystem aliases cannot place runtime configuration inside the existing owner key directory', t => {
  const f = fixture(t); provisionPushRuntime(f.input, f.options);
  const keys = unchangedKeys(f.input.keysDirectory), alias = join(f.root, 'ancestor');
  symlinkSync('/tmp', alias);
  const output = join(alias, basename(f.root), 'keys', 'config.json');
  assert.throws(() => provisionPushRuntime({ ...f.input, output }, f.options), error);
  assert.deepEqual(unchangedKeys(f.input.keysDirectory), keys);
});

test('rejects mismatched project, sender, missing/duplicate variant and invalid provider before key creation', t => {
  const mutations = [f => f.provider.project_id = 'wrong-project', f => f.provider.client_email = 'push@wrong-project.iam.gserviceaccount.com',
    f => f.provider.private_key = 'INVALID', f => f.firebase.project_info.project_number = '654321',
    f => f.firebase.client.pop(), f => f.firebase.client.push(f.firebase.client[0])];
  for (const mutate of mutations) {
    const f = fixture(t); mutate(f);
    writeFileSync(f.input.firebasePath, JSON.stringify(f.firebase)); writeFileSync(f.input.providerPath, JSON.stringify(f.provider));
    assert.throws(() => provisionPushRuntime(f.input, f.options), error);
    assert.equal(existsSync(f.input.keysDirectory), false); assert.equal(existsSync(f.input.output), false);
  }
});

test('TLS verifies a matching live server chain and refuses hostname, key, expiry, trust and CA leaf errors', () => {
  const valid = { key: tls.key, cert: tls.cert, hostname, roots: [tls.root] };
  verifyRuntimeTls(valid); verifyRuntimeTls({ ...valid, cert: Buffer.concat([tls.cert, tls.root]) });
  const otherKey = generateKeyPairSync('rsa', { modulusLength: 2048 }).privateKey.export({ type: 'pkcs8', format: 'pem' });
  for (const changes of [{ hostname: 'other.example.net' }, { key: otherKey }, { now: Date.now() + 3 * 86_400_000 },
    { now: 0 }, { roots: [] }, { cert: tls.root }, { cert: Buffer.from('invalid') }])
    assert.throws(() => verifyRuntimeTls({ ...valid, ...changes }), error);
  assert.throws(() => verifyRuntimeTls({ key: tls.key, cert: tls.cert, hostname }), error);
});

test('unsafe certificate/private files and executable are rejected without creating runtime or owner keys', t => {
  const mutations = [f => chmodSync(f.input.providerPath, 0o644), f => chmodSync(f.input.tlsKeyPath, 0o644),
    f => { rmSync(f.input.tlsCertPath); symlinkSync(f.input.tlsKeyPath, f.input.tlsCertPath); },
    f => chmodSync(f.input.sealerExecutable, 0o600), f => chmodSync(f.input.sealerExecutable, 0o722),
    f => { rmSync(f.input.sealerExecutable); symlinkSync(f.input.tlsKeyPath, f.input.sealerExecutable); },
    f => writeFileSync(f.input.tlsCertPath, 'INVALID')];
  for (const mutate of mutations) {
    const f = fixture(t); mutate(f);
    assert.throws(() => provisionPushRuntime(f.input, f.options), error);
    assert.equal(existsSync(f.input.output), false); assert.equal(existsSync(f.input.keysDirectory), false);
  }
});

test('rejects ambiguous addresses, overlong sockets, reserved/native output and overlapping key directories', t => {
  const changes = [{ listenHost: '0.0.0.0' }, { listenHost: '::' }, { listenHost: '0:0:0:0:0:0:0:0' },
    { listenHost: '::ffff:0.0.0.0' }, { listenHost: hostname }, { port: 0 }, { port: 65536 },
    { hostname: '-bad.example.net' }, { hostname: 'a'.repeat(64) + '.example.net' }, { hostname: 'fixture.example.net/secret' }];
  for (const changed of changes) {
    const f = fixture(t); assert.throws(() => provisionPushRuntime({ ...f.input, ...changed }, f.options), error);
    assert.equal(existsSync(f.input.keysDirectory), false);
  }
  for (const mutate of [f => f.input.output = join(f.root, 'x'.repeat(80), 'config.json'),
    f => f.input.output = join(f.root, 'runtime', 'native.json'), f => f.input.keysDirectory = join(f.root, 'runtime'),
    f => f.input.keysDirectory = join(f.root, 'runtime', 'nested'), f => f.input.keysDirectory = f.root,
    f => f.input.output = f.input.providerPath]) {
    const f = fixture(t); mutate(f); assert.throws(() => provisionPushRuntime(f.input, f.options), error);
    assert.equal(existsSync(join(f.root, 'runtime')), false);
  }
});

test('port 443 omits endpoint port; CLI rejects missing inputs with coarse diagnostics', t => {
  const f = fixture(t); f.input.port = 443;
  assert.equal(provisionPushRuntime(f.input, f.options).configuration.endpoint, `https://${hostname}/v1/push/enroll`);
  const child = spawnSync(process.execPath, ['scripts/provision-push-runtime.mjs', '--hostname', 'PRIVATE-MARKER'], { encoding: 'utf8' });
  assert.equal(child.status, 1); assert.equal(child.stdout, '');
  assert.match(child.stderr, /^Private push runtime setup unavailable\./); assert.equal(child.stderr.includes('PRIVATE-MARKER'), false);
});
