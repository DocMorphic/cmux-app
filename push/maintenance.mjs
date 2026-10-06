import { createHash, createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { enrollmentFrame, PushEnrollmentError } from './enrollment.mjs';

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const fields = ['accountID', 'teamID', 'iosBuildID', 'iosInstallationID', 'macDeviceID', 'macInstanceTag', 'macBuildID'];
const clone = value => JSON.parse(JSON.stringify(value));
const need = condition => { if (!condition) throw new PushEnrollmentError(); };
const exact = (v, keys) => v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k));
const text = value => typeof value === 'string' && value.length > 0 && value.length <= 1024 && value.isWellFormed() && !/[\u0000-\u001f]/.test(value);
const mac = (key, domain, values) => createHmac('sha256', key).update(enrollmentFrame(domain, values)).digest('base64url');
function verify(actual, expected) {
  need(typeof actual === 'string' && /^[A-Za-z0-9_-]{43}$/.test(actual) && Buffer.from(actual, 'base64url').toString('base64url') === actual);
  need(timingSafeEqual(Buffer.from(actual, 'base64url'), Buffer.from(expected, 'base64url')));
}
export function maintenanceDigest(endpoint, body) {
  need(exact(body, ['requestID', 'registration', 'recipient', 'action', 'token']) && uuid.test(body.requestID));
  need(exact(body.registration, ['id', 'generation']) && Object.values(body.registration).every(v => typeof v === 'string' && uuid.test(v)));
  const recipient = body.recipient;
  need(exact(recipient, ['installationID', 'keyID', 'senderKeyID', 'tuple']) && ['installationID', 'keyID', 'senderKeyID'].every(k => text(recipient[k])));
  need(exact(recipient.tuple, fields) && fields.every(k => k === 'teamID' ? recipient.tuple[k] === null || text(recipient.tuple[k]) : text(recipient.tuple[k])));
  need(recipient.installationID === recipient.tuple.iosInstallationID);
  need(body.action === 'renew' ? typeof body.token === 'string' && body.token.length > 0 && body.token.length <= 4096 &&
    body.token.isWellFormed() && !/[\s\u0000-\u001f]/.test(body.token) : body.action === 'revoke' && body.token === null);
  return createHash('sha256').update(enrollmentFrame('cmux-app.helper.registration.request.v1', [endpoint, body.requestID, body.action,
    body.registration.id, body.registration.generation, recipient.installationID, recipient.keyID, recipient.senderKeyID,
    ...fields.map(k => recipient.tuple[k]), body.token])).digest('hex');
}

