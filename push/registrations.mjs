import { DatabaseSync } from 'node:sqlite';
import { createCipheriv, createDecipheriv, createHmac, randomBytes, randomUUID } from 'node:crypto';
import { closeSync, constants, fstatSync, lstatSync, mkdirSync, openSync } from 'node:fs';
import { isAbsolute, join } from 'node:path';

const tupleFields = ['accountID', 'teamID', 'iosBuildID', 'iosInstallationID', 'macDeviceID', 'macInstanceTag', 'macBuildID'];
const text = value => typeof value === 'string' && value.length > 0 && value.length <= 1024 && !/[\u0000-\u001f]/.test(value);
const exact = (value, fields) => value && typeof value === 'object' && !Array.isArray(value) &&
  Object.keys(value).length === fields.length && fields.every(field => Object.hasOwn(value, field));
const canonical = value => JSON.stringify(value, (_, item) => item && typeof item === 'object' && !Array.isArray(item) ?
  Object.fromEntries(Object.keys(item).sort().map(key => [key, item[key]])) : item);
export class RegistrationError extends Error {
  constructor(kind) { super(`Push registrations ${kind}`); this.kind = kind; }
}
function requireInput(condition) { if (!condition) throw new RegistrationError('invalid-input'); }
function recipient(value) {
  requireInput(exact(value, ['installationID', 'keyID', 'senderKeyID', 'tuple']));
  requireInput(['installationID', 'keyID', 'senderKeyID'].every(key => text(value[key])));
  requireInput(value.tuple && !Array.isArray(value.tuple) && Object.keys(value.tuple).every(key => tupleFields.includes(key)));
  requireInput(tupleFields.filter(key => key !== 'teamID').every(key => text(value.tuple[key])));
  requireInput(value.tuple.teamID == null || text(value.tuple.teamID));
  requireInput(value.installationID === value.tuple.iosInstallationID);
  return { ...value, tuple: { ...value.tuple, teamID: value.tuple.teamID ?? null } };
}
function binding(value) {
  requireInput(exact(value, ['registration', 'token', 'recipient']));
  requireInput(exact(value.registration, ['id', 'generation']) && Object.values(value.registration).every(text));
  requireInput(typeof value.token === 'string' && value.token.length > 0 && value.token.length <= 4096 && !/\s/.test(value.token));
  return { registration: value.registration, token: value.token, recipient: recipient(value.recipient) };
}
function enrollment(value) {
  requireInput(exact(value, ['token', 'recipient', 'publicKey']));
  requireInput(typeof value.token === 'string' && value.token.length > 0 && value.token.length <= 4096 && !/\s/.test(value.token));
  const key = typeof value.publicKey === 'string' ? Buffer.from(value.publicKey, 'base64') : Buffer.alloc(0);
  requireInput(key.length === 32 && key.toString('base64') === value.publicKey);
  return { token: value.token, recipient: recipient(value.recipient), publicKey: value.publicKey };
}

