import { fcmMessage } from './fcm.mjs';

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
export async function preparePushJob({ event, registration, authority, expiresAt }, { seal, now = Date.now }) {
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
  const plaintext = Buffer.from(JSON.stringify(payload));
  let envelope;
  try { envelope = await seal({ recipient: r.recipient, publicKey: r.publicKey, plaintext, senderPublicKey: a.publicKey }); }
  finally { plaintext.fill(0); }
  const delivery = { token: r.token, recipient: r.recipient, envelope, expiresAt };
  // Validate exact identity, ciphertext and provider size before durable admission.
  if (!fcmMessage(delivery, now())) return { kind: 'expired' };
  return { kind: 'prepared', job: { eventID: e.correlationID, registration: r.registration, delivery } };
}
