import { createPrivateKey, createPublicKey, randomBytes, randomUUID } from 'node:crypto';
import { constants, lstatSync, mkdirSync, openSync, writeFileSync, fsyncSync, closeSync, readdirSync } from 'node:fs';
import { dirname, isAbsolute, join } from 'node:path';
import { readPrivatePushFile } from './private-credentials.mjs';

const names = ['sender', 'registrations', 'source', 'outbox'];
export class PushOwnerKeyError extends Error {
  constructor() { super('Private push owner keys unavailable'); }
}
function directory(path) {
  const stat = lstatSync(path);
  if (!stat.isDirectory() || stat.uid !== process.getuid() || (stat.mode & 0o077)) throw new PushOwnerKeyError();
}
function publicKey(sender) {
  const der = Buffer.concat([Buffer.from('302e020100300506032b656e04220420', 'hex'), sender]);
  try { return createPublicKey(createPrivateKey({ key: der, format: 'der', type: 'pkcs8' }))
    .export({ format: 'der', type: 'spki' }).subarray(-32).toString('base64'); }
  finally { der.fill(0); }
}

/** Explicit local setup. Existing material is validated and preserved; incomplete
 * or unsafe directories fail closed, never silently rotating encrypted stores. */
export function provisionPushOwnerKeys(path) {
  const owned = [];
  try {
    if (process.platform === 'win32' || !isAbsolute(path ?? '') || path.includes('\0')) throw new PushOwnerKeyError();
    directory(dirname(path));
    let created = false;
    try { mkdirSync(path, { mode: 0o700 }); created = true; }
    catch (error) { if (error.code !== 'EEXIST') throw error; }
    directory(path);
    const put = (name, value) => {
      const fd = openSync(join(path, name), constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600);
      try { writeFileSync(fd, value); fsyncSync(fd); } finally { closeSync(fd); }
    };
    if (created) {
      for (const name of names) {
        const bytes = randomBytes(32); owned.push(bytes); put(`${name}.key`, bytes);
      }
      put('helper.json', JSON.stringify({ version: 1, algorithm: 'rawX25519', installationID: randomUUID(),
        keyID: randomUUID(), publicKey: publicKey(owned[0]) }) + '\n');
      const fd = openSync(path, constants.O_RDONLY);
      try { fsyncSync(fd); } finally { closeSync(fd); }
    }
    if (readdirSync(path).sort().join(',') !== ['helper.json', ...names.map(n => `${n}.key`)].sort().join(','))
      throw new PushOwnerKeyError();
    let loadedSender;
    const keys = Object.fromEntries(names.map(name => {
      const keyPath = join(path, `${name}.key`), bytes = readPrivatePushFile(keyPath, 32); owned.push(bytes);
      if (bytes.length !== 32) throw new PushOwnerKeyError();
      if (name === 'sender') loadedSender = bytes;
      return [name, keyPath];
    }));
    const descriptorBytes = readPrivatePushFile(join(path, 'helper.json'), 4096); owned.push(descriptorBytes);
    const helper = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(descriptorBytes));
    if (!helper || Object.keys(helper).sort().join(',') !== 'algorithm,installationID,keyID,publicKey,version' ||
        helper.version !== 1 || helper.algorithm !== 'rawX25519' ||
        ![helper.installationID, helper.keyID].every(v => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(v)) ||
        helper.installationID === helper.keyID || helper.publicKey !== publicKey(loadedSender))
      throw new PushOwnerKeyError();
    return { helper, keys, created };
  } catch { throw new PushOwnerKeyError(); }
  finally { owned.forEach(bytes => bytes.fill(0)); }
}
