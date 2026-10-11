import { createHash, createHmac, timingSafeEqual } from 'node:crypto';
import { readPushHost } from './policy.mjs';

const MAX_BODY = 1_048_576;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const exact = (value, fields) => value && typeof value === 'object' && !Array.isArray(value) &&
  Object.keys(value).length === fields.length && fields.every(field => Object.hasOwn(value, field));
const text = (value, maximum = 1024) => typeof value === 'string' && value.length > 0 && value.length <= maximum &&
  value.isWellFormed() && !/[\u0000-\u001f]/.test(value);
export class NativePushSourceError extends Error {
  constructor() { super('Native push source rejected'); }
}
const need = condition => { if (!condition) throw new NativePushSourceError(); };
function base64(value, maximum) {
  need(typeof value === 'string' && value.length <= 4 * Math.ceil(maximum / 3));
  const bytes = Buffer.from(value, 'base64');
  need(bytes.length <= maximum && bytes.toString('base64') === value); return bytes;
}
function authenticationFrame(sessionID, sequence, body) {
  const values = [Buffer.from('cmux-app.native.push.v1'), Buffer.from(sessionID), Buffer.from(String(sequence)), body];
  return Buffer.concat(values.flatMap(value => { const size = Buffer.alloc(4); size.writeUInt32BE(value.length); return [size, value]; }));
}
/** Native producer codec. The fresh session/key must come from a private bootstrap,
 * never a phone request, event, log, command argument or enrollment offer. */
export function encodeNativePushFrame({ sessionID, sequence, body, key }) {
  need(uuid.test(sessionID) && Number.isSafeInteger(sequence) && sequence >= 1 && sequence < Number.MAX_SAFE_INTEGER && Buffer.isBuffer(key) && key.length === 32);
  const bytes = Buffer.from(JSON.stringify(body)); need(bytes.length <= MAX_BODY);
  return { version: 1, sessionID, sequence, body: bytes.toString('base64'),
    proof: createHmac('sha256', key).update(authenticationFrame(sessionID, sequence, bytes)).digest('base64') };
}
function validateRequest(request) {
  need(exact(request, ['event', 'expiresAt', 'sourceEpoch', 'phoneEligible']) && text(request.sourceEpoch) &&
    typeof request.phoneEligible === 'boolean' && Number.isSafeInteger(request.expiresAt) && request.expiresAt % 1000 === 0);
  const e = request.event;
  const common = ['kind', 'correlationID', 'badgeCount', 'hideContent'];
  need(e && uuid.test(e.correlationID) && Number.isInteger(e.badgeCount) && e.badgeCount >= 0 && e.badgeCount <= 2_147_483_647 && typeof e.hideContent === 'boolean');
  if (e.kind === 'notify') {
    const fields = ['title', 'subtitle', 'body', 'replyShape', 'retargetsToLiveSurfaceOwner', 'workspaceId', 'surfaceId', 'notificationId'];
    need(exact(e, [...common, ...fields]) && ['text', 'none'].includes(e.replyShape) && typeof e.retargetsToLiveSurfaceOwner === 'boolean');
    for (const name of ['title', 'subtitle', 'body']) need(typeof e[name] === 'string' && e[name].length <= MAX_BODY && e[name].isWellFormed());
    for (const name of ['workspaceId', 'surfaceId', 'notificationId']) need(e[name] === null || text(e[name], 200));
  } else {
    need(e.kind === 'dismiss' && exact(e, [...common, 'notificationIds']) && Array.isArray(e.notificationIds) &&
      e.notificationIds.length >= 1 && e.notificationIds.length <= 4096 && e.notificationIds.every(id => text(id, 200)));
  }
}

/** Authenticates an explicitly paired native producer and projects its live host
 * state into the existing forwarder. No listener, process, timer or provider starts
 * here. General history/feed records cannot establish this source's eligibility.
 */
