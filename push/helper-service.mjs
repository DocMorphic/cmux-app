import { isAbsolute, join } from 'node:path';
import { mkdirSync, lstatSync } from 'node:fs';
import { PushRegistrations } from './registrations.mjs';
import { PushOutbox } from './outbox.mjs';
import { PushSourceJournal } from './source-journal.mjs';
import { PushForwarder } from './forwarder.mjs';
import { PushSourceDispatcher } from './source-dispatcher.mjs';
import { FcmSender } from './fcm.mjs';
import { PushEnrollment } from './enrollment.mjs';
import { PushMaintenance } from './maintenance.mjs';
import { createPushEnrollmentServer } from './enrollment-http.mjs';
import { NativePushSourceAdapter } from './native-source.mjs';
import { NativePushSourceChannel } from './native-source-channel.mjs';
import { readPushHost, hostMatchesRecipient } from './policy.mjs';

const text = value => typeof value === 'string' && value.length > 0 && value.length <= 1024 && !/[\u0000-\u001f]/.test(value);
const descriptor = value => value && Object.keys(value).length === 5 && value.version === 1 && value.algorithm === 'rawX25519' &&
  text(value.installationID) && text(value.keyID) && typeof value.publicKey === 'string' &&
  Buffer.from(value.publicKey, 'base64').length === 32 && Buffer.from(value.publicKey, 'base64').toString('base64') === value.publicKey;
const sameDescriptor = (a, b) => ['version', 'algorithm', 'installationID', 'keyID', 'publicKey'].every(k => a?.[k] === b?.[k]);
const fields = ['accountID', 'teamID', 'macDeviceID', 'macInstanceTag', 'macBuildID'];
export class PushHelperError extends Error { constructor() { super('Push helper unavailable'); } }

/** One private native source, durable queues, FCM transport and HTTPS enrollment.
 * The owner provides credentials/TLS/native descriptor; no authority comes from
 * a phone request. Construction does not touch storage, timers or network.
 */
