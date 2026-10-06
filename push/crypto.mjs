import { spawn } from 'node:child_process';
import { isAbsolute } from 'node:path';

export class PushEncryptionError extends Error {
  constructor() { super('Push encryption failed'); }
}

/** Explicit trusted executable and provisioned key only. Secrets travel on stdin, never argv or environment. */
export function cryptoKitSealer({ executable, senderKeyID, senderPublicKey, privateKey }) {
  if (!isAbsolute(executable ?? '') || typeof senderKeyID !== 'string' || !senderKeyID ||
      typeof senderPublicKey !== 'string' || typeof privateKey !== 'function') throw new PushEncryptionError();
  return async ({ recipient, publicKey, plaintext, senderPublicKey: expectedSender }) => {
    // Snapshot authority/content before awaiting the host's credential store.
    let target; let body;
    try {
      target = JSON.parse(JSON.stringify(recipient));
      if (target.senderKeyID !== senderKeyID || expectedSender !== senderPublicKey || typeof publicKey !== 'string' ||
          !Buffer.isBuffer(plaintext) || plaintext.length < 1 || plaintext.length > 16_368)
        throw new PushEncryptionError();
      body = plaintext.toString('base64');
    } catch { throw new PushEncryptionError(); }
    let key; let input;
    try {
      const supplied = await privateKey();
      if (!Buffer.isBuffer(supplied) || supplied.length !== 32) throw new PushEncryptionError();
      key = Buffer.from(supplied); // Caller retains ownership of its credential buffer.
      input = Buffer.from(JSON.stringify({ recipient: target, recipientPublicKey: publicKey,
        senderPrivateKey: key.toString('base64'), senderPublicKey, plaintext: body }));
      if (input.length > 65_536) throw new PushEncryptionError();
      return await new Promise((resolve, reject) => {
        const child = spawn(executable, [], { stdio: ['pipe', 'pipe', 'ignore'],
          // Don't propagate unrelated host credentials or dynamic-loader overrides.
          env: { PATH: '/usr/bin:/bin' } });
        let output = []; let size = 0; let failed = false;
        const fail = () => { failed = true; child.kill('SIGKILL'); };
        const timer = setTimeout(fail, 10_000);
        child.on('error', fail); child.stdin.on('error', fail);
        child.stdout.on('data', chunk => {
          size += chunk.length;
          if (size > 32_768) fail(); else output.push(chunk);
        });
        child.on('close', code => {
          clearTimeout(timer);
          if (failed || code !== 0) { reject(new PushEncryptionError()); return; }
          try { resolve(JSON.parse(Buffer.concat(output).toString('utf8'))); }
          catch { reject(new PushEncryptionError()); }
        });
        child.stdin.end(input);
      });
    } catch { throw new PushEncryptionError(); }
    finally { key?.fill(0); input?.fill(0); }
  };
}
