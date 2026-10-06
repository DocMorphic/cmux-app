/** Explicitly started host scheduler; importing this module never enrolls a phone or sends a request. */
export class PushDispatcher {
  #outbox; #registrations; #sender; #policy; #now; #setTimer; #clearTimer; #report;
  #running = false; #generation = 0; #timer = null; #active = null; #wakeAgain = false;
  constructor({ outbox, registrations, sender, policy = () => false, now = Date.now,
    setTimer = setTimeout, clearTimer = clearTimeout, onState = () => {} }) {
    if (typeof outbox?.drain !== 'function' || typeof outbox?.status !== 'function' ||
        typeof registrations?.matches !== 'function' || typeof registrations?.retire !== 'function' ||
        typeof sender?.send !== 'function' || [policy, now, setTimer, clearTimer, onState].some(value => typeof value !== 'function'))
      throw new TypeError('Invalid push dispatcher configuration');
    this.#outbox = outbox; this.#registrations = registrations; this.#sender = sender; this.#policy = policy;
    this.#now = now; this.#setTimer = setTimer; this.#clearTimer = clearTimer; this.#report = onState;
  }
  #state(value) { try { this.#report(value); } catch { /* Host diagnostics cannot break delivery or expose a binding. */ } }
  #permits(binding, admission) { return this.#registrations.matches(binding) === true && this.#policy(binding, admission) === true; }
  #schedule(delay) {
    if (!this.#running) return;
    if (this.#timer !== null) this.#clearTimer(this.#timer);
    this.#timer = this.#setTimer(() => { this.#timer = null; return this.#pump(); }, Math.min(2_147_483_647, Math.max(0, delay)));
  }
  #wake() { if (this.#active) this.#wakeAgain = true; else this.#schedule(0); }
  start() {
    if (this.#running) return;
    if (this.#active) throw new Error('Push dispatcher is stopping');
    this.#running = true; this.#generation++; this.#wake();
  }
  /** Wait for an in-flight provider operation before the host closes its database/credential handles. */
  async stop() {
    this.#running = false; this.#generation++; this.#wakeAgain = false;
    if (this.#timer !== null) { this.#clearTimer(this.#timer); this.#timer = null; }
    await this.#active;
  }
  enqueue(job) {
    // The caller must already have authenticated the original event and encrypted it for this admitted recipient.
    const snapshot = structuredClone(job);
    const binding = { registration: snapshot.registration, token: snapshot.delivery.token, recipient: snapshot.delivery.recipient };
    if (!this.#permits(binding, snapshot.admission ?? null)) return { kind: 'retired' };
    const result = this.#outbox.enqueue(snapshot); this.#wake(); return result;
  }
  enqueueBatch(jobs) {
    if (!Array.isArray(jobs) || jobs.length < 1 || jobs.length > 512) throw new TypeError('Invalid push batch');
    // Inspect the whole snapshot before admission; one revoked recipient prevents a partial enqueue.
    const snapshot = structuredClone(jobs);
    for (const job of snapshot) {
      const binding = { registration: job.registration, token: job.delivery.token, recipient: job.delivery.recipient };
      if (!this.#permits(binding, job.admission ?? null)) return { kind: 'retired' };
    }
    const result = this.#outbox.enqueueBatch(snapshot); this.#wake(); return result;
  }
  /** Call after enrollment/policy changes committed in another host component. */
  changed() { this.#wake(); }
  resumeBlocked(reason) { const count = this.#outbox.resumeBlocked(reason); this.#wake(); return count; }
  async #pump() {
    if (!this.#running || this.#active) return;
    const generation = this.#generation;
    const work = (async () => {
      let storageFailed = false;
      try {
        const outcomes = await this.#outbox.drain({ sender: this.#sender,
          permits: (binding, admission) => {
            // Stopping is temporary unavailability, not device revocation. Retain work for the next start.
            if (!this.#running || generation !== this.#generation) throw new Error('Push dispatcher stopped');
            return this.#permits(binding, admission);
          }, retire: binding => this.#registrations.retire(binding), limit: 8 });
        this.#state({ kind: 'pass', outcomes });
      } catch { storageFailed = true; this.#state({ kind: 'storage-unavailable' }); }
      if (!this.#running || generation !== this.#generation) return;
      try {
        const status = this.#outbox.status(); this.#state({ kind: 'scheduled', ...status });
        if (storageFailed) this.#schedule(5000);
        else if (this.#wakeAgain) this.#schedule(0);
        else if (status.nextDueAt !== null) this.#schedule(status.nextDueAt - this.#now());
      } catch { this.#state({ kind: 'storage-unavailable' }); this.#schedule(5000); }
    })();
    this.#active = work;
    try { await work; } finally { this.#active = null; this.#wakeAgain = false; }
  }
}
