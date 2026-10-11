// Public synthetic keys only; prepares/verifies the real helper composition.
import assert from 'node:assert/strict';
import { generateKeyPairSync } from 'node:crypto';
import { writeFileSync, readFileSync, lstatSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { requestPushOwner } from '../push/owner-control.mjs';
import { PushSourceJournal } from '../push/source-journal.mjs';
const [mode, root] = process.argv.slice(2), config = join(root, 'config.json');
if (mode === 'prepare') {
  const put = (name, value) => { const path = join(root, name); writeFileSync(path, value, { mode: 0o600 }); return path; };
  const sender = generateKeyPairSync('x25519'), rsa = generateKeyPairSync('rsa', { modulusLength: 2048 }).privateKey;
  put('openssl.cnf', '[req]\ndistinguished_name=dn\nx509_extensions=ext\nprompt=no\n[dn]\nCN=localhost\n[ext]\nsubjectAltName=DNS:localhost,IP:127.0.0.1\n');
  execFileSync('/usr/bin/openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-config', join(root, 'openssl.cnf'),
    '-keyout', join(root, 'tls.key'), '-out', join(root, 'tls.crt')], { timeout: 10000, stdio: 'ignore' });
  // OpenSSL's public certificate is copied into the same private-file policy.
  put('tls.crt', readFileSync(join(root, 'tls.crt')));
  const { chmodSync } = await import('node:fs'); chmodSync(join(root, 'tls.crt'), 0o600);
  chmodSync(join(root, 'tls.key'), 0o600);
  put('config.json', JSON.stringify({ version: 1, stateDirectory: join(root, 'state'),
    helper: { version: 1, algorithm: 'rawX25519', installationID: 'fixture-helper', keyID: 'fixture-helper-key',
      publicKey: sender.publicKey.export({ format: 'der', type: 'spki' }).subarray(-32).toString('base64') },
    nativeDescriptorPath: join(root, 'native.json'),
    keys: { sender: put('sender.key', sender.privateKey.export({ format: 'der', type: 'pkcs8' }).subarray(-32)),
      registrations: put('registrations.key', Buffer.alloc(32, 3)), source: put('source.key', Buffer.alloc(32, 4)), outbox: put('outbox.key', Buffer.alloc(32, 5)) },
    tls: { key: join(root, 'tls.key'), cert: join(root, 'tls.crt') },
    provider: { path: put('provider.json', JSON.stringify({ type: 'service_account', project_id: 'cmux-fixture',
      client_email: 'push@cmux-fixture.iam.gserviceaccount.com', private_key_id: 'fixture', private_key: rsa.export({ format: 'pem', type: 'pkcs8' }) })),
      projectID: 'cmux-fixture', accountEmail: 'push@cmux-fixture.iam.gserviceaccount.com', sender: '123456' },
    clients: [{ phoneBuildID: 'android.fixture', application: 'fixture-app' }], endpoint: 'https://127.0.0.1:0/v1/push/enroll',
    listener: { host: '127.0.0.1', port: 0 }, sealerExecutable: '/usr/bin/false' }));
  console.log('Prepared public native-owner fixture');
} else if (mode === 'offer' || mode === 'rotated-offer') {
  const offer = await requestPushOwner({ path: join(root, 'state/control/owner.sock'), action: 'offer', value: 'android.fixture' });
  assert.equal(offer.mac.accountID, 'fixture-account'); assert.equal(offer.mac.macBuildID, 'fixture-build');
  const rotated = mode === 'rotated-offer';
  assert.equal(offer.native.installationID, rotated ? 'fixture-native-rotated' : 'fixture-native');
  assert.equal(offer.native.keyID, rotated ? 'fixture-native-key-rotated' : 'fixture-native-key');
  assert.equal(offer.native.publicKey, Buffer.alloc(32, rotated ? 10 : 9).toString('base64'));
  assert.equal(lstatSync(join(root, 'native.json')).mode & 0o777, 0o600);
  assert.deepEqual(Object.keys(JSON.parse(readFileSync(join(root, 'native.json')))).sort(), ['algorithm', 'installationID', 'keyID', 'publicKey', 'version']);
  console.log('Native authority and published public descriptor verified through owner control');
} else if (mode === 'verify') {
  const journal = new PushSourceJournal({ directory: join(root, 'state/source'), key: Buffer.alloc(32, 4) });
  try { assert.ok(Object.values(journal.status().counts).reduce((a, b) => a + b, 0) >= 1); }
  finally { journal.close(); }
  assert.equal(readFileSync(join(root, 'state/source/source.sqlite')).includes(Buffer.from('native owner λ')), false);
  console.log('Actual native capture persists encrypted after helper retirement');
} else if (['verify-recovery', 'verify-full-recovery'].includes(mode)) {
  const journal = new PushSourceJournal({ directory: join(root, 'state/source'), key: Buffer.alloc(32, 4) });
  try {
    const expected = mode === 'verify-full-recovery' ? 128 : 1;
    assert.equal(Object.values(journal.status().counts).reduce((a, b) => a + b, 0), expected);
    const original = JSON.parse(readFileSync(join(root, 'original-public-fixture.json'))).request;
    assert.equal(original.sourceEpoch, 'native-incarnation-first');
    assert.equal(original.event.correlationID, '00000000-0000-4000-8000-000000000222');
    assert.equal(journal.accept(original).kind, 'duplicate');
    assert.equal(Object.values(journal.status().counts).reduce((a, b) => a + b, 0), expected);
  } finally { journal.close(); }
  console.log(mode === 'verify-full-recovery' ?
    'Actual encrypted helper journal retains 128 original identities; exact first replay is duplicate, with the new capture backpressured' :
    'Actual encrypted helper journal contains one original identity; exact replay is duplicate, without epoch rewrite');
} else throw Error('Unknown public fixture operation');
