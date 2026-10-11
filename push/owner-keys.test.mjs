import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, chmodSync, rmSync, readFileSync, lstatSync, writeFileSync, symlinkSync, linkSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { provisionPushOwnerKeys } from './owner-keys.mjs';

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'push-keys-')); chmodSync(root, 0o700);
  t.after(() => rmSync(root, { recursive: true, force: true }));
  return { root, path: join(root, 'keys') };
}
test('provisions independent private keys and preserves identities and bytes on repeated setup', t => {
  const { path } = fixture(t), first = provisionPushOwnerKeys(path);
  assert.equal(first.created, true); assert.equal(lstatSync(path).mode & 0o777, 0o700);
  const before = Object.fromEntries(Object.entries(first.keys).map(([name, file]) => {
    assert.equal(lstatSync(file).mode & 0o777, 0o600); const bytes = readFileSync(file); assert.equal(bytes.length, 32);
    return [name, bytes];
  }));
  assert.equal(new Set(Object.values(before).map(v => v.toString('hex'))).size, 4);
  assert.equal(lstatSync(join(path, 'helper.json')).mode & 0o777, 0o600);
  const second = provisionPushOwnerKeys(path); assert.equal(second.created, false);
  assert.deepEqual(second.helper, first.helper); assert.deepEqual(second.keys, first.keys);
  for (const [name, file] of Object.entries(second.keys)) assert.deepEqual(readFileSync(file), before[name]);
});
test('rejects changed descriptor without replacing existing storage keys', t => {
  const { path } = fixture(t), first = provisionPushOwnerKeys(path), old = readFileSync(first.keys.source);
  writeFileSync(join(path, 'helper.json'), JSON.stringify({ ...first.helper, publicKey: Buffer.alloc(32).toString('base64') }));
  assert.throws(() => provisionPushOwnerKeys(path), /unavailable/);
  assert.deepEqual(readFileSync(first.keys.source), old);
});
test('rejects nonprivate parent before creating material', t => {
  const { root, path } = fixture(t); chmodSync(root, 0o755);
  assert.throws(() => provisionPushOwnerKeys(path), /unavailable/); assert.deepEqual(readdirSync(root), []);
});
for (const kind of ['symlink-directory', 'symlink-key', 'hardlink-key', 'public-key', 'incomplete']) {
  test(`preserves and refuses unsafe or incomplete material: ${kind}`, t => {
    const { root, path } = fixture(t), initial = provisionPushOwnerKeys(path), old = readFileSync(initial.keys.source);
    if (kind === 'symlink-directory') symlinkSync(path, join(root, 'alias'));
    if (kind === 'symlink-key') { rmSync(initial.keys.sender); symlinkSync(initial.keys.source, initial.keys.sender); }
    if (kind === 'hardlink-key') { rmSync(initial.keys.sender); linkSync(initial.keys.source, initial.keys.sender); }
    if (kind === 'public-key') chmodSync(initial.keys.sender, 0o644);
    if (kind === 'incomplete') rmSync(initial.keys.sender);
    assert.throws(() => provisionPushOwnerKeys(kind === 'symlink-directory' ? join(root, 'alias') : path), /unavailable/);
    assert.deepEqual(readFileSync(initial.keys.source), old);
  });
}
