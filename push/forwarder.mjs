import { preparePushBatch } from './events.mjs';
import { PushDispatcher } from './dispatcher.mjs';
import { readPushHost, hostMatchesRecipient, hostAdmitsEvent, hostPermitsDelivery } from './policy.mjs';

/** Composition of live host policy, enrolled recipients, encryption and durable delivery.
 * readHost is a trusted local source, never request parameters or the phone's claimed account.
 * The source owns replay/cursors and must retain a prepared result when queue admission needs retry.
 */
export class PushForwarder {
  #registrations; #readHost; #seal; #now; #dispatcher; #preparing = 0; #stopped = false; #generation = 0; #settled = new Set(); #stopPromise = null;
  constructor({ registrations, outbox, sender, readHost, seal, now = Date.now, ...scheduler }) {
    if (typeof registrations?.recipients !== 'function' || typeof readHost !== 'function' || typeof seal !== 'function')
      throw new TypeError('Invalid push forwarder configuration');
    this.#registrations = registrations; this.#readHost = readHost; this.#seal = seal; this.#now = now;
    this.#dispatcher = new PushDispatcher({ ...scheduler, registrations, outbox, sender, now,
      policy: (binding, admission) => hostPermitsDelivery(readPushHost(this.#readHost, this.#now), binding, admission) });
  }
  /** No listener/provider IO starts until the owning service explicitly calls this. */
  start() {
    if (this.#stopPromise) throw new Error('Push forwarder is stopping');
    this.#dispatcher.start(); this.#stopped = false;
  }
  stop() {
    if (this.#stopPromise) return this.#stopPromise;
    this.#stopped = true; this.#generation++;
    this.#stopPromise = Promise.all([this.#dispatcher.stop(), ...this.#settled]).then(() => undefined)
      .finally(() => { this.#stopPromise = null; });
    return this.#stopPromise;
  }
  changed() { this.#dispatcher.changed(); }
  resumeBlocked(reason) { return this.#dispatcher.resumeBlocked(reason); }

  async prepare({ event, expiresAt, sourceEpoch, phoneEligible }) {
    if (this.#stopped) return { kind: 'stopped' };
    if (this.#preparing >= 2) return { kind: 'busy' };
    this.#preparing++;
    const generation = this.#generation;
    let finish;
    const settled = new Promise(resolve => { finish = resolve; });
    this.#settled.add(settled);
    try {
      const original = structuredClone(event), host = readPushHost(this.#readHost, this.#now);
      if (!original || !['notify', 'dismiss'].includes(original.kind)) throw new TypeError('Invalid push event');
      if (host.state !== 'ready' || sourceEpoch !== host.epoch) return { kind: 'retired' };
      // History/feed insertion and replay alone are not evidence of phone-forward eligibility.
      if ((original.kind === 'notify' && phoneEligible !== true) || !hostAdmitsEvent(host, original.kind)) return { kind: 'suppressed' };
      if (!Number.isSafeInteger(expiresAt) || expiresAt % 1000 !== 0) throw new TypeError('Invalid push expiry');
      if (expiresAt - this.#now() < 1000) return { kind: 'expired' };
      const records = this.#registrations.recipients({ accountID: host.authority.accountID, teamID: host.authority.teamID ?? null })
        .filter(record => hostMatchesRecipient(host, record.recipient));
      if (!records.length) return { kind: 'no-recipients' };
      const jobs = [];
      for (const registration of records) {
        const admission = { version: 1, hostEpoch: host.epoch, kind: original.kind, hideContent: host.settings.hideContent,
          senderInstallationID: host.authority.macInstallationID, senderPublicKey: host.authority.publicKey };
        const permits = () => {
          if (this.#stopped || generation !== this.#generation) return false;
          const live = readPushHost(this.#readHost, this.#now);
          return hostAdmitsEvent(live, original.kind) && hostPermitsDelivery(live, { recipient: registration.recipient }, admission);
        };
        const prepared = await preparePushBatch({ event: { ...original, hideContent: host.settings.hideContent },
          expiresAt, registration, authority: host.authority, hostEpoch: host.epoch }, { seal: this.#seal, now: this.#now, permits });
        if (this.#stopped || generation !== this.#generation) return { kind: 'stopped' };
        if (prepared.kind !== 'prepared') return prepared;
        jobs.push(...prepared.jobs);
        if (jobs.length > 512) throw new Error('Push fanout exceeds capacity');
        const live = readPushHost(this.#readHost, this.#now);
        if (!hostAdmitsEvent(live, original.kind)) return { kind: 'suppressed' };
        for (const job of jobs) {
          if (!hostPermitsDelivery(live, { recipient: job.delivery.recipient }, job.admission)) return { kind: 'retired' };
        }
      }
      return { kind: 'prepared', jobs };
    } finally { this.#preparing--; this.#settled.delete(settled); finish(); }
  }
  /** Retry this same sealed result after storage/backpressure errors; never re-encrypt with a renewed expiry. */
  enqueue(prepared) {
    if (this.#stopped) return { kind: 'stopped' };
    if (prepared?.kind !== 'prepared') throw new TypeError('Push event has not been prepared');
    return this.#dispatcher.enqueueBatch(prepared.jobs);
  }
}
