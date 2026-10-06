import { createHash, createHmac, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const macFields = ['accountID', 'teamID', 'macDeviceID', 'macInstanceTag', 'macBuildID'];
export class PushEnrollmentError extends Error {
  constructor(kind = 'rejected') { super(`Push enrollment ${kind}`); this.kind = kind; }
}
function need(value) { if (!value) throw new PushEnrollmentError(); }
const text = x => typeof x === 'string' && x.length > 0 && x.length <= 1024 && x.isWellFormed() && !/[\u0000-\u001f]/.test(x);
const exact = (v, fields) => v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === fields.length && fields.every(k => Object.hasOwn(v, k));
const clone = value => JSON.parse(JSON.stringify(value));
function bytes(value, size, encoding = 'base64url') {
  need(typeof value === 'string' && value.length <= Math.ceil(size / 3) * 4);
  const b = Buffer.from(value, encoding); need(b.length === size && b.toString(encoding) === value); return b;
}
function publicOffer(offer) { const { secret, ...visible } = offer; return clone(visible); }
function descriptor(value) {
  need(exact(value, ['version', 'algorithm', 'installationID', 'keyID', 'publicKey']) && value.version === 1 && value.algorithm === 'rawX25519');
  need(text(value.installationID) && text(value.keyID)); bytes(value.publicKey, 32, 'base64');
  return [value.installationID, value.keyID, value.publicKey];
}
/** Length-prefixed UTF-8 fields avoid cross-language JSON escaping/canonicalization differences. */
export function enrollmentFrame(domain, fields) {
  return Buffer.concat([domain, ...fields].flatMap(value => {
    const header = Buffer.alloc(4);
    if (value === null) { header.writeInt32BE(-1); return [header]; }
    need(typeof value === 'string' && value.isWellFormed());
    const b = Buffer.from(value); need(b.length <= 16_384); header.writeInt32BE(b.length); return [header, b];
  }));
}
export function enrollmentOfferDigest(offer) {
  need(exact(offer, ['version', 'offerID', 'expiresAt', 'endpoint', 'project', 'phoneBuildID', 'mac', 'native', 'helper', 'secret']));
  need(offer.version === 1 && uuid.test(offer.offerID) && Number.isSafeInteger(offer.expiresAt) && offer.expiresAt > 0);
  const url = new URL(offer.endpoint);
  need(url.protocol === 'https:' && !url.username && !url.password && !url.search && !url.hash && url.origin + '/v1/push/enroll' === offer.endpoint);
  need(exact(offer.project, ['project', 'application', 'sender']) && Object.values(offer.project).every(text) && text(offer.phoneBuildID));
  need(exact(offer.mac, macFields) && macFields.every(k => k === 'teamID' ? offer.mac[k] === null || text(offer.mac[k]) : text(offer.mac[k])));
  const native = descriptor(offer.native), helper = descriptor(offer.helper);
  need(native.every((value, i) => value !== helper[i])); bytes(offer.secret, 32);
  return createHash('sha256').update(enrollmentFrame('cmux-app.helper.offer.v1', [offer.offerID, String(offer.expiresAt), offer.endpoint,
    offer.project.project, offer.project.application, offer.project.sender, offer.phoneBuildID,
    ...macFields.map(k => offer.mac[k]), ...native, ...helper])).digest('hex');
}
function requestDigest(offerDigest, body) {
  need(exact(body, ['offerID', 'requestID', 'phone', 'token', 'proof']) && uuid.test(body.offerID) && uuid.test(body.requestID));
  need(typeof body.token === 'string' && body.token.length > 0 && body.token.length <= 4096 && !/[\s\u0000-\u001f]/.test(body.token) && body.token.isWellFormed());
  return createHash('sha256').update(enrollmentFrame('cmux-app.helper.request.v1', [offerDigest, body.requestID,
    ...descriptor(body.phone), body.token])).digest('hex');
}
function mac(key, domain, fields) { return createHmac('sha256', key).update(enrollmentFrame(domain, fields)).digest(); }
function verify(key, proof, domain, fields) { need(timingSafeEqual(bytes(proof, 32), mac(key, domain, fields))); }

/** In-memory short-lived offers; registration commits use the durable encrypted store.
 * No listener or authority discovery. Host supplies live, independently verified scope and key material.
 */
export class PushEnrollment {
  #offers = new Map(); #seal; #registrations; #permits; #now; #capacity; #closed = false;
  constructor({ seal, registrations, permits, now = Date.now, capacity = 16 }) {
    need(typeof seal === 'function' && typeof permits === 'function' && registrations && Number.isInteger(capacity) && capacity > 0 && capacity <= 128);
    this.#seal = seal; this.#registrations = registrations; this.#permits = permits; this.#now = now; this.#capacity = capacity;
  }
  #dispose(row) { row.secret.fill(0); row.challenge?.fill(0); }
  #prune() {
    for (const [id, row] of this.#offers) if (row.offer.expiresAt <= this.#now()) { this.#dispose(row); this.#offers.delete(id); }
  }
  #admitted(row) {
    need(!this.#closed && this.#offers.get(row.offer.offerID) === row && this.#now() < row.offer.expiresAt);
    // A promise/truthy value is not authorization; the host policy must be a current synchronous decision.
    need(this.#permits(publicOffer(row.offer)) === true);
  }
  issue(input, lifetime = 120_000) {
    this.#prune(); need(!this.#closed && this.#offers.size < this.#capacity && Number.isSafeInteger(lifetime) && lifetime >= 1000 && lifetime <= 300_000);
    const secret = randomBytes(32), offer = { ...clone(input), version: 1, offerID: randomUUID(), expiresAt: this.#now() + lifetime, secret: secret.toString('base64url') };
    need(Buffer.byteLength(JSON.stringify(offer)) <= 8192);
    const digest = enrollmentOfferDigest(offer), row = { offer, digest, secret };
    need(this.#permits(publicOffer(offer)) === true);
    this.#offers.set(offer.offerID, row); return clone(offer);
  }
  async begin(input) {
    this.#prune(); const body = clone(input), row = this.#offers.get(body.offerID); need(row); this.#admitted(row);
    const digest = requestDigest(row.digest, body);
    verify(row.secret, body.proof, 'cmux-app.helper.begin.v1', [row.digest, digest]);
    if (row.requestDigest) { need(row.requestDigest === digest); const response = await row.pending; this.#admitted(row); return clone(response); }
    const tuple = { ...row.offer.mac, iosBuildID: row.offer.phoneBuildID, iosInstallationID: body.phone.installationID };
    const recipient = { installationID: body.phone.installationID, keyID: body.phone.keyID, senderKeyID: row.offer.helper.keyID, tuple };
    const previous = this.#registrations.recipients({ accountID: tuple.accountID, teamID: tuple.teamID })
      .find(r => r.recipient.tuple.iosBuildID === tuple.iosBuildID && r.recipient.tuple.iosInstallationID === tuple.iosInstallationID &&
        r.recipient.tuple.macDeviceID === tuple.macDeviceID && r.recipient.tuple.macInstanceTag === tuple.macInstanceTag && r.recipient.tuple.macBuildID === tuple.macBuildID);
    row.requestDigest = digest; row.requestID = body.requestID; row.challenge = randomBytes(32);
    row.enrollment = { token: body.token, recipient, publicKey: body.phone.publicKey };
    row.expectedGeneration = previous?.registration.generation ?? null;
    row.pending = (async () => {
      const plaintext = Buffer.from(JSON.stringify({ version: 1, kind: 'cmux-app.helper.enrollment', offerID: row.offer.offerID,
        requestID: row.requestID, offerDigest: row.digest, requestDigest: digest, challenge: row.challenge.toString('base64url'), expiresAt: row.offer.expiresAt }));
      try {
        const envelope = await this.#seal({ recipient: clone(recipient), publicKey: body.phone.publicKey, plaintext, senderPublicKey: row.offer.helper.publicKey });
        this.#admitted(row); row.ready = true;
        return { offerID: row.offer.offerID, requestID: row.requestID, envelope };
      } catch { this.#offers.delete(row.offer.offerID); this.#dispose(row); throw new PushEnrollmentError(); }
      finally { plaintext.fill(0); }
    })();
    return clone(await row.pending);
  }
  finish(input) {
    this.#prune(); const body = clone(input); need(exact(body, ['offerID', 'requestID', 'proof']));
    const row = this.#offers.get(body.offerID); need(row && row.ready && body.requestID === row.requestID); this.#admitted(row);
    verify(row.challenge, body.proof, 'cmux-app.helper.finish.v1', [row.digest, row.requestDigest]);
    if (row.record) need(this.#registrations.matches({ registration: row.record.registration, token: row.record.token, recipient: row.record.recipient }));
    else {
      try { row.record = this.#registrations.replace(row.enrollment, { expectedGeneration: row.expectedGeneration }); }
      catch { throw new PushEnrollmentError('registration-unavailable'); }
    }
    return { offerID: row.offer.offerID, requestID: row.requestID, registration: clone(row.record.registration),
      proof: mac(row.challenge, 'cmux-app.helper.ack.v1', [row.digest, row.requestDigest, row.record.registration.id, row.record.registration.generation]).toString('base64url') };
  }
  cancel(offerID) { const row = this.#offers.get(offerID); if (row) this.#dispose(row); this.#offers.delete(offerID); }
  close() { this.#closed = true; for (const row of this.#offers.values()) this.#dispose(row); this.#offers.clear(); }
}
