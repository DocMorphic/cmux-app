import { fcmMessage } from './fcm.mjs';
import { createHash } from 'node:crypto';

const segmenter = new Intl.Segmenter('en', { granularity: 'grapheme' });
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
function requireInput(value) { if (!value) throw new TypeError('Invalid push event'); }
function identifier(value, optional = false) {
  if (optional && value == null) return undefined;
  requireInput(typeof value === 'string' && value.isWellFormed());
  const result = value.trim();
  if (optional && !result) return undefined;
  requireInput(result.length > 0 && result.length <= 200);
  return result;
}
function text(value, maximum) {
  requireInput(typeof value === 'string' && value.length <= 1_048_576 && value.isWellFormed());
  const trimmed = value.trim();
  if (trimmed.length <= maximum) return trimmed;
  let result = '';
  for (const { segment } of segmenter.segment(trimmed)) {
    if (result.length + segment.length > maximum) break;
    result += segment;
  }
  return result;
}

/** Construct one already-admitted event for one independently enrolled phone.
 * authority is host-owned account/team/Mac/sender state, never taken from the event.
 * The dispatcher rechecks live authorization and registration after encryption.
 */
function prepare({ event, registration, authority, expiresAt }, { seal, now }) {
  // No caller mutation may switch identity/content while the key store/encryptor awaits.
  const [e, r, a] = JSON.parse(JSON.stringify([event, registration, authority]));
  requireInput(e && r?.recipient?.tuple && a && typeof seal === 'function');
  const tuple = r.recipient.tuple;
  requireInput(['accountID', 'macDeviceID', 'macInstanceTag', 'macBuildID'].every(k =>
    typeof a[k] === 'string' && a[k].length > 0 && a[k] === tuple[k]));
  requireInput((a.teamID ?? null) === (tuple.teamID ?? null) && a.senderKeyID === r.recipient.senderKeyID);
  requireInput(typeof a.macInstallationID === 'string' && a.macInstallationID.length > 0 && a.macInstallationID.length <= 1024);
  requireInput(typeof a.publicKey === 'string' && Buffer.from(a.publicKey, 'base64').length === 32 &&
    Buffer.from(a.publicKey, 'base64').toString('base64') === a.publicKey);
  requireInput(typeof e.correlationID === 'string' && uuid.test(e.correlationID));
  const started = now();
  requireInput(Number.isSafeInteger(started) && started >= 0 && Number.isSafeInteger(expiresAt) &&
    expiresAt % 1000 === 0 && expiresAt - started >= 1000 && expiresAt - started <= 900_000);
  requireInput(Number.isInteger(e.badgeCount) && e.badgeCount >= 0 && e.badgeCount <= 2_147_483_647 && typeof e.hideContent === 'boolean');
  const payload = { kind: e.kind, correlationId: e.correlationID, expirationEpochSeconds: expiresAt / 1000,
    badgeCount: e.badgeCount, hideContent: e.hideContent, macDeviceId: a.macDeviceID,
    macInstanceTag: a.macInstanceTag, macBuildID: a.macBuildID,
    macInstallationID: a.macInstallationID, macPushPublicKey: a.publicKey };
  if (e.kind === 'notify') {
    requireInput(typeof e.retargetsToLiveSurfaceOwner === 'boolean' && ['text', 'none'].includes(e.replyShape));
    Object.assign(payload, { title: e.hideContent ? 'cmux' : text(e.title, 120),
      subtitle: e.hideContent ? '' : text(e.subtitle, 120), body: e.hideContent ? 'New terminal activity' : text(e.body, 500),
      replyShape: e.replyShape, category: e.replyShape === 'text' ? 'cmux.terminal.reply' : 'cmux.terminal',
      retargetsToLiveSurfaceOwner: e.retargetsToLiveSurfaceOwner,
      workspaceId: identifier(e.workspaceId, true), surfaceId: identifier(e.surfaceId, true),
      notificationId: identifier(e.notificationId, true) });
  } else {
    requireInput(e.kind === 'dismiss' && Array.isArray(e.notificationIds) && e.notificationIds.length >= 1 && e.notificationIds.length <= 4096);
    Object.assign(payload, { title: '', body: '', notificationIds: [...new Set(e.notificationIds.map(value => identifier(value)))] });
  }
  return { payload, registration: r, authority: a, expiresAt, started };
}