export class NativePushSourceAdapter {
  #key; #session; #helper; #capture; #changed; #now; #host = null; #sequence = 1; #previous = null; #closed = false;
  constructor({ key, sessionID, helper, capture, changed, now = Date.now }) {
    need(Buffer.isBuffer(key) && key.length === 32 && uuid.test(sessionID) &&
      exact(helper, ['senderKeyID', 'macInstallationID', 'publicKey']) && text(helper.senderKeyID) && text(helper.macInstallationID) &&
      base64(helper.publicKey, 32).length === 32 && typeof capture === 'function' && typeof changed === 'function' && typeof now === 'function');
    this.#key = Buffer.from(key); this.#session = sessionID; this.#helper = structuredClone(helper);
    this.#capture = capture; this.#changed = changed; this.#now = now;
  }
  readHost() { return structuredClone(this.#host); }
  #hostUpdate(snapshot) {
    let host;
    if (snapshot?.state === 'signed-out') {
      need(exact(snapshot, ['state', 'observedAt', 'validUntil'])); host = structuredClone(snapshot);
    } else {
      need(exact(snapshot, ['state', 'epoch', 'observedAt', 'validUntil', 'authority', 'settings']) && snapshot.state === 'ready' &&
        exact(snapshot.authority, ['accountID', 'teamID', 'macDeviceID', 'macInstanceTag', 'macBuildID']) &&
        exact(snapshot.settings, ['forwardingEnabled', 'mode', 'admission', 'hideContent']));
      for (const field of ['accountID', 'macDeviceID', 'macInstanceTag', 'macBuildID']) need(text(snapshot.authority[field]));
      need(text(snapshot.epoch) && (snapshot.authority.teamID === null || text(snapshot.authority.teamID)));
      host = { ...structuredClone(snapshot), authority: { ...snapshot.authority, ...this.#helper } };
    }
    try { readPushHost(() => host, this.#now); } catch { throw new NativePushSourceError(); }
    this.#host = host; this.#changed(); return 'state-updated';
  }
  accept(frame) {
    need(!this.#closed && exact(frame, ['version', 'sessionID', 'sequence', 'body', 'proof']) && frame.version === 1 &&
      frame.sessionID === this.#session && Number.isSafeInteger(frame.sequence) && frame.sequence >= 1 && frame.sequence < Number.MAX_SAFE_INTEGER);
    const body = base64(frame.body, MAX_BODY), proof = base64(frame.proof, 32);
    need(proof.length === 32 && timingSafeEqual(proof, createHmac('sha256', this.#key)
      .update(authenticationFrame(frame.sessionID, frame.sequence, body)).digest()));
    const fingerprint = createHash('sha256').update(body).digest('hex');
    if (frame.sequence === this.#sequence - 1 && this.#previous?.fingerprint === fingerprint)
      return { ...this.#previous.receipt };
    need(frame.sequence === this.#sequence);
    let message;
    try { message = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(body)); } catch { throw new NativePushSourceError(); }
    let kind;
    if (message?.type === 'host') {
      need(exact(message, ['type', 'snapshot'])); kind = this.#hostUpdate(message.snapshot);
    } else {
      need(exact(message, ['type', 'request']) && message.type === 'capture'); validateRequest(message.request);
      let host;
      try { host = readPushHost(() => this.#host, this.#now); }
      catch { return { sequence: frame.sequence, kind: 'source-unavailable', acknowledged: false }; }
      if (host.state === 'signed-out' || message.request.sourceEpoch !== host.epoch) kind = 'retired';
      else {
        let result;
        try { result = this.#capture(structuredClone(message.request)); }
        catch (error) {
          if (['invalid-input', 'identity-conflict'].includes(error?.kind)) throw new NativePushSourceError();
          return { sequence: frame.sequence, kind: error?.kind === 'full' ? 'backpressure' : 'storage-unavailable', acknowledged: false };
        }
        if (!['captured', 'duplicate', 'expired'].includes(result?.kind))
          return { sequence: frame.sequence, kind: result?.kind === 'capacity' ? 'backpressure' : 'storage-unavailable', acknowledged: false };
        kind = result.kind;
      }
    }
    const receipt = { sequence: frame.sequence, kind, acknowledged: true };
    this.#sequence++; this.#previous = { fingerprint, receipt }; return { ...receipt };
  }
  close() {
    if (this.#closed) return;
    this.#closed = true; this.#host = null; this.#previous = null; this.#key.fill(0); this.#changed();
  }
}
