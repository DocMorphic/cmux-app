import test, { before } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { cryptoKitSealer } from './crypto.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const vectors = JSON.parse(readFileSync(join(root, 'app/src/test/resources/push/apple-hpke-v2.json')));
const executable = join(root, 'build/push/cmux-push-seal');
const mac = process.platform === 'darwin';
before(() => {
  if (!mac) return;
  const result = spawnSync('/bin/sh', [join(root, 'scripts/build-push-seal.sh')], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
});
function fixture(index = 0, overrides = {}) {
  const v = vectors[index];
  const key = Buffer.from(v.senderPrivateKeyBase64, 'base64');
  const { version, encapsulatedKey, ciphertext, ...recipient } = v.envelope;
  return { key, args: { recipient, publicKey: v.recipientPublicKeyBase64, senderPublicKey: v.senderPublicKeyBase64,
    plaintext: Buffer.from(`Generated Android push fixture λ 中 ${index}`) },
  seal: cryptoKitSealer({ executable, senderKeyID: recipient.senderKeyID, senderPublicKey: v.senderPublicKeyBase64,
    privateKey: async () => key, ...overrides }) };
}

test('missing executable and credential failures expose only coarse diagnostics', async () => {
  const a = fixture(0, { executable: '/no-such-cmux-push-executable' });
  await assert.rejects(a.seal(a.args), { message: 'Push encryption failed' });
  const b = fixture(0, { privateKey: async () => { throw new Error('secret diagnostic'); } });
  await assert.rejects(b.seal(b.args), { message: 'Push encryption failed' });
});

test('sender descriptor mismatch is rejected before requesting its private key', async () => {
  const f = fixture(0, { privateKey: () => assert.fail('must not request key') });
  f.args.senderPublicKey = Buffer.alloc(32).toString('base64');
  await assert.rejects(f.seal(f.args), { message: 'Push encryption failed' });
});

test('CryptoKit encrypts with fresh encapsulation and preserves caller-owned key/plaintext', { skip: !mac }, async () => {
  const f = fixture(); const keyBefore = Buffer.from(f.key), textBefore = Buffer.from(f.args.plaintext);
  const a = await f.seal(f.args), b = await f.seal(f.args);
  assert.equal(a.version, 2); assert.deepEqual(a.tuple, f.args.recipient.tuple);
  assert.notEqual(a.encapsulatedKey, b.encapsulatedKey); assert.notEqual(a.ciphertext, b.ciphertext);
  assert.deepEqual(f.key, keyBefore); assert.deepEqual(f.args.plaintext, textBefore);
});

test('CryptoKit rejects low-order recipients and mismatched sender key material without leaking input', { skip: !mac }, async () => {
  const f = fixture(); f.args.publicKey = Buffer.alloc(32).toString('base64');
  await assert.rejects(f.seal(f.args), { message: 'Push encryption failed' });
  const g = fixture(0, { privateKey: async () => Buffer.alloc(32, 8) });
  await assert.rejects(g.seal(g.args), { message: 'Push encryption failed' });
});

test('pinned upstream decryptor opens ordinary and Unicode/omitted-field envelopes from the production adapter',
  { skip: !mac || !process.env.CMUX_PUSH_REFERENCE_FIXTURE }, async () => {
    const directory = mkdtempSync(join(tmpdir(), 'cmux-seal-interop-'));
    try {
      const envelopes = [];
      for (let i = 0; i < vectors.length; i++) { const f = fixture(i); envelopes.push(await f.seal(f.args)); }
      const path = join(directory, 'public-fixture-envelopes.json'); writeFileSync(path, JSON.stringify(envelopes));
      const result = spawnSync(process.env.CMUX_PUSH_REFERENCE_FIXTURE, ['--verify', path], { encoding: 'utf8' });
      assert.equal(result.status, 0, result.stderr);
      assert.match(result.stdout, /opened both/);
    } finally { rmSync(directory, { recursive: true, force: true }); }
  });
