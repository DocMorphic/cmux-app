import { DatabaseSync } from 'node:sqlite';
import { createCipheriv, createDecipheriv, createHmac, randomBytes, randomUUID } from 'node:crypto';
import { closeSync, constants, fstatSync, lstatSync, mkdirSync, openSync } from 'node:fs';
import { isAbsolute, join } from 'node:path';

const MAX_BYTES = 4 * 1024 * 1024;
const LEASE_MS = 60_000;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const canonical = value => JSON.stringify(value, (_, v) => v && typeof v === 'object' && !Array.isArray(v) ?
  Object.fromEntries(Object.keys(v).sort().map(k => [k, v[k]])) : v);
const exact = (value, keys) => value && typeof value === 'object' && !Array.isArray(value) &&
  Object.keys(value).length === keys.length && keys.every(k => Object.hasOwn(value, k));
export class SourceJournalError extends Error {
  constructor(kind) { super(`Push source journal ${kind}`); this.kind = kind; }
}
function requireInput(value) { if (!value) throw new SourceJournalError('invalid-input'); }

/** Durable handoff for already-authenticated source records. It never establishes source authority.
 * Acknowledge capture only after accept() succeeds. Finish source replay only after a terminal drain outcome.
 * Requests and prepared ciphertext are encrypted; the same ciphertext is replayed after an uncertain queue commit.
 */