async function sealPayload({ payload, registration: r, authority: a, expiresAt }, { seal, now }) {
  const plaintext = Buffer.from(JSON.stringify(payload));
  let envelope;
  try { envelope = await seal({ recipient: r.recipient, publicKey: r.publicKey, plaintext, senderPublicKey: a.publicKey }); }
  finally { plaintext.fill(0); }
  const delivery = { token: r.token, recipient: r.recipient, envelope, expiresAt };
  // Validate exact identity, ciphertext and provider size before durable admission.
  if (!fcmMessage(delivery, now())) return { kind: 'expired' };
  return { kind: 'prepared', job: { eventID: payload.correlationId, registration: r.registration, delivery } };
}

export async function preparePushJob(input, { seal, now = Date.now }) {
  return sealPayload(prepare(input, { seal, now }), { seal, now });
}

function partID(original, index) {
  const bytes = createHash('sha256').update(JSON.stringify(['cmux-android-dismiss-v1', original, index])).digest().subarray(0, 16);
  bytes[6] = (bytes[6] & 15) | 0x80; bytes[8] = (bytes[8] & 63) | 0x80; // UUID v8, RFC variant.
  const hex = bytes.toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** Exact serialized FCM data budget; HPKE adds one 16-byte tag and a 32-byte encapsulated key.
 * This probe never leaves this module or enters the queue; only real sealed output is returned.
 */
function budget(prepared) {
  const r = prepared.registration;
  const envelope = { ...r.recipient, version: 2, tuple: Object.fromEntries(
    Object.entries(r.recipient.tuple).filter(([, value]) => value != null)),
    encapsulatedKey: Buffer.alloc(32).toString('base64'), ciphertext: Buffer.alloc(16).toString('base64') };
  // Validate enrollment/wire identity independently, so a malformed input is never treated as a size failure.
  fcmMessage({ token: r.token, recipient: r.recipient, envelope, expiresAt: prepared.expiresAt }, prepared.started);
  const overhead = Buffer.byteLength(JSON.stringify({ cmux: JSON.stringify({ encryptedPayloads: [envelope] }) })) - envelope.ciphertext.length;
  return payload => {
    const ciphertextBytes = Buffer.byteLength(JSON.stringify(payload)) + 16;
    return ciphertextBytes <= 16_384 && overhead + 4 * Math.ceil(ciphertextBytes / 3) <= 4096;
  };
}

/** Prepare one logical event. Oversized dismissals split without losing IDs; notify content is not silently reduced.
 * Retain returned sealed jobs for admission retries. Fresh encryption is intentionally not a replacement for an existing job.
 */
export async function preparePushBatch(input, { seal, now = Date.now, maxParts = 128 }) {
  requireInput(Number.isInteger(maxParts) && maxParts > 0 && maxParts <= 512);
  const prepared = prepare(input, { seal, now }), fits = budget(prepared);
  const original = prepared.payload, parts = [];
  if (fits(original)) parts.push(original);
  else {
    requireInput(original.kind === 'dismiss');
    const ids = original.notificationIds;
    let offset = 0;
    while (offset < ids.length) {
      if (parts.length >= maxParts) throw new Error('Push batch exceeds capacity');
      const part = { ...original, correlationId: partID(original.correlationId, parts.length) };
      let low = 1, high = ids.length - offset, count = 0;
      while (low <= high) {
        const mid = Math.floor((low + high) / 2);
        if (fits({ ...part, notificationIds: ids.slice(offset, offset + mid) })) { count = mid; low = mid + 1; }
        else high = mid - 1;
      }
      if (count === 0) throw new Error('Push dismissal cannot fit provider limit');
      parts.push({ ...part, notificationIds: ids.slice(offset, offset + count) }); offset += count;
    }
  }
  // Plan and validate every part before starting potentially slow credential-store/crypto operations.
  const jobs = [];
  for (const payload of parts) {
    if (prepared.expiresAt - now() < 1000) return { kind: 'expired' };
    const result = await sealPayload({ ...prepared, payload }, { seal, now });
    if (result.kind === 'expired') return result;
    jobs.push(result.job);
  }
  return { kind: 'prepared', jobs };
}