export class NativePushHelperService {
  #directory; #keys; #tls; #helper; #readNative; #project; #clients; #configuredEndpoint; #endpoint;
  #seal; #tokens; #fetch; #now; #report; #state = 'new'; #starting = null; #stopping = null; #disposing = null;
  #registrations; #outbox; #journal; #forwarder; #dispatcher; #adapter; #channel; #server;
  #sockets = new Set(); #sealing = new Set(); #offers = new Map(); #issuingEpoch = null;
  constructor({ directory, keys, tls, helper, readNativeDescriptor, project, clients, endpoint,
    seal, tokens, fetch = globalThis.fetch, now = Date.now, onState = () => {} }) {
    let url;
    try { url = new URL(endpoint); } catch { throw new PushHelperError(); }
    if (!isAbsolute(directory ?? '') || !keys || Object.keys(keys).length !== 3 ||
        ['registrations', 'outbox', 'source'].some(k => !Buffer.isBuffer(keys[k]) || keys[k].length !== 32) ||
        !Buffer.isBuffer(tls?.key) || !tls.key.length || tls.key.length > 16384 ||
        !Buffer.isBuffer(tls.cert) || !tls.cert.length || tls.cert.length > 65536 ||
        !descriptor(helper) || typeof readNativeDescriptor !== 'function' ||
        !/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(project?.project ?? '') || !text(project?.sender) || Object.keys(project).length !== 2 ||
        !Array.isArray(clients) || !clients.length || clients.length > 8 ||
        clients.some(c => !c || !text(c.phoneBuildID) || !text(c.application)) ||
        new Set(clients.map(c => c.phoneBuildID)).size !== clients.length ||
        url.protocol !== 'https:' || url.username || url.password || url.search || url.hash || url.pathname !== '/v1/push/enroll' ||
        [seal, fetch, now, onState].some(v => typeof v !== 'function') || typeof tokens?.get !== 'function' || typeof tokens.invalidate !== 'function')
      throw new PushHelperError();
    this.#directory = directory; this.#keys = Object.fromEntries(Object.entries(keys).map(([k, v]) => [k, Buffer.from(v)]));
    this.#tls = { key: Buffer.from(tls.key), cert: Buffer.from(tls.cert) };
    this.#helper = structuredClone(helper); this.#readNative = readNativeDescriptor; this.#project = structuredClone(project);
    this.#clients = new Map(clients.map(c => [c.phoneBuildID, c.application])); this.#configuredEndpoint = url.href;
    this.#seal = seal; this.#tokens = tokens; this.#fetch = fetch; this.#now = now; this.#report = onState;
  }
  #emit(kind) { try { this.#report({ kind }); } catch { /* Diagnostics cannot grant authority or interrupt cleanup. */ } }
  #host() {
    if (this.#state !== 'running') throw new PushHelperError();
    const value = readPushHost(() => this.#adapter?.readHost(), this.#now);
    if (value.state !== 'ready') throw new PushHelperError();
    return value;
  }
  #native() {
    const value = structuredClone(this.#readNative());
    if (!descriptor(value) || ['installationID', 'keyID', 'publicKey'].some(k => value[k] === this.#helper[k])) throw new PushHelperError();
    return value;
  }
  #permitOffer(offer) {
    try {
      const host = this.#host(), entry = this.#offers.get(offer.offerID);
      const epoch = entry?.epoch ?? this.#issuingEpoch;
      return epoch === host.epoch && sameDescriptor(offer.native, this.#native()) && sameDescriptor(offer.helper, this.#helper) &&
        fields.every(k => (offer.mac[k] ?? null) === (host.authority[k] ?? null)) &&
        offer.endpoint === this.#endpoint && offer.project.project === this.#project.project && offer.project.sender === this.#project.sender &&
        this.#clients.get(offer.phoneBuildID) === offer.project.application;
    } catch { return false; }
  }
  #permitMaintenance(binding, action) {
    try {
      const host = this.#host();
      // Phone-key possession and current native account authorize cleanup even
      // while forwarding is paused. Presence does not gate token maintenance.
      return hostMatchesRecipient(host, binding.recipient) &&
        (action === 'revoke' || (action === 'renew' && this.#clients.has(binding.recipient.tuple.iosBuildID)));
    } catch { return false; }
  }
  #encrypt = input => {
    if (this.#state !== 'running') return Promise.reject(new PushHelperError());
    const work = Promise.resolve().then(() => {
      if (this.#state !== 'running') throw new PushHelperError();
      return this.#seal(input);
    });
    this.#sealing.add(work);
    work.finally(() => this.#sealing.delete(work)).catch(() => {});
    return work;
  };
  #enrollment; #maintenance;
  /** TLS host/interface is explicit. Port zero is useful only for local fixture
   * listeners; its advertised endpoint is replaced with the actual bound port. */
  start({ input, output, host, port }) {
    if (this.#state !== 'new' || typeof host !== 'string' || !host || !Number.isInteger(port) || port < 0 || port > 65535)
      return Promise.reject(new PushHelperError());
    this.#state = 'starting';
    this.#starting = (async () => {
      try {
        mkdirSync(this.#directory, { recursive: true, mode: 0o700 });
        const stat = lstatSync(this.#directory);
        if (!stat.isDirectory() || (process.platform !== 'win32' && (stat.uid !== process.getuid() || (stat.mode & 0o077))))
          throw new PushHelperError();
        this.#registrations = new PushRegistrations({ directory: join(this.#directory, 'registrations'), key: this.#keys.registrations });
        this.#outbox = new PushOutbox({ directory: join(this.#directory, 'outbox'), key: this.#keys.outbox, now: this.#now });
        this.#journal = new PushSourceJournal({ directory: join(this.#directory, 'source'), key: this.#keys.source, now: this.#now });
        const sender = new FcmSender({ projectId: this.#project.project, tokens: this.#tokens, fetch: this.#fetch, now: this.#now });
        this.#forwarder = new PushForwarder({ registrations: this.#registrations, outbox: this.#outbox, sender,
          readHost: () => this.#state === 'running' ? this.#adapter?.readHost() : null, seal: this.#encrypt, now: this.#now });
        this.#dispatcher = new PushSourceDispatcher({ journal: this.#journal, forwarder: this.#forwarder, now: this.#now });
        this.#enrollment = new PushEnrollment({ registrations: this.#registrations, seal: this.#encrypt, now: this.#now,
          permits: offer => this.#permitOffer(offer) });
        // Initialized after the actual listener endpoint is known.
        const enrollment = { begin: body => this.#enrollment.begin(body), finish: body => {
          const receipt = this.#enrollment.finish(body); this.#dispatcher.changed(); return receipt;
        } };
        const maintenance = { begin: body => this.#maintenance.begin(body), finish: body => {
          const receipt = this.#maintenance.finish(body); this.#dispatcher.changed(); return receipt;
        }, abort: body => this.#maintenance.abort(body) };
        this.#server = createPushEnrollmentServer({ ...this.#tls, enrollment, maintenance, endpoint: () => this.#endpoint });
        this.#server.on('connection', socket => { this.#sockets.add(socket); socket.once('close', () => this.#sockets.delete(socket)); });
        this.#server.on('error', () => { if (this.#state === 'running') { this.#emit('listener-unavailable'); void this.stop(); } });
        await new Promise((resolve, reject) => {
          const failed = error => { this.#server.off('listening', ready); reject(error); };
          const ready = () => { this.#server.off('error', failed); resolve(); };
          this.#server.once('error', failed); this.#server.once('listening', ready); this.#server.listen(port, host);
        });
        if (this.#state !== 'starting') throw new PushHelperError();
        const endpoint = new URL(this.#configuredEndpoint);
        if (endpoint.port === '0') endpoint.port = String(this.#server.address().port);
        this.#endpoint = endpoint.href;
        this.#maintenance = new PushMaintenance({ registrations: this.#registrations, seal: this.#encrypt, now: this.#now,
          endpoint: this.#endpoint, senderKeyID: this.#helper.keyID, senderPublicKey: this.#helper.publicKey,
          permits: (binding, action) => this.#permitMaintenance(binding, action) });
        this.#channel = new NativePushSourceChannel({ input, output, createAdapter: ({ key, sessionID }) => {
          this.#adapter = new NativePushSourceAdapter({ key, sessionID,
            helper: { senderKeyID: this.#helper.keyID, macInstallationID: this.#helper.installationID, publicKey: this.#helper.publicKey },
            capture: request => this.#dispatcher.capture(request), changed: () => this.#dispatcher.changed(), now: this.#now });
          return this.#adapter;
        } });
        this.#state = 'running'; this.#dispatcher.start();
        this.#channel.start().then(() => this.stop()).catch(() => { this.#emit('source-unavailable'); void this.stop(); });
        this.#emit('started');
        return { endpoint: this.#endpoint };
      } catch {
        this.#state = 'stopping'; await this.#dispose(); this.#state = 'closed'; throw new PushHelperError();
      }
    })();
    return this.#starting;
  }
  /** Trusted host-only operation; never an HTTP step or an incoming phone scope. */
  issueOffer(phoneBuildID, lifetime = 120_000) {
    try {
      const host = this.#host(), application = this.#clients.get(phoneBuildID);
      if (!application) throw new PushHelperError();
      for (const [id, row] of this.#offers) if (row.expiresAt <= this.#now()) this.#offers.delete(id);
      this.#issuingEpoch = host.epoch;
      const offer = this.#enrollment.issue({ endpoint: this.#endpoint,
        project: { ...this.#project, application }, phoneBuildID,
        mac: Object.fromEntries(fields.map(k => [k, host.authority[k] ?? null])), native: this.#native(), helper: this.#helper }, lifetime);
      this.#offers.set(offer.offerID, { epoch: host.epoch, expiresAt: offer.expiresAt });
      return offer;
    } catch { throw new PushHelperError(); } finally { this.#issuingEpoch = null; }
  }
  cancelOffer(id) { this.#enrollment?.cancel(id); this.#offers.delete(id); }
  /** Explicit owner recovery after credentials/configuration are fixed. */
  resumeBlocked(reason) { if (this.#state !== 'running') throw new PushHelperError(); return this.#dispatcher.resumeBlocked(reason); }
  stop() {
    if (this.#stopping) return this.#stopping;
    this.#state = 'stopping'; this.#channel?.stop(); this.#adapter?.close();
    this.#enrollment?.close(); this.#maintenance?.close();
    this.#stopping = (async () => {
      await this.#starting?.catch(() => {}); await this.#dispose(); this.#state = 'closed'; this.#emit('closed');
    })();
    return this.#stopping;
  }
  #dispose() {
    if (this.#disposing) return this.#disposing;
    this.#channel?.stop(); this.#adapter?.close(); this.#enrollment?.close(); this.#maintenance?.close();
    for (const socket of this.#sockets) socket.destroy();
    const listener = this.#server ? new Promise(resolve => this.#server.close(() => resolve())) : Promise.resolve();
    this.#disposing = (async () => {
      try { await Promise.allSettled([listener, this.#dispatcher?.stop(), ...this.#sealing]); }
      finally {
        for (const store of [this.#journal, this.#outbox, this.#registrations]) { try { store?.close(); } catch { this.#emit('storage-unavailable'); } }
        Object.values(this.#keys).forEach(key => key.fill(0)); this.#tls.key.fill(0);
        this.#offers.clear(); this.#seal = null; this.#tokens = null; this.#fetch = null; this.#readNative = null;
      }
    })();
    return this.#disposing;
  }
}
