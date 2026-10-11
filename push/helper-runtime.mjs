import { createPrivateKey, createPublicKey } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import { isAbsolute, join } from 'node:path';
import { readPrivatePushFile, privateServiceAccountTokens } from './private-credentials.mjs';
import { cryptoKitSealer } from './crypto.mjs';
import { NativePushHelperService, PushHelperError } from './helper-service.mjs';
import { PushOwnerControl } from './owner-control.mjs';

const exact = (v, keys) => v && typeof v === 'object' && !Array.isArray(v) &&
  Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k));
function json(path) {
  const bytes = readPrivatePushFile(path);
  try { return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)); }
  finally { bytes.fill(0); }
}

/** Production composition. Trusted native owner supplies configuration and
 * private pipes; neither stored registrations nor config contain account authority. */
export class NativePushHelperRuntime {
  #path; #input; #output; #fetch; #report; #service; #control; #owned = [];
  #state = 'new'; #starting; #stopping; #cleanup; #resolve;
  constructor({ configurationPath, input, output, fetch = globalThis.fetch, onState = () => {} }) {
    if (!isAbsolute(configurationPath ?? '') || typeof fetch !== 'function' || typeof onState !== 'function') throw new PushHelperError();
    this.#path = configurationPath; this.#input = input; this.#output = output; this.#fetch = fetch; this.#report = onState;
    this.finished = new Promise(resolve => { this.#resolve = resolve; });
  }
  #read = (path, maximum) => { const bytes = readPrivatePushFile(path, maximum); this.#owned.push(bytes); return bytes; };
  start() {
    if (this.#state !== 'new') return Promise.reject(new PushHelperError());
    this.#state = 'starting';
    this.#starting = (async () => {
      try {
        const c = json(this.#path);
        if (!exact(c, ['version', 'stateDirectory', 'helper', 'nativeDescriptorPath', 'keys', 'tls', 'provider',
              'clients', 'endpoint', 'listener', 'sealerExecutable']) || c.version !== 1 ||
            !isAbsolute(c.stateDirectory ?? '') || !isAbsolute(c.nativeDescriptorPath ?? '') || !isAbsolute(c.sealerExecutable ?? '') ||
            !exact(c.keys, ['sender', 'registrations', 'source', 'outbox']) || !exact(c.tls, ['key', 'cert']) ||
            !exact(c.provider, ['path', 'projectID', 'accountEmail', 'sender']) ||
            !exact(c.listener, ['host', 'port']) || !Number.isInteger(c.listener.port) || c.listener.port < 0 || c.listener.port > 65535)
          throw new PushHelperError();
        const sender = this.#read(c.keys.sender, 32);
        if (sender.length !== 32) throw new PushHelperError();
        const der = Buffer.concat([Buffer.from('302e020100300506032b656e04220420', 'hex'), sender]);
        let publicKey;
        try {
          publicKey = createPublicKey(createPrivateKey({ key: der, format: 'der', type: 'pkcs8' }))
            .export({ format: 'der', type: 'spki' }).subarray(-32).toString('base64');
        } finally { der.fill(0); }
        if (publicKey !== c.helper?.publicKey) throw new PushHelperError();
        const keys = Object.fromEntries(['registrations', 'source', 'outbox'].map(k => [k, this.#read(c.keys[k], 32)]));
        const tls = { key: this.#read(c.tls.key, 16_384), cert: this.#read(c.tls.cert, 65_536) };
        const tokens = privateServiceAccountTokens({ path: c.provider.path, projectID: c.provider.projectID,
          accountEmail: c.provider.accountEmail, fetch: this.#fetch });
        const seal = cryptoKitSealer({ executable: c.sealerExecutable, senderKeyID: c.helper.keyID,
          senderPublicKey: publicKey, privateKey: async () => sender });
        this.#service = new NativePushHelperService({ directory: c.stateDirectory, keys, tls, helper: c.helper,
          readNativeDescriptor: () => json(c.nativeDescriptorPath), project: { project: c.provider.projectID, sender: c.provider.sender },
          clients: c.clients, endpoint: c.endpoint, seal, tokens, fetch: this.#fetch,
          onState: event => {
            try { this.#report(event); } catch {}
            if (event.kind === 'closed') queueMicrotask(() => { void this.stop(); });
          } });
        const ready = await this.#service.start({ ...c.listener, input: this.#input, output: this.#output });
        if (this.#state !== 'starting') throw new PushHelperError();
        const directory = join(c.stateDirectory, 'control');
        mkdirSync(directory, { recursive: true, mode: 0o700 });
        const path = join(directory, 'owner.sock');
        this.#control = new PushOwnerControl({ path,
          issueOffer: build => this.#service.issueOffer(build), cancelOffer: id => this.#service.cancelOffer(id),
          resumeBlocked: reason => this.#service.resumeBlocked(reason), onFailure: () => { void this.stop(); } });
        await this.#control.start();
        if (this.#state !== 'starting') throw new PushHelperError();
        this.#state = 'running'; return { ...ready, controlPath: path };
      } catch {
        this.#state = 'stopping'; await this.#dispose(); this.#state = 'closed'; this.#resolve(); throw new PushHelperError();
      }
    })();
    return this.#starting;
  }
  stop() {
    if (this.#stopping) return this.#stopping;
    this.#state = 'stopping';
    // Fence before awaiting startup or local control shutdown.
    const serviceStop = this.#service?.stop(); const controlStop = this.#control?.stop();
    this.#stopping = (async () => {
      await this.#starting?.catch(() => {});
      await Promise.allSettled([serviceStop, controlStop]); await this.#dispose();
      this.#state = 'closed'; this.#resolve();
    })();
    return this.#stopping;
  }
  #dispose() {
    if (this.#cleanup) return this.#cleanup;
    this.#cleanup = (async () => {
      await Promise.allSettled([this.#control?.stop(), this.#service?.stop()]);
      this.#owned.forEach(bytes => bytes.fill(0)); this.#owned = []; this.#fetch = null;
    })();
    return this.#cleanup;
  }
}
