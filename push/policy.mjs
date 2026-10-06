const exact = (v, keys) => v && typeof v === 'object' && !Array.isArray(v) &&
  Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k));
const text = v => typeof v === 'string' && v.length > 0 && v.length <= 1024 && !/[\u0000-\u001f]/.test(v);
const key = v => typeof v === 'string' && Buffer.from(v, 'base64').length === 32 && Buffer.from(v, 'base64').toString('base64') === v;

/** Encrypted-at-rest metadata; never sent to FCM. Missing legacy metadata is not fresh host authorization. */
export function validPushAdmission(v) {
  return !!(exact(v, ['version', 'hostEpoch', 'kind', 'hideContent', 'senderInstallationID', 'senderPublicKey']) &&
    v.version === 1 && text(v.hostEpoch) && ['notify', 'dismiss'].includes(v.kind) && typeof v.hideContent === 'boolean' &&
    text(v.senderInstallationID) && key(v.senderPublicKey));
}

export class PushHostUnavailable extends Error {
  constructor() { super('Push host state unavailable'); }
}

/** The provider must independently authenticate this snapshot. No event/enrollment supplies host authority. */
export function readPushHost(read, now = Date.now) {
  let host;
  try { host = structuredClone(read()); } catch { throw new PushHostUnavailable(); }
  const time = now();
  if (!Number.isSafeInteger(time) || time < 0 || !host || !Number.isSafeInteger(host.observedAt) || host.observedAt < 0 || !Number.isSafeInteger(host.validUntil) ||
      host.observedAt > time || host.validUntil <= time || host.validUntil - host.observedAt > 30_000)
    throw new PushHostUnavailable();
  if (host.state === 'signed-out') return host;
  const a = host.authority, s = host.settings;
  if (host.state !== 'ready' || !text(host.epoch) || !a ||
      !['accountID', 'macDeviceID', 'macInstanceTag', 'macBuildID', 'senderKeyID', 'macInstallationID'].every(k => text(a[k])) ||
      !(a.teamID == null || text(a.teamID)) || !key(a.publicKey) || !s ||
      typeof s.forwardingEnabled !== 'boolean' || typeof s.hideContent !== 'boolean' ||
      !['always', 'onlyWhenAway'].includes(s.mode) || !['allowed', 'forwarding_disabled', 'suppressed_mac_active', 'unknown'].includes(s.admission))
    throw new PushHostUnavailable();
  return host;
}

export function hostMatchesRecipient(host, recipient) {
  if (host.state !== 'ready') return false;
  const a = host.authority, t = recipient?.tuple;
  return !!t && ['accountID', 'macDeviceID', 'macInstanceTag', 'macBuildID'].every(k => t[k] === a[k]) &&
    (t.teamID ?? null) === (a.teamID ?? null) && recipient.senderKeyID === a.senderKeyID;
}

/** Dismissals do not wait for away presence. Like iOS, presence gates a new alert, not its already-admitted retry. */
export function hostAdmitsEvent(host, kind) {
  if (host.state !== 'ready' || !host.settings.forwardingEnabled) return false;
  if (kind === 'dismiss') return true;
  if (kind !== 'notify') return false;
  if (host.settings.mode === 'always') return true;
  if (host.settings.admission === 'unknown') throw new PushHostUnavailable();
  return host.settings.admission === 'allowed';
}

/** Called again before provider IO, including after an awaited OAuth exchange. */
export function hostPermitsDelivery(host, binding, admission) {
  return validPushAdmission(admission) && hostMatchesRecipient(host, binding?.recipient) && host.settings.forwardingEnabled &&
    admission.hostEpoch === host.epoch && admission.senderInstallationID === host.authority.macInstallationID &&
    admission.senderPublicKey === host.authority.publicKey &&
    !(admission.kind === 'notify' && host.settings.hideContent && !admission.hideContent);
}
