import { createServer, createConnection } from 'node:net';
import { lstatSync, chmodSync, unlinkSync } from 'node:fs';
import { dirname, isAbsolute } from 'node:path';

export class PushOwnerControlError extends Error {
  constructor() { super('Push owner control unavailable'); }
}
function privateParent(path) {
  if (process.platform === 'win32' || !isAbsolute(path ?? '') || Buffer.byteLength(path) > 100) throw new PushOwnerControlError();
  const stat = lstatSync(dirname(path));
  if (!stat.isDirectory() || stat.uid !== process.getuid() || (stat.mode & 0o077)) throw new PushOwnerControlError();
}
async function recoverStaleSocket(path, signal) {
  if (signal.aborted) throw new PushOwnerControlError();
  let original;
  try { original = lstatSync(path, { bigint: true }); }
  catch (error) { if (error.code === 'ENOENT') return; throw new PushOwnerControlError(); }
  if (!original.isSocket() || original.uid !== BigInt(process.getuid()) || (original.mode & 0o077n)) throw new PushOwnerControlError();
  const stale = await new Promise(resolve => {
    const socket = createConnection(path); let settled = false;
    const finish = result => { if (settled) return; settled = true; signal.removeEventListener('abort', cancelled); socket.destroy(); resolve(result); };
    const cancelled = () => finish(false);
    signal.addEventListener('abort', cancelled, { once: true });
    socket.on('connect', () => finish(false));
    socket.on('error', error => finish(error.code === 'ECONNREFUSED' || error.code === 'ENOENT'));
    socket.setTimeout(1000, () => finish(false));
  });
  if (!stale || signal.aborted) throw new PushOwnerControlError();
  let current;
  try { current = lstatSync(path, { bigint: true }); }
  catch (error) { if (error.code === 'ENOENT') return; throw new PushOwnerControlError(); }
  // Never unlink an active/replacement owner or a changed filesystem object.
  if (!current.isSocket() || ['dev', 'ino', 'uid', 'mode', 'birthtimeNs', 'ctimeNs'].some(k => current[k] !== original[k]))
    throw new PushOwnerControlError();
  unlinkSync(path);
}
/** Owner-only Unix socket, separate from phone HTTPS and source pipes. */
export class PushOwnerControl {
  #path; #issue; #cancel; #resume; #server; #sockets = new Set(); #state = 'new'; #stopping; #failed;
  #recovery; #abort = new AbortController();
  constructor({ path, issueOffer, cancelOffer, resumeBlocked, onFailure = () => {} }) {
    if ([issueOffer, cancelOffer, resumeBlocked, onFailure].some(f => typeof f !== 'function')) throw new PushOwnerControlError();
    this.#path = path; this.#issue = issueOffer; this.#cancel = cancelOffer; this.#resume = resumeBlocked;
    this.#failed = onFailure;
  }
  async start() {
    if (this.#state !== 'new') throw new PushOwnerControlError();
    this.#state = 'starting';
    try {
      privateParent(this.#path);
      this.#recovery = recoverStaleSocket(this.#path, this.#abort.signal);
      await this.#recovery;
      if (this.#state !== 'starting') throw new PushOwnerControlError();
      this.#server = createServer(socket => {
        if (this.#state !== 'running') { socket.destroy(); return; }
        this.#sockets.add(socket); socket.once('close', () => this.#sockets.delete(socket));
        socket.on('error', () => {}); socket.setTimeout(3000, () => socket.destroy());
        let bytes = Buffer.alloc(0), completed = false;
        socket.on('data', chunk => {
          if (completed) { socket.destroy(); return; }
          if (bytes.length + chunk.length > 4096) { socket.destroy(); return; }
          bytes = Buffer.concat([bytes, chunk]);
          const end = bytes.indexOf(10); if (end < 0) return;
          completed = true;
          try {
            if (end !== bytes.length - 1 || this.#state !== 'running') throw new PushOwnerControlError();
            const request = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes.subarray(0, end)));
            if (!request || Array.isArray(request) || Object.keys(request).length !== 2 ||
                !['offer', 'cancel', 'resume'].includes(request.action) || typeof request.value !== 'string' ||
                !request.value || request.value.length > 1024) throw new PushOwnerControlError();
            let result;
            if (request.action === 'offer') result = this.#issue(request.value);
            else if (request.action === 'cancel') { this.#cancel(request.value); result = { cancelled: true }; }
            else {
              if (!['credentials', 'rejected'].includes(request.value)) throw new PushOwnerControlError();
              result = { resumed: this.#resume(request.value) };
            }
            const response = JSON.stringify({ ok: true, result });
            if (Buffer.byteLength(response) > 32_768) throw new PushOwnerControlError();
            socket.end(response + '\n');
          } catch { socket.end('{"ok":false,"error":"unavailable"}\n'); }
          finally { bytes.fill(0); }
        });
      });
      this.#server.maxConnections = 4;
      // Errors after startup are observed; the runtime owns shutdown.
      this.#server.on('error', () => { if (this.#state === 'running') { try { this.#failed(); } catch {} } });
      await new Promise((resolve, reject) => {
        this.#server.once('error', reject);
        this.#server.listen(this.#path, () => { this.#server.off('error', reject); resolve(); });
      });
      if (this.#state !== 'starting') throw new PushOwnerControlError();
      chmodSync(this.#path, 0o600); this.#state = 'running';
    } catch { await this.stop(); throw new PushOwnerControlError(); }
  }
  stop() {
    if (this.#stopping) return this.#stopping;
    this.#state = 'closed';
    this.#abort.abort();
    for (const socket of this.#sockets) socket.destroy();
    this.#stopping = (async () => {
      await this.#recovery?.catch(() => {});
      if (this.#server) await new Promise(resolve => this.#server.close(() => resolve()));
    })();
    return this.#stopping;
  }
}

/** Explicit local owner command. Returns the private offer only to the caller. */
export async function requestPushOwner({ path, action, value }) {
  try {
    privateParent(path);
    const stat = lstatSync(path);
    if (!stat.isSocket() || stat.uid !== process.getuid() || (stat.mode & 0o077)) throw new PushOwnerControlError();
    return await new Promise((resolve, reject) => {
      const socket = createConnection(path); let bytes = Buffer.alloc(0), settled = false;
      const fail = () => { if (settled) return; settled = true; socket.destroy(); reject(new PushOwnerControlError()); };
      socket.on('error', fail); socket.setTimeout(4000, fail);
      socket.on('connect', () => socket.write(JSON.stringify({ action, value }) + '\n'));
      socket.on('data', chunk => {
        if (bytes.length + chunk.length > 32_768) { fail(); return; }
        bytes = Buffer.concat([bytes, chunk]);
      });
      socket.on('end', () => {
        if (settled) return;
        try {
          const response = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
          if (response.ok !== true || !Object.hasOwn(response, 'result')) throw new PushOwnerControlError();
          settled = true; socket.destroy(); resolve(response.result);
        } catch { fail(); }
        finally { bytes.fill(0); }
      });
      socket.on('close', () => { if (!settled) fail(); });
    });
  } catch { throw new PushOwnerControlError(); }
}
