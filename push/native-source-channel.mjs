import { NativePushSourceError } from './native-source.mjs';

// Raw JSON is carried as base64, with a bounded outer envelope. Never expose this
// channel on a socket: the owner supplies private child stdin/stdout pipes.
const MAX_LINE = 4 * Math.ceil(1_048_576 / 3) + 512;
const session = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const decoder = new TextDecoder('utf-8', { fatal: true });

/** Private, parent-owned NDJSON source channel. Construction is passive. The
 * first line provisions an ephemeral session/key; only the native Mac parent is
 * allowed to write this pipe. The adapter authenticates all subsequent frames.
 * This bootstrap is not an account-authentication or public enrollment endpoint.
 */
export class NativePushSourceChannel {
  #input; #output; #create; #report; #adapter = null; #closed = false; #started = false;
  #line = null; #size = 0; #active = null; #ended = false; #done; #resolve;
  constructor({ input, output, createAdapter, onState = () => {} }) {
    if (!input || !output || ['on', 'off', 'pause', 'resume'].some(name => typeof input[name] !== 'function') ||
        ['on', 'off', 'write'].some(name => typeof output[name] !== 'function') ||
        typeof createAdapter !== 'function' || typeof onState !== 'function')
      throw new TypeError('Invalid native source channel configuration');
    this.#input = input; this.#output = output; this.#create = createAdapter; this.#report = onState;
    this.#done = new Promise(resolve => { this.#resolve = resolve; });
  }
  #state(kind) { try { this.#report({ kind }); } catch { /* No source data in diagnostics. */ } }
  #failed = () => { this.#finish('channel-unavailable'); };
  #end = () => {
    this.#ended = true;
    // Authority must expire even when the final receipt is stuck in output IO.
    this.#finish(this.#size ? 'source-rejected' : 'closed');
  };
  #close = () => { if (!this.#ended) this.#finish('channel-unavailable'); };
  #data = chunk => {
    if (this.#closed) return;
    // Node pipe streams are bytes. Text decoding must occur after complete lines.
    if (!(chunk instanceof Uint8Array) || this.#active || chunk.length > MAX_LINE * 2) {
      this.#finish('source-rejected'); return;
    }
    this.#input.pause();
    const work = this.#consume(Buffer.from(chunk));
    this.#active = work;
    work.catch(() => this.#finish('source-rejected')).finally(() => {
      this.#active = null;
      if (this.#closed) return;
      if (this.#ended) this.#finish(this.#size ? 'source-rejected' : 'closed');
      else this.#input.resume();
    });
  };
  start() {
    if (this.#closed) throw new Error('Native source channel is closed');
    if (this.#started) return this.#done;
    this.#started = true;
    this.#input.on('data', this.#data); this.#input.on('end', this.#end);
    this.#input.on('error', this.#failed); this.#input.on('close', this.#close);
    this.#output.on('error', this.#failed); this.#output.on('close', this.#failed);
    this.#state('started'); this.#input.resume();
    return this.#done;
  }
  async #consume(chunk) {
    let offset = 0;
    while (!this.#closed && offset < chunk.length) {
      const newline = chunk.indexOf(10, offset), end = newline < 0 ? chunk.length : newline;
      const part = chunk.subarray(offset, end);
      this.#size += part.length;
      if (this.#size > MAX_LINE) throw new NativePushSourceError();
      this.#line ??= Buffer.alloc(MAX_LINE);
      part.copy(this.#line, this.#size - part.length);
      if (newline < 0) break;
      const value = JSON.parse(decoder.decode(this.#line.subarray(0, this.#size)));
      this.#line.fill(0, 0, this.#size); this.#size = 0;
      let receipt;
      if (!this.#adapter) {
        if (!value || Array.isArray(value) || Object.keys(value).length !== 4 || value.type !== 'bootstrap' || value.version !== 1 ||
            !session.test(value.sessionID) || typeof value.key !== 'string' || value.key.length !== 44)
          throw new NativePushSourceError();
        const key = Buffer.from(value.key, 'base64');
        try {
          if (key.length !== 32 || key.toString('base64') !== value.key) throw new NativePushSourceError();
          this.#adapter = this.#create({ key, sessionID: value.sessionID });
          if (!this.#adapter || ['accept', 'close'].some(name => typeof this.#adapter[name] !== 'function'))
            throw new NativePushSourceError();
        } finally { key.fill(0); }
        receipt = { sequence: 0, kind: 'ready', acknowledged: true };
        this.#state('ready');
      } else {
        receipt = this.#adapter.accept(value);
      }
      await this.#write(receipt);
      offset = end + 1;
    }
  }
  #write(receipt) {
    return new Promise((resolve, reject) => {
      if (this.#closed) { resolve(); return; }
      // Deliberately construct the reply: never echo a source field or thrown error.
      const bytes = JSON.stringify({ sequence: receipt.sequence, kind: receipt.kind, acknowledged: receipt.acknowledged }) + '\n';
      this.#output.write(bytes, error => error ? reject(error) : resolve());
    });
  }
  #finish(kind) {
    if (this.#closed) return;
    this.#closed = true; this.#input.pause();
    this.#input.off('data', this.#data); this.#input.off('end', this.#end);
    this.#input.off('error', this.#failed); this.#input.off('close', this.#close);
    this.#output.off('error', this.#failed); this.#output.off('close', this.#failed);
    try { this.#adapter?.close(); } catch { /* Closure still retires the channel. */ }
    this.#line?.fill(0); this.#line = null; this.#size = 0;
    this.#state(kind); this.#resolve({ kind });
  }
  /** Invalidates authority synchronously. The owner then tears down child pipes
   * and stops the dispatcher before closing stores or destroying storage keys. */
  stop() { this.#finish('closed'); return this.#done; }
}