export class PushSourceJournal {
  #db; #key; #now; #capacity; #active = 0;
  constructor({ directory, key, now = Date.now, capacity = 128 }) {
    requireInput(isAbsolute(directory ?? '') && Buffer.isBuffer(key) && key.length === 32 && typeof now === 'function');
    requireInput(Number.isInteger(capacity) && capacity > 0 && capacity <= 512);
    this.#key = Buffer.from(key); this.#now = now; this.#capacity = capacity;
    try {
      mkdirSync(directory, { recursive: true, mode: 0o700 }); this.#private(lstatSync(directory), true);
      const path = join(directory, 'source.sqlite');
      const fd = openSync(path, constants.O_CREAT | constants.O_RDWR | constants.O_NOFOLLOW, 0o600);
      try { this.#private(fstatSync(fd), false); } finally { closeSync(fd); }
      this.#db = new DatabaseSync(path, { timeout: 1000 });
      this.#db.exec(`PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA secure_delete=ON; PRAGMA max_page_count=2048;
        CREATE TABLE IF NOT EXISTS meta (id INTEGER PRIMARY KEY CHECK(id=1), proof TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS source (
          id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, payload BLOB NOT NULL,
          expires INTEGER NOT NULL, due INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'pending',
          attempts INTEGER NOT NULL DEFAULT 0, owner TEXT, lease INTEGER NOT NULL DEFAULT 0, outcome TEXT
        );`);
      this.#transaction(() => {
        const proof = this.#digest('cmux-push-source-key-v1');
        this.#db.prepare('INSERT OR IGNORE INTO meta VALUES (1, ?)').run(proof);
        if (this.#db.prepare('SELECT proof FROM meta WHERE id=1').get().proof !== proof) throw new SourceJournalError('key-mismatch');
      });
    } catch (error) {
      this.#db?.close(); this.#db = null; this.#key.fill(0);
      throw error instanceof SourceJournalError ? error : new SourceJournalError('storage-unavailable');
    }
  }
  #private(stat, directory) {
    if (!(directory ? stat.isDirectory() : stat.isFile()) || (process.platform !== 'win32' &&
        ((stat.mode & 0o077) !== 0 || stat.uid !== process.getuid()))) throw new SourceJournalError('private-storage-required');
  }
  #time() { const n = this.#now(); requireInput(Number.isSafeInteger(n) && n >= 0); return n; }
  #digest(value) { return createHmac('sha256', this.#key).update(value).digest('hex'); }
  #transaction(body) {
    if (!this.#db) throw new SourceJournalError('closed');
    try { this.#db.exec('BEGIN IMMEDIATE'); } catch { throw new SourceJournalError('storage-unavailable'); }
    try { const result = body(); this.#db.exec('COMMIT'); return result; }
    catch (error) {
      try { this.#db.exec('ROLLBACK'); } catch { }
      throw error instanceof SourceJournalError ? error : new SourceJournalError('storage-unavailable');
    }
  }
  #encrypt(id, expires, value) {
    const bytes = Buffer.from(canonical(value)); requireInput(bytes.length <= MAX_BYTES);
    try {
      const nonce = randomBytes(12), cipher = createCipheriv('aes-256-gcm', this.#key, nonce);
      cipher.setAAD(Buffer.from(`cmux-push-source-v1:${id}:${expires}`));
      return Buffer.concat([nonce, cipher.update(bytes), cipher.final(), cipher.getAuthTag()]);
    } finally { bytes.fill(0); }
  }
  #read(row) {
    let clear;
    try {
      const bytes = Buffer.from(row.payload);
      if (bytes.length < 28 || bytes.length > MAX_BYTES + 28) throw new Error();
      const cipher = createDecipheriv('aes-256-gcm', this.#key, bytes.subarray(0, 12));
      cipher.setAAD(Buffer.from(`cmux-push-source-v1:${row.id}:${row.expires}`)); cipher.setAuthTag(bytes.subarray(-16));
      clear = Buffer.concat([cipher.update(bytes.subarray(12, -16)), cipher.final()]);
      const record = JSON.parse(clear);
      if (record.request.expiresAt !== row.expires || this.#digest(canonical(record.request)) !== row.fingerprint ||
          !['pending', 'prepared'].includes(row.state) || (row.state === 'prepared' && record.prepared?.kind !== 'prepared')) throw new Error();
      return record;
    } catch { throw new SourceJournalError('corrupt-record'); }
    finally { clear?.fill(0); }
  }
  #prune(now) { this.#db.prepare('DELETE FROM source WHERE expires <= ?').run(now); }
  accept(value) {
    requireInput(exact(value, ['event', 'expiresAt', 'sourceEpoch', 'phoneEligible']));
    requireInput(typeof value.sourceEpoch === 'string' && value.sourceEpoch.length > 0 && value.sourceEpoch.length <= 1024 &&
      typeof value.phoneEligible === 'boolean' && value.event && ['notify', 'dismiss'].includes(value.event.kind) && uuid.test(value.event.correlationID));
    const now = this.#time(), expires = value.expiresAt;
    requireInput(Number.isSafeInteger(expires) && expires % 1000 === 0 && expires - now <= 900_000);
    if (expires - now < 1000) return { kind: 'expired' };
    let raw;
    try { raw = canonical(value); } catch { throw new SourceJournalError('invalid-input'); }
    requireInput(Buffer.byteLength(raw) <= 1_048_576);
    const request = JSON.parse(raw), fingerprint = this.#digest(raw);
    // Source epochs separate account incarnations even when a producer reuses an event UUID.
    const id = this.#digest(canonical([request.sourceEpoch, request.event.correlationID]));
    return this.#transaction(() => {
      this.#prune(now);
      const old = this.#db.prepare('SELECT fingerprint,state,outcome FROM source WHERE id=?').get(id);
      if (old) {
        if (old.fingerprint !== fingerprint) throw new SourceJournalError('identity-conflict');
        return { kind: 'duplicate', id, outcome: old.state === 'done' ? old.outcome : null };
      }
      if (this.#db.prepare('SELECT COUNT(*) AS count FROM source').get().count >= this.#capacity) throw new SourceJournalError('full');
      this.#db.prepare('INSERT INTO source(id,fingerprint,payload,expires,due) VALUES (?,?,?,?,?)')
        .run(id, fingerprint, this.#encrypt(id, expires, { request }), expires, now);
      return { kind: 'captured', id };
    });
  }
  #claim() {
    return this.#transaction(() => {
      const now = this.#time(); this.#prune(now);
      const row = this.#db.prepare("SELECT * FROM source WHERE state != 'done' AND due <= ? AND lease <= ? ORDER BY due,id LIMIT 1").get(now, now);
      if (!row) return null;
      const owner = randomUUID(), lease = now + LEASE_MS;
      this.#db.prepare('UPDATE source SET owner=?,lease=?,attempts=attempts+1 WHERE id=?').run(owner, lease, row.id);
      return { ...row, owner, lease, attempts: row.attempts + 1 };
    });
  }
  #owns(row) {
    const now = this.#time();
    return !!this.#db.prepare("SELECT 1 FROM source WHERE id=? AND owner=? AND lease>? AND expires>? AND state!='done'")
      .get(row.id, row.owner, now, now);
  }
  #finish(row, outcome) {
    if (!this.#owns(row)) return { kind: 'lease-lost', id: row.id };
    this.#db.prepare("UPDATE source SET state='done',outcome=?,payload=X'',owner=NULL,lease=0 WHERE id=? AND owner=?")
      .run(outcome, row.id, row.owner);
    return { kind: outcome, id: row.id };
  }
  #retry(row, reason = 'unavailable') {
    if (!this.#owns(row)) return { kind: 'lease-lost', id: row.id };
    const due = Math.min(row.expires, this.#time() + Math.min(30_000, 1000 * 2 ** Math.min(row.attempts - 1, 5)));
    this.#db.prepare('UPDATE source SET due=?,owner=NULL,lease=0 WHERE id=? AND owner=?').run(due, row.id, row.owner);
    return { kind: reason, id: row.id, retryAt: due };
  }
  /** Encrypted preparation commits before synchronous delivery-queue admission. */
  async drain({ forwarder, limit = 8 }) {
    requireInput(typeof forwarder?.prepare === 'function' && typeof forwarder?.enqueue === 'function' &&
      Number.isInteger(limit) && limit > 0 && limit <= 32);
    this.#active++;
    try {
      const outcomes = [];
      for (let i = 0; i < limit; i++) {
        const row = this.#claim(); if (!row) break;
        try {
          const record = this.#read(row);
          let prepared = record.prepared;
          if (row.state === 'pending') {
            prepared = await forwarder.prepare(record.request);
            if (!this.#owns(row)) { outcomes.push({ kind: 'lease-lost', id: row.id }); continue; }
            if (['suppressed', 'retired', 'expired'].includes(prepared?.kind)) { outcomes.push(this.#finish(row, prepared.kind)); continue; }
            if (prepared?.kind !== 'prepared') { outcomes.push(this.#retry(row)); continue; }
            // A lost commit result can safely replay this durable ciphertext on the next pass.
            this.#db.prepare("UPDATE source SET state='prepared',payload=? WHERE id=? AND owner=?")
              .run(this.#encrypt(row.id, row.expires, { ...record, prepared }), row.id, row.owner);
          }
          if (!this.#owns(row)) { outcomes.push({ kind: 'lease-lost', id: row.id }); continue; }
          const result = forwarder.enqueue(prepared);
          if (result?.kind === 'queued') outcomes.push(this.#finish(row, 'queued'));
          else if (['retired', 'expired'].includes(result?.kind)) outcomes.push(this.#finish(row, result.kind));
          else outcomes.push(this.#retry(row));
        } catch (error) {
          // A corrupt journal cannot be acknowledged as processed. Retain it for explicit repair/expiry.
          outcomes.push(this.#retry(row, error?.kind === 'corrupt-record' ? 'corrupt-record' : 'unavailable'));
        }
      }
      return outcomes;
    } finally { this.#active--; }
  }
  status() {
    return this.#transaction(() => {
      this.#prune(this.#time());
      return { counts: Object.fromEntries(this.#db.prepare('SELECT state,COUNT(*) AS count FROM source GROUP BY state').all().map(r => [r.state, r.count])),
        nextDueAt: this.#db.prepare("SELECT MIN(CASE WHEN state='done' THEN expires ELSE MIN(MAX(due,lease),expires) END) AS due FROM source").get().due ?? null };
    });
  }
  close() {
    if (this.#active) throw new SourceJournalError('busy');
    this.#db?.close(); this.#db = null; this.#key.fill(0);
  }
}