/** Durable storage for already-authenticated enrollments. It is not an authentication or network API. */
export class PushRegistrations {
  #db; #key; #capacity;
  constructor({ directory, key, capacity = 128 }) {
    requireInput(isAbsolute(directory ?? '') && Buffer.isBuffer(key) && key.length === 32);
    requireInput(Number.isInteger(capacity) && capacity > 0 && capacity <= 512);
    this.#key = Buffer.from(key); this.#capacity = capacity;
    try {
      mkdirSync(directory, { recursive: true, mode: 0o700 }); this.#private(lstatSync(directory), true);
      const path = join(directory, 'registrations.sqlite');
      const fd = openSync(path, constants.O_CREAT | constants.O_RDWR | constants.O_NOFOLLOW, 0o600);
      try { this.#private(fstatSync(fd), false); } finally { closeSync(fd); }
      this.#db = new DatabaseSync(path, { timeout: 1000 });
      this.#db.exec(`PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA secure_delete=ON; PRAGMA max_page_count=1024;
        CREATE TABLE IF NOT EXISTS meta (id INTEGER PRIMARY KEY CHECK(id=1), proof BLOB NOT NULL);
        CREATE TABLE IF NOT EXISTS registrations (slot TEXT PRIMARY KEY, payload BLOB NOT NULL);
        CREATE TABLE IF NOT EXISTS retired_registrations (slot TEXT PRIMARY KEY, payload BLOB NOT NULL);
        CREATE TABLE IF NOT EXISTS maintenance (request TEXT PRIMARY KEY, payload BLOB NOT NULL, expires INTEGER NOT NULL);`);
      const proof = this.#digest('cmux-push-registrations-key-v1');
      this.#transaction(() => {
        this.#db.prepare('INSERT OR IGNORE INTO meta VALUES (1, ?)').run(Buffer.from(proof));
        if (Buffer.from(this.#db.prepare('SELECT proof FROM meta WHERE id=1').get().proof).toString() !== proof)
          throw new RegistrationError('key-mismatch');
      });
    } catch (error) {
      this.#db?.close(); this.#db = null; this.#key.fill(0);
      throw error instanceof RegistrationError ? error : new RegistrationError('storage-unavailable');
    }
  }
  #private(stat, directory) {
    if (!(directory ? stat.isDirectory() : stat.isFile()) || (process.platform !== 'win32' &&
        ((stat.mode & 0o077) !== 0 || stat.uid !== process.getuid()))) throw new RegistrationError('private-storage-required');
  }
  #digest(value) { return createHmac('sha256', this.#key).update(value).digest('hex'); }
  #slot(recipient) { return this.#digest(canonical(['cmux-push-registration-slot-v1', recipient.tuple])); }
  #transaction(body) {
    if (!this.#db) throw new RegistrationError('closed');
    try { this.#db.exec('BEGIN IMMEDIATE'); } catch { throw new RegistrationError('storage-unavailable'); }
    try { const result = body(); this.#db.exec('COMMIT'); return result; }
    catch (error) {
      try { this.#db.exec('ROLLBACK'); } catch { /* Preserve a coarse failure if rollback also fails. */ }
      throw error instanceof RegistrationError ? error : new RegistrationError('storage-unavailable');
    }
  }
  #encode(slot, value) {
    const raw = canonical(value); requireInput(Buffer.byteLength(raw) <= 24_576);
    const nonce = randomBytes(12), cipher = createCipheriv('aes-256-gcm', this.#key, nonce);
    cipher.setAAD(Buffer.from(`cmux-push-registration-v1:${slot}`));
    return Buffer.concat([nonce, cipher.update(raw, 'utf8'), cipher.final(), cipher.getAuthTag()]);
  }
  #open(row) {
    if (!row) return null;
    try {
      const bytes = Buffer.from(row.payload);
      if (bytes.length < 28 || bytes.length > 24_604) throw new Error();
      const decipher = createDecipheriv('aes-256-gcm', this.#key, bytes.subarray(0, 12));
      decipher.setAAD(Buffer.from(`cmux-push-registration-v1:${row.slot}`)); decipher.setAuthTag(bytes.subarray(-16));
      return JSON.parse(Buffer.concat([decipher.update(bytes.subarray(12, -16)), decipher.final()]).toString('utf8'));
    } catch { throw new RegistrationError('corrupt-record'); }
  }
  #decode(row) {
    if (!row) return null;
    try {
      const value = this.#open(row);
      const checked = enrollment({ token: value.token, recipient: value.recipient, publicKey: value.publicKey });
      requireInput(exact(value, ['registration', 'token', 'recipient', 'publicKey']));
      binding({ registration: value.registration, token: checked.token, recipient: checked.recipient });
      if (this.#slot(checked.recipient) !== row.slot) throw new Error();
      return value;
    } catch { throw new RegistrationError('corrupt-record'); }
  }
  #read(slot, retired = false) { return this.#decode(this.#db.prepare(retired ?
    'SELECT * FROM retired_registrations WHERE slot=?' : 'SELECT * FROM registrations WHERE slot=?').get(slot)); }
  #known(slot) { return this.#read(slot) ?? this.#read(slot, true); }
  #bound(value) { return { registration: value.registration, token: value.token, recipient: value.recipient }; }

  /** The host first authenticates/pins both keys. null means create-only; a replacement needs the exact old generation. */
  replace(value, { expectedGeneration = null, forceGeneration = false } = {}) {
    const checked = enrollment(value); requireInput((expectedGeneration === null || text(expectedGeneration)) && typeof forceGeneration === 'boolean');
    return this.#transaction(() => {
      const slot = this.#slot(checked.recipient), previous = this.#read(slot);
      if ((previous?.registration.generation ?? null) !== expectedGeneration) throw new RegistrationError('superseded');
      if (previous && !forceGeneration && canonical({ token: previous.token, recipient: previous.recipient, publicKey: previous.publicKey }) === canonical(checked))
        return structuredClone(previous);
      if (!previous && !this.#read(slot, true) && this.#db.prepare(
        'SELECT (SELECT COUNT(*) FROM registrations) + (SELECT COUNT(*) FROM retired_registrations) AS count').get().count >= this.#capacity)
        throw new RegistrationError('full');
      const next = { ...checked, registration: { id: previous?.registration.id ?? randomUUID(), generation: randomUUID() } };
      this.#db.prepare('INSERT INTO registrations VALUES (?,?) ON CONFLICT(slot) DO UPDATE SET payload=excluded.payload')
        .run(slot, this.#encode(slot, next));
      this.#db.prepare('DELETE FROM retired_registrations WHERE slot=?').run(slot);
      return structuredClone(next);
    });
  }
  /** This checks durable identity only. The sender must ALSO check live membership and forwarding/privacy/away policy. */
  matches(value) {
    const checked = binding(value);
    return this.#transaction(() => {
      const current = this.#read(this.#slot(checked.recipient));
      return current !== null && canonical(this.#bound(current)) === canonical(checked);
    });
  }
  /** Exact callback for PushOutbox UNREGISTERED handling; a token/key replacement survives an old provider response. */
  retire(value) {
    const checked = binding(value);
    return this.#transaction(() => {
      const slot = this.#slot(checked.recipient), current = this.#read(slot);
      if (current === null || canonical(this.#bound(current)) !== canonical(checked)) return 'superseded';
      // Stop delivery but keep authenticated phone trust for token recovery. Fresh pairing or logout supersedes it.
      this.#db.prepare('INSERT INTO retired_registrations VALUES (?,?) ON CONFLICT(slot) DO UPDATE SET payload=excluded.payload')
        .run(slot, this.#encode(slot, current));
      this.#db.prepare('DELETE FROM registrations WHERE slot=?').run(slot); return 'retired';
    });
  }
  /** Phone-key proof service only. Retired provider tokens retain trust but are excluded from delivery. */
  maintenanceRegistration(target, registration) {
    const checked = recipient(target);
    requireInput(exact(registration, ['id', 'generation']) && Object.values(registration).every(text));
    return this.#transaction(() => {
      const current = this.#known(this.#slot(checked));
      return current && canonical(current.registration) === canonical(registration) && canonical(current.recipient) === canonical(checked)
        ? structuredClone(current) : null;
    });
  }
  maintenanceMatches(value) {
    const checked = binding(value);
    return this.#transaction(() => {
      const current = this.#known(this.#slot(checked.recipient));
      return current !== null && canonical(this.#bound(current)) === canonical(checked);
    });
  }
  #maintenance(requestID, now) {
    requireInput(typeof requestID === 'string' && /^[0-9a-f-]{36}$/.test(requestID) && Number.isSafeInteger(now) && now >= 0);
    this.#db.prepare('DELETE FROM maintenance WHERE expires<=?').run(now);
    const request = this.#digest(`cmux-push-maintenance-v1:${requestID}`);
    const row = this.#db.prepare('SELECT * FROM maintenance WHERE request=?').get(request);
    const value = row ? this.#open({ slot: `maintenance:${request}`, payload: row.payload }) : null;
    if (value) {
      if (value.requestID !== requestID || value.expiresAt !== row.expires || !['renew', 'revoke'].includes(value.action))
        throw new RegistrationError('corrupt-record');
      binding(value.before);
      const current = this.#known(this.#slot(value.before.recipient));
      if (value.after === null ? current !== null : !current || canonical(this.#bound(current)) !== canonical({ ...value.after, recipient: value.before.recipient }))
        throw new RegistrationError('superseded');
    }
    return value;
  }
  /** Internal authenticated-service recovery only; never expose stored proof/bindings over HTTP. */
  maintenanceReceipt(requestID, now = Date.now()) {
    return this.#transaction(() => structuredClone(this.#maintenance(requestID, now)));
  }
  /** Caller has verified phone-key possession and live policy. Mutation and retry receipt commit atomically. */
  maintain({ requestID, digest, proof, action, expected, token = null, expiresAt }, acknowledge, now = Date.now()) {
    const before = binding(expected);
    requireInput(['renew', 'revoke'].includes(action) && typeof digest === 'string' && /^[a-f0-9]{64}$/.test(digest) &&
      typeof proof === 'string' && /^[A-Za-z0-9_-]{43}$/.test(proof) && typeof acknowledge === 'function' &&
      Number.isSafeInteger(expiresAt) && expiresAt > now && expiresAt - now <= 86_400_000);
    requireInput(action === 'renew' ? typeof token === 'string' && token.length > 0 && token.length <= 4096 && !/\s/.test(token) : token === null);
    return this.#transaction(() => {
      const previous = this.#maintenance(requestID, now);
      if (previous) {
        if (previous.digest !== digest || previous.proof !== proof || previous.action !== action || canonical(previous.before) !== canonical(before))
          throw new RegistrationError('superseded');
        return structuredClone(previous);
      }
      const slot = this.#slot(before.recipient), current = this.#known(slot);
      if (!current || canonical(this.#bound(current)) !== canonical(before)) throw new RegistrationError('superseded');
      if (this.#db.prepare('SELECT COUNT(*) AS count FROM maintenance').get().count >= 512) throw new RegistrationError('full');
      const next = action === 'revoke' ? null : { ...current, token,
        registration: { id: current.registration.id, generation: randomUUID() } };
      if (next) this.#db.prepare('INSERT INTO registrations VALUES (?,?) ON CONFLICT(slot) DO UPDATE SET payload=excluded.payload')
        .run(slot, this.#encode(slot, next));
      else this.#db.prepare('DELETE FROM registrations WHERE slot=?').run(slot);
      this.#db.prepare('DELETE FROM retired_registrations WHERE slot=?').run(slot);
      const result = { requestID, digest, proof, action, before, after: next ? { registration: next.registration, token: next.token } : null, expiresAt,
        ack: acknowledge(next ? structuredClone(next.registration) : null) };
      requireInput(result.ack && typeof result.ack === 'object' && !Array.isArray(result.ack));
      const request = this.#digest(`cmux-push-maintenance-v1:${requestID}`);
      this.#db.prepare('INSERT INTO maintenance VALUES (?,?,?)')
        .run(request, this.#encode(`maintenance:${request}`, result), expiresAt);
      return structuredClone(result);
    });
  }
  /** Enumerate only an explicitly selected, independently authorized account/team. No all-accounts broadcast API. */
  recipients({ accountID, teamID = null }) {
    requireInput(text(accountID) && (teamID === null || text(teamID)));
    return this.#transaction(() => this.#db.prepare('SELECT * FROM registrations').all().map(row => this.#decode(row))
      .filter(value => value.recipient.tuple.accountID === accountID && value.recipient.tuple.teamID === teamID));
  }
  /** Called after host logout/team revocation. Corrupt state rolls back the entire operation. */
  revoke({ accountID, teamID = null }) {
    requireInput(text(accountID) && (teamID === null || text(teamID)));
    return this.#transaction(() => {
      const slots = [...this.#db.prepare('SELECT * FROM registrations').all(),
        ...this.#db.prepare('SELECT * FROM retired_registrations').all()].filter(row => {
        const value = this.#decode(row); return value.recipient.tuple.accountID === accountID && value.recipient.tuple.teamID === teamID;
      }).map(row => row.slot);
      const receipts = this.#db.prepare('SELECT * FROM maintenance').all().filter(row => {
        const value = this.#open({ slot: `maintenance:${row.request}`, payload: row.payload });
        binding(value.before);
        return value.before.recipient.tuple.accountID === accountID && value.before.recipient.tuple.teamID === teamID;
      }).map(row => row.request);
      for (const slot of slots) {
        this.#db.prepare('DELETE FROM registrations WHERE slot=?').run(slot);
        this.#db.prepare('DELETE FROM retired_registrations WHERE slot=?').run(slot);
      }
      for (const request of receipts) this.#db.prepare('DELETE FROM maintenance WHERE request=?').run(request);
      return slots.length;
    });
  }
  close() { if (this.#db) { this.#db.close(); this.#db = null; this.#key.fill(0); } }
}
