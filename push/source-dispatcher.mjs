const terminal = new Set(['queued', 'suppressed', 'retired', 'expired', 'lease-lost', 'unavailable', 'corrupt-record']);

/** Owns source replay and the existing delivery forwarder. The caller supplies an
 * authenticated producer; capture() only confirms durable storage, not authority
 * or phone delivery. Import/construction never starts timers, listeners or IO.
 */
export class PushSourceDispatcher {
  #journal; #forwarder; #now; #setTimer; #clearTimer; #report; #limit;
  #running = false; #generation = 0; #timer = null; #active = null; #wakeAgain = false; #stopping = null;
  constructor({ journal, forwarder, now = Date.now, setTimer = setTimeout, clearTimer = clearTimeout,
    onState = () => {}, limit = 8 }) {
    if (['accept', 'drain', 'status'].some(name => typeof journal?.[name] !== 'function') ||
        ['start', 'stop', 'prepare', 'enqueue', 'changed', 'resumeBlocked'].some(name => typeof forwarder?.[name] !== 'function') ||
        [now, setTimer, clearTimer, onState].some(value => typeof value !== 'function') ||
        !Number.isInteger(limit) || limit < 1 || limit > 32)
      throw new TypeError('Invalid push source dispatcher configuration');
    this.#journal = journal; this.#forwarder = forwarder; this.#now = now;
    this.#setTimer = setTimer; this.#clearTimer = clearTimer; this.#report = onState; this.#limit = limit;
  }
  #state(value) { try { this.#report(value); } catch { /* Diagnostics cannot interrupt delivery. */ } }
  #cancelTimer() {
    if (this.#timer !== null) { this.#clearTimer(this.#timer.handle); this.#timer = null; }
  }
  #schedule(delay) {
    if (!this.#running) return;
    this.#cancelTimer();
    const ticket = { generation: this.#generation, handle: null };
    this.#timer = ticket;
    ticket.handle = this.#setTimer(() => {
      // A cancelled callback already in the event loop must not consume a newer timer.
      if (this.#timer !== ticket || !this.#running || ticket.generation !== this.#generation) return;
      this.#timer = null;
      return this.#pump();
    }, Math.min(2_147_483_647, Math.max(0, delay)));
  }
  #wake() {
    if (!this.#running) return;
    if (this.#active) this.#wakeAgain = true;
    else this.#schedule(0);
  }
  start() {
    if (this.#stopping) throw new Error('Push source dispatcher is stopping');
    if (this.#running) return;
    if (this.#active) throw new Error('Push source dispatcher is stopping');
    this.#forwarder.start();
    this.#running = true; this.#generation++; this.#wake();
  }
  /** Stop both schedulers and wait for preparation/provider IO before closing stores/keys. */
  stop() {
    if (this.#stopping) return this.#stopping;
    this.#running = false; this.#generation++; this.#wakeAgain = false; this.#cancelTimer();
    this.#stopping = Promise.all([this.#active, this.#forwarder.stop()]).then(() => undefined)
      .finally(() => { this.#stopping = null; });
    return this.#stopping;
  }
  /** Capture is allowed while stopped. The host must first quiesce its source before closing storage. */
  capture(request) {
    const receipt = this.#journal.accept(request);
    if (['captured', 'duplicate'].includes(receipt.kind)) this.#wake();
    return receipt;
  }
  /** Policy/enrollment transitions wake both stages; existing retry deadlines/expiry are retained. */
  changed() { this.#forwarder.changed(); this.#wake(); }
  resumeBlocked(reason) { const count = this.#forwarder.resumeBlocked(reason); this.#wake(); return count; }
  async #pump() {
    if (!this.#running || this.#active) return;
    const generation = this.#generation;
    const current = () => this.#running && generation === this.#generation;
    const work = (async () => {
      let failed = false;
      try {
        const outcomes = await this.#journal.drain({ forwarder: {
          prepare: request => current() ? this.#forwarder.prepare(request) : { kind: 'stopped' },
          enqueue: prepared => current() ? this.#forwarder.enqueue(prepared) : { kind: 'stopped' }
        }, limit: this.#limit, shouldContinue: current });
        // Journal IDs and event content never enter host diagnostic callbacks.
        const counts = {};
        for (const outcome of outcomes) {
          const kind = terminal.has(outcome?.kind) ? outcome.kind : 'unknown';
          counts[kind] = (counts[kind] ?? 0) + 1;
        }
        this.#state({ kind: 'pass', counts });
      } catch { failed = true; this.#state({ kind: 'storage-unavailable' }); }
      if (!current()) return;
      try {
        const status = this.#journal.status(), time = this.#now();
        if (!Number.isSafeInteger(time) || time < 0 ||
            !(status.nextDueAt === null || (Number.isSafeInteger(status.nextDueAt) && status.nextDueAt >= 0)) ||
            !status.counts || typeof status.counts !== 'object' || Array.isArray(status.counts) ||
            Object.entries(status.counts).some(([key, value]) => !['pending', 'prepared', 'done'].includes(key) ||
              !Number.isSafeInteger(value) || value < 0)) throw new Error();
        this.#state({ kind: 'scheduled', nextDueAt: status.nextDueAt, counts: { ...status.counts } });
        if (failed) this.#schedule(5000);
        else if (this.#wakeAgain) this.#schedule(0);
        else if (status.nextDueAt !== null) this.#schedule(status.nextDueAt - time);
      } catch { this.#state({ kind: 'storage-unavailable' }); this.#schedule(5000); }
    })();
    this.#active = work;
    try { await work; } finally { this.#active = null; this.#wakeAgain = false; }
  }
}