/** Existing registration only. Challenge goes to its pinned phone key; request cannot supply a replacement key. */
export class PushMaintenance {
  #rows = new Map(); #seal; #registrations; #permits; #now; #endpoint; #senderPublicKey; #senderKeyID; #capacity; #closed = false;
  constructor({ seal, registrations, permits, endpoint, senderPublicKey, senderKeyID, now = Date.now, capacity = 16 }) {
    const url = new URL(endpoint);
    need(url.protocol === 'https:' && !url.username && !url.password && !url.search && !url.hash && url.origin + '/v1/push/enroll' === endpoint);
    need(typeof seal === 'function' && typeof permits === 'function' && registrations && text(senderKeyID) && typeof senderPublicKey === 'string' &&
      Buffer.from(senderPublicKey, 'base64').length === 32 && Buffer.from(senderPublicKey, 'base64').toString('base64') === senderPublicKey);
    need(Number.isInteger(capacity) && capacity > 0 && capacity <= 128);
    this.#seal = seal; this.#registrations = registrations; this.#permits = permits; this.#now = now;
    this.#endpoint = endpoint; this.#senderPublicKey = senderPublicKey; this.#senderKeyID = senderKeyID; this.#capacity = capacity;
  }
  #prune() {
    need(!this.#closed);
    for (const [id, row] of this.#rows) if (row.expiresAt <= this.#now()) { row.challenge.fill(0); this.#rows.delete(id); }
  }
  #binding(record) { return { registration: clone(record.registration), token: record.token, recipient: clone(record.recipient) }; }
  #admitted(row) {
    need(!this.#closed && this.#rows.get(row.body.requestID) === row && row.expiresAt > this.#now() &&
      row.record.recipient.senderKeyID === this.#senderKeyID && this.#permits(this.#binding(row.record), row.body.action) === true);
    if (!this.#registrations.maintenanceMatches(this.#binding(row.record))) throw new PushEnrollmentError('superseded');
  }
  async begin(input) {
    this.#prune(); const body = clone(input), digest = maintenanceDigest(this.#endpoint, body);
    const previous = this.#rows.get(body.requestID);
    if (previous) {
      need(previous.digest === digest); this.#admitted(previous);
      const result = await previous.pending; this.#admitted(previous); return clone(result);
    }
    if (this.#rows.size >= this.#capacity) throw new PushEnrollmentError('registration-unavailable');
    const record = this.#registrations.maintenanceRegistration(body.recipient, body.registration);
    if (!record) throw new PushEnrollmentError('superseded');
    need(record.recipient.senderKeyID === this.#senderKeyID && this.#permits(this.#binding(record), body.action) === true);
    // One live challenge per registration avoids allocating every slot to an unauthenticated caller.
    if ([...this.#rows.values()].some(row => row.record.registration.id === record.registration.id))
      throw new PushEnrollmentError('registration-unavailable');
    const row = { body, record, digest, challenge: randomBytes(32), expiresAt: this.#now() + 120_000 };
    this.#rows.set(body.requestID, row);
    row.pending = (async () => {
      const plaintext = Buffer.from(JSON.stringify({ version: 1, kind: 'cmux-app.helper.registration', requestID: body.requestID,
        requestDigest: digest, challenge: row.challenge.toString('base64url'), expiresAt: row.expiresAt }));
      try {
        const envelope = await this.#seal({ recipient: clone(record.recipient), publicKey: record.publicKey, plaintext, senderPublicKey: this.#senderPublicKey });
        this.#admitted(row); row.ready = true;
        return { requestID: body.requestID, envelope };
      } catch (error) { this.#rows.delete(body.requestID); row.challenge.fill(0); throw error instanceof PushEnrollmentError ? error : new PushEnrollmentError(); }
      finally { plaintext.fill(0); }
    })();
    return clone(await row.pending);
  }
  finish(input) {
    this.#prune(); const body = clone(input);
    need(exact(body, ['requestID', 'proof']) && uuid.test(body.requestID));
    try {
      const receipt = this.#registrations.maintenanceReceipt(body.requestID, this.#now());
      if (receipt) {
        verify(body.proof, receipt.proof);
        need(receipt.before.recipient.senderKeyID === this.#senderKeyID && this.#permits(clone(receipt.before), receipt.action) === true);
        return clone(receipt.ack);
      }
      const row = this.#rows.get(body.requestID);
      if (!row) throw new PushEnrollmentError('challenge-required');
      need(row.ready); this.#admitted(row);
      const proof = mac(row.challenge, 'cmux-app.helper.registration.finish.v1', [row.digest]);
      verify(body.proof, proof);
      const completed = this.#registrations.maintain({ requestID: body.requestID, digest: row.digest, proof, action: row.body.action,
        expected: this.#binding(row.record), token: row.body.token, expiresAt: this.#now() + 86_400_000 }, registration => ({
          requestID: body.requestID, action: row.body.action, registration,
          proof: mac(row.challenge, 'cmux-app.helper.registration.ack.v1', [row.digest, registration?.id ?? null, registration?.generation ?? null])
        }), this.#now());
      row.challenge.fill(0); this.#rows.delete(body.requestID);
      return clone(completed.ack);
    } catch (error) {
      if (error instanceof PushEnrollmentError) throw error;
      throw new PushEnrollmentError(error.kind === 'superseded' ? 'superseded' : 'registration-unavailable');
    }
  }
  /** Cancels an uncommitted operation, or returns its already-committed receipt. Synchronous with finish. */
  abort(input) {
    this.#prune(); const body = clone(input);
    need(exact(body, ['requestID', 'proof']) && uuid.test(body.requestID));
    try {
      const receipt = this.#registrations.maintenanceReceipt(body.requestID, this.#now());
      if (receipt) {
        verify(body.proof, receipt.proof);
        need(receipt.before.recipient.senderKeyID === this.#senderKeyID && this.#permits(clone(receipt.before), receipt.action) === true);
        return { requestID: body.requestID, ack: clone(receipt.ack) };
      }
      const row = this.#rows.get(body.requestID);
      if (row) {
        need(row.ready); this.#admitted(row);
        verify(body.proof, mac(row.challenge, 'cmux-app.helper.registration.finish.v1', [row.digest]));
        row.challenge.fill(0); this.#rows.delete(body.requestID);
      }
      // No receipt is a transport-level cancellation outcome, never a new trust/registration assertion.
      return { requestID: body.requestID, ack: null };
    } catch (error) {
      if (error instanceof PushEnrollmentError) throw error;
      throw new PushEnrollmentError(error.kind === 'superseded' ? 'superseded' : 'registration-unavailable');
    }
  }
  close() { this.#closed = true; for (const row of this.#rows.values()) row.challenge.fill(0); this.#rows.clear(); }
}
