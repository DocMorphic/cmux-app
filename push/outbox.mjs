import { DatabaseSync } from 'node:sqlite';
import { createCipheriv, createDecipheriv, createHmac, randomBytes, randomUUID } from 'node:crypto';
import { closeSync, constants, fstatSync, lstatSync, mkdirSync, openSync } from 'node:fs';
import { isAbsolute, join } from 'node:path';
import { fcmMessage } from './fcm.mjs';

const LEASE_MS = 60_000;
const MAX_RECORD_BYTES = 32_768;
const text = value => typeof value === 'string' && value.length > 0 && value.length <= 1024;
const exact = (value, keys) => value && typeof value === 'object' && !Array.isArray(value) &&
  Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key));
const canonical = value => JSON.stringify(value, (_, v) => v && typeof v === 'object' && !Array.isArray(v) ?
  Object.fromEntries(Object.keys(v).sort().map(key => [key, v[key]])) : v);
function frozenCopy(value) {
  const copy = structuredClone(value);
  const freeze = item => { if (item && typeof item === 'object') { Object.values(item).forEach(freeze); Object.freeze(item); } };
  freeze(copy); return copy;
}
export class OutboxError extends Error {
  constructor(kind) { super(`Push outbox ${kind}`); this.kind = kind; }
}
function requireInput(condition) { if (!condition) throw new OutboxError('invalid-input'); }

/** Local-disk queue. Keys and independently authenticated registration state belong to its host. */
export class PushOutbox {
  #db; #key; #now; #random; #capacity; #active = 0;
  constructor({ directory, key, now = Date.now, random = Math.random, capacity = 128 }) {
    requireInput(isAbsolute(directory ?? '') && Buffer.isBuffer(key) && key.length === 32);
    requireInput(Number.isInteger(capacity) && capacity > 0 && capacity <= 512);
    requireInput(typeof now === 'function' && typeof random === 'function');
    this.#key = Buffer.from(key); this.#now = now; this.#random = random; this.#capacity = capacity;
    try {
      mkdirSync(directory, { recursive: true, mode: 0o700 });
      this.#privateFile(lstatSync(directory), true);
      const path = join(directory, 'outbox.sqlite');
      const fd = openSync(path, constants.O_CREAT | constants.O_RDWR | constants.O_NOFOLLOW, 0o600);
      try { this.#privateFile(fstatSync(fd), false); } finally { closeSync(fd); }
      this.#db = new DatabaseSync(path, { timeout: 1000, enableForeignKeyConstraints: true });
      this.#db.exec(`PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;
        PRAGMA max_page_count=2048; PRAGMA secure_delete=ON;
        CREATE TABLE IF NOT EXISTS meta (id INTEGER PRIMARY KEY CHECK(id=1), proof BLOB NOT NULL);
        CREATE TABLE IF NOT EXISTS jobs (
          id TEXT PRIMARY KEY, payload BLOB NOT NULL, expires INTEGER NOT NULL,
          due INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
          state TEXT NOT NULL DEFAULT 'pending', reason TEXT,
          owner TEXT, lease INTEGER NOT NULL DEFAULT 0
        );`);
      const proof = this.#digest('cmux-push-outbox-v1');
      this.#transaction(() => {
        this.#db.prepare('INSERT OR IGNORE INTO meta VALUES (1, ?)').run(Buffer.from(proof));
        if (Buffer.from(this.#db.prepare('SELECT proof FROM meta WHERE id=1').get().proof).toString() !== proof)
          throw new OutboxError('key-mismatch');
      });
    } catch (error) {
      this.#db?.close(); this.#key.fill(0);
      throw error instanceof OutboxError ? error : new OutboxError('storage-unavailable');
    }
  }
  #privateFile(stat, directory) {
    if (!(directory ? stat.isDirectory() : stat.isFile()) ||
        (process.platform !== 'win32' && ((stat.mode & 0o077) !== 0 || stat.uid !== process.getuid())))
      throw new OutboxError('private-storage-required');
  }
  #time() {
    const value = this.#now(); requireInput(Number.isSafeInteger(value) && value >= 0); return value;
  }
  #digest(value) { return createHmac('sha256', this.#key).update(value).digest('hex'); }
  #transaction(body) {
    if (!this.#db) throw new OutboxError('closed');
    this.#db.exec('BEGIN IMMEDIATE');
    try { const result = body(); this.#db.exec('COMMIT'); return result; }
    catch (error) { this.#db.exec('ROLLBACK'); throw error; }
  }
  #encrypt(id, expires, raw) {
    const nonce = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', this.#key, nonce);
    cipher.setAAD(Buffer.from(`cmux-outbox-v1:${id}:${expires}`));
    return Buffer.concat([nonce, cipher.update(raw, 'utf8'), cipher.final(), cipher.getAuthTag()]);
  }
  #decrypt(row) {
    try {
      const data = Buffer.from(row.payload);
      if (data.length < 28 || data.length > MAX_RECORD_BYTES + 28) throw new Error();
      const decipher = createDecipheriv('aes-256-gcm', this.#key, data.subarray(0, 12));
      decipher.setAAD(Buffer.from(`cmux-outbox-v1:${row.id}:${row.expires}`));
      decipher.setAuthTag(data.subarray(-16));
      const raw = Buffer.concat([decipher.update(data.subarray(12, -16)), decipher.final()]).toString('utf8');
      const job = JSON.parse(raw);
      if (job.delivery.expiresAt !== row.expires) throw new Error();
      return { raw, job };
    } catch { throw new OutboxError('corrupt-record'); }
  }
  #prune(now) { this.#db.prepare('DELETE FROM jobs WHERE expires <= ?').run(now); }

  /** One stable event ID per registration generation; duplicates cannot renew expiry or replace ciphertext. */
  enqueue(value) {
    requireInput(exact(value, ['eventID', 'registration', 'delivery']));
    requireInput(text(value.eventID) && exact(value.registration, ['id', 'generation']) &&
      text(value.registration.id) && text(value.registration.generation));
    requireInput(exact(value.delivery, ['token', 'envelope', 'recipient', 'expiresAt']));
    const raw = canonical(value); requireInput(Buffer.byteLength(raw) <= MAX_RECORD_BYTES);
    const job = JSON.parse(raw); const now = this.#time();
    if (!fcmMessage(job.delivery, now)) return { kind: 'expired' };
    const id = this.#digest(canonical([job.eventID, job.registration.id, job.registration.generation]));
    return this.#transaction(() => {
      this.#prune(now);
      const existing = this.#db.prepare('SELECT * FROM jobs WHERE id=?').get(id);
      if (existing) {
        if (existing.state !== 'done' && this.#decrypt(existing).raw !== raw) throw new OutboxError('identity-conflict');
        return { kind: 'duplicate' };
      }
      if (this.#db.prepare('SELECT COUNT(*) AS count FROM jobs').get().count >= this.#capacity)
        throw new OutboxError('full'); // Never evict a fresh event or its deduplication receipt silently.
      this.#db.prepare('INSERT INTO jobs(id,payload,expires,due) VALUES (?,?,?,?)')
        .run(id, this.#encrypt(id, job.delivery.expiresAt, raw), job.delivery.expiresAt, now);
      return { kind: 'queued' };
    });
  }
  #claim() {
    const now = this.#time();
    return this.#transaction(() => {
      this.#prune(now);
      return this.#db.prepare(`UPDATE jobs SET owner=?, lease=?, attempts=attempts+1
        WHERE id=(SELECT id FROM jobs WHERE state IN ('pending','retiring') AND due<=? AND lease<=?
          ORDER BY due,id LIMIT 1) RETURNING *`).get(randomUUID(), now + LEASE_MS, now, now);
    });
  }
  #owns(row) {
    const current = this.#db?.prepare('SELECT owner,lease,expires FROM jobs WHERE id=?').get(row.id);
    const now = this.#time();
    return !!current && current.owner === row.owner && current.lease > now && current.expires > now;
  }
  #finish(row, kind) {
    if (!this.#owns(row)) return { kind: 'lease-lost' };
    const changed = this.#db.prepare(`UPDATE jobs SET state='done',reason=?,payload=X'',owner=NULL,lease=0 WHERE id=? AND owner=?`)
      .run(kind, row.id, row.owner);
    return { kind: changed.changes === 1 ? kind : 'lease-lost' };
  }
  #retry(row, outcome) {
    if (!this.#owns(row)) return { kind: 'lease-lost' };
    const now = this.#time();
    const jitter = this.#random(); requireInput(Number.isFinite(jitter) && jitter >= 0 && jitter < 1);
    const provider = Number.isFinite(outcome.retryAfterMs) ? Math.max(0, outcome.retryAfterMs) : 1000;
    const delay = Math.max(provider, Math.ceil(Math.min(60_000, 1000 * 2 ** Math.min(row.attempts - 1, 6)) * (1 + jitter)));
    const due = Math.min(row.expires, now + delay);
    const changed = this.#db.prepare('UPDATE jobs SET due=?,owner=NULL,lease=0,reason=? WHERE id=? AND owner=?')
      .run(due, 'unavailable', row.id, row.owner);
    return changed.changes === 1 ? { kind: 'unavailable', retryAt: due } : { kind: 'lease-lost' };
  }
  async #retire(row, binding, retire) {
    if (!this.#owns(row)) return { kind: 'lease-lost' };
    try {
      // The host MUST implement durable, idempotent compare-and-retire of every field in binding.
      const result = await retire(binding);
      if (result !== 'retired' && result !== 'superseded') throw new Error();
      return this.#finish(row, 'unregistered');
    } catch { return this.#retry(row, { retryAfterMs: 1000 }); }
  }
  /** A bounded pass; the host schedules the next pass and owns registration/trust/policy state. */
  async drain({ sender, permits = () => false, retire = async () => { throw new Error(); }, limit = 8 }) {
    requireInput(typeof sender?.send === 'function' && typeof permits === 'function' && typeof retire === 'function');
    requireInput(Number.isInteger(limit) && limit > 0 && limit <= 64);
    this.#active++;
    try {
      const results = [];
      for (let i = 0; i < limit; i++) {
        const row = this.#claim(); if (!row) break;
        const { job } = this.#decrypt(row);
        const binding = frozenCopy({ registration: job.registration, token: job.delivery.token, recipient: job.delivery.recipient });
        if (row.state === 'retiring') { results.push(await this.#retire(row, binding, retire)); continue; }
        const admitted = () => this.#owns(row) && job.delivery.expiresAt - this.#time() >= 1000 && permits(binding) === true;
        let outcome;
        try {
          outcome = job.delivery.expiresAt - this.#time() < 1000 ? { kind: 'expired' } :
            admitted() ? await sender.send(frozenCopy(job.delivery), { permits: admitted }) : { kind: 'retired' };
        } catch { outcome = { kind: 'unavailable', retryAfterMs: 1000 }; }
        if (!this.#owns(row)) { results.push({ kind: 'lease-lost' }); continue; }
        if (['accepted', 'expired', 'retired'].includes(outcome?.kind)) results.push(this.#finish(row, outcome.kind));
        else if (outcome?.kind === 'unregistered') {
          // Persist provider evidence before touching registration state; a crash resumes retirement, not sending.
          const changed = this.#db.prepare("UPDATE jobs SET state='retiring' WHERE id=? AND owner=?").run(row.id, row.owner);
          results.push(changed.changes === 1 ? await this.#retire(row, binding, retire) : { kind: 'lease-lost' });
        } else if (['credentials', 'rejected'].includes(outcome?.kind)) {
          const changed = this.#db.prepare("UPDATE jobs SET state='blocked',reason=?,owner=NULL,lease=0 WHERE id=? AND owner=?")
            .run(outcome.kind, row.id, row.owner);
          results.push({ kind: changed.changes === 1 ? outcome.kind : 'lease-lost' });
        } else results.push(this.#retry(row, outcome ?? {}));
      }
      return results;
    } finally { this.#active--; }
  }
  /** Explicit operator recovery after credentials/configuration are fixed; never renews event expiry. */
  resumeBlocked(reason) {
    requireInput(['credentials', 'rejected'].includes(reason));
    const now = this.#time();
    return this.#transaction(() => {
      this.#prune(now);
      return this.#db.prepare("UPDATE jobs SET state='pending',due=?,reason=NULL WHERE state='blocked' AND reason=?")
        .run(now, reason).changes;
    });
  }
  status() {
    return this.#transaction(() => {
      this.#prune(this.#time());
      return {
        counts: Object.fromEntries(this.#db.prepare('SELECT state,COUNT(*) AS count FROM jobs GROUP BY state').all().map(r => [r.state, r.count])),
        // Wake for expiry even when every entry is blocked/completed; retention needs host scheduling.
        nextDueAt: this.#db.prepare(`SELECT MIN(CASE WHEN state IN ('pending','retiring')
          THEN MIN(MAX(due,lease),expires) ELSE expires END) AS due FROM jobs`).get().due ?? null
      };
    });
  }
  close() {
    if (this.#active) throw new OutboxError('busy');
    this.#db?.close(); this.#db = null; this.#key.fill(0);
  }
}
