import { createPrivateKey, sign } from 'node:crypto';

const OAUTH = 'https://oauth2.googleapis.com/token';
const SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';
const FCM_ERROR = 'type.googleapis.com/google.firebase.fcm.v1.FcmError';
const MAX_TTL = 15 * 60_000;
const TUPLE_KEYS = ['accountID', 'teamID', 'iosBuildID', 'iosInstallationID', 'macDeviceID', 'macInstanceTag', 'macBuildID'];
const REQUIRED = TUPLE_KEYS.filter(k => k !== 'teamID');
const ENVELOPE_KEYS = ['version', 'installationID', 'keyID', 'senderKeyID', 'encapsulatedKey', 'ciphertext', 'tuple'];
const field = value => typeof value === 'string' && value.length > 0 && value.length <= 1024;
const exactKeys = (value, keys) => value && typeof value === 'object' && !Array.isArray(value) &&
  Object.keys(value).length === keys.length && Object.keys(value).every(k => keys.includes(k));
function requireInput(condition) { if (!condition) throw new TypeError('Invalid push delivery request'); }
function base64(value, min, max) {
  if (typeof value !== 'string' || value.length > Math.ceil(max / 3) * 4) return false;
  const bytes = Buffer.from(value, 'base64');
  return bytes.length >= min && bytes.length <= max && bytes.toString('base64') === value;
}
function project(value) {
  if (!/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(value ?? '')) throw new TypeError('Invalid Firebase project ID');
  return value;
}

/** The caller supplies a separately authenticated registration; ciphertext never establishes authority. */
export function fcmMessage({ token, envelope, recipient, expiresAt }, now = Date.now()) {
  requireInput(typeof token === 'string' && token.length >= 1 && token.length <= 4096 && !/\s/.test(token));
  requireInput(Number.isSafeInteger(now) && now >= 0 && Number.isSafeInteger(expiresAt) && expiresAt - now <= MAX_TTL);
  if (expiresAt - now < 1000) return null; // Do not round past the authenticated event expiry.
  requireInput(exactKeys(envelope, ENVELOPE_KEYS) && envelope.version === 2 && recipient && recipient.tuple);
  requireInput(['installationID', 'keyID', 'senderKeyID'].every(key => field(recipient[key]) && recipient[key] === envelope[key]));
  requireInput(envelope.tuple && Object.keys(envelope.tuple).every(k => TUPLE_KEYS.includes(k)));
  requireInput(REQUIRED.every(key => field(recipient.tuple[key])));
  requireInput(recipient.tuple.teamID == null || field(recipient.tuple.teamID));
  requireInput(TUPLE_KEYS.every(key => (envelope.tuple[key] ?? null) === (recipient.tuple[key] ?? null)));
  requireInput(envelope.installationID === recipient.tuple.iosInstallationID);
  requireInput(base64(envelope.encapsulatedKey, 32, 32) && base64(envelope.ciphertext, 16, 16 * 1024));
  const data = { cmux: JSON.stringify({ encryptedPayloads: [envelope] }) };
  // Conservatively include JSON quoting/key overhead, not just plaintext length.
  requireInput(Buffer.byteLength(JSON.stringify(data), 'utf8') <= 4096);
  return { token, data, android: { priority: 'HIGH', ttl: `${Math.floor((expiresAt - now) / 1000)}s` } };
}

export class PushTransportError extends Error {
  constructor(kind, retryAfterMs = 0) { super(`Push transport ${kind}`); this.kind = kind; this.retryAfterMs = retryAfterMs; }
}
function retryDelay(response, now, minimum = 1000) {
  const header = response.headers.get('retry-after');
  let milliseconds = /^[0-9]+$/.test(header ?? '') ? Number(header) * 1000 : Date.parse(header ?? '') - now;
  if (!Number.isFinite(milliseconds)) milliseconds = minimum;
  return Math.max(minimum, milliseconds);
}
async function json(response) {
  try { const raw = await response.text(); return raw.length <= 32_768 ? JSON.parse(raw) : null; }
  catch { return null; }
}

/** Optional service-account adapter for a separately provisioned trusted sender; never ship this in the APK. */
export function serviceAccountTokens(credentials, { fetch: request = globalThis.fetch, now = Date.now } = {}) {
  if (credentials?.type !== 'service_account' ||
      !/^[^\s@]+@[^\s@]+\.iam\.gserviceaccount\.com$/.test(credentials.client_email ?? '') ||
      (credentials.token_uri != null && credentials.token_uri !== OAUTH)) throw new TypeError('Invalid push service account');
  project(credentials.project_id);
  let key;
  try { key = createPrivateKey(credentials.private_key); }
  catch { throw new TypeError('Invalid push service account key'); }
  if (key.asymmetricKeyType !== 'rsa' || key.asymmetricKeyDetails.modulusLength < 2048) throw new TypeError('Invalid push service account key');
  const email = credentials.client_email;
  const kid = credentials.private_key_id;
  if (kid != null && !/^[a-zA-Z0-9_-]{1,256}$/.test(kid)) throw new TypeError('Invalid push service account key ID');
  let cached = null; let pending = null;
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  async function refresh() {
    const issued = Math.floor(now() / 1000);
    const header = { alg: 'RS256', typ: 'JWT', ...(kid ? { kid } : {}) };
    const body = { iss: email, scope: SCOPE, aud: OAUTH, iat: issued, exp: issued + 3600 };
    const unsigned = `${encode(header)}.${encode(body)}`;
    const assertion = `${unsigned}.${sign('RSA-SHA256', Buffer.from(unsigned), key).toString('base64url')}`;
    let response;
    try { response = await request(OAUTH, { method: 'POST', redirect: 'error',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion }).toString(),
      signal: AbortSignal.timeout(10_000) }); }
    catch { throw new PushTransportError('unavailable', 1000); }
    if (response.status === 429 || response.status >= 500) throw new PushTransportError('unavailable', retryDelay(response, now()));
    if (!response.ok) throw new PushTransportError('credentials');
    const value = await json(response);
    if (typeof value?.access_token !== 'string' || !value.access_token || value.access_token.length > 8192 || /\s/.test(value.access_token) ||
        value.token_type?.toLowerCase() !== 'bearer' || !Number.isInteger(value.expires_in) || value.expires_in <= 60 || value.expires_in > 3600)
      throw new PushTransportError('credentials');
    cached = { token: value.access_token, expires: now() + value.expires_in * 1000 };
    return cached.token;
  }
  return {
    async get() {
      if (cached && cached.expires - now() > 60_000) return cached.token;
      if (!pending) pending = refresh().finally(() => { pending = null; });
      return pending;
    },
    invalidate(token) { if (cached?.token === token) cached = null; }
  };
}

/** No automatic ambiguous-send retries. Keep the same encrypted event/correlation ID in the caller's queue. */
export class FcmSender {
  constructor({ projectId, tokens, fetch: request = globalThis.fetch, now = Date.now }) {
    this.parent = `projects/${project(projectId)}`;
    this.endpoint = `https://fcm.googleapis.com/v1/${this.parent}/messages:send`;
    if (typeof tokens?.get !== 'function' || typeof tokens.invalidate !== 'function') throw new TypeError('Missing push token provider');
    this.tokens = tokens; this.request = request; this.now = now;
  }
  async send(input, { permits = () => false } = {}) {
    // Clone validated data before yielding; the caller cannot mutate identity mid-request.
    const snapshot = JSON.parse(JSON.stringify(input));
    if (!fcmMessage(snapshot, this.now())) return { kind: 'expired' };
    for (let attempt = 0; attempt < 2; attempt++) {
      if (permits() !== true) return { kind: 'retired' };
      let token;
      try { token = await this.tokens.get(); }
      catch (error) { return { kind: error instanceof PushTransportError ? error.kind : 'unavailable', retryAfterMs: error instanceof PushTransportError ? error.retryAfterMs : 1000, ambiguous: false }; }
      if (permits() !== true) return { kind: 'retired' };
      if (typeof token !== 'string' || !token || token.length > 8192 || /\s/.test(token)) return { kind: 'credentials' };
      const message = fcmMessage(snapshot, this.now());
      if (!message) return { kind: 'expired' };
      let response;
      try { response = await this.request(this.endpoint, { method: 'POST', redirect: 'error',
        headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
        body: JSON.stringify({ message }), signal: AbortSignal.timeout(10_000) }); }
      catch { return { kind: 'unavailable', retryAfterMs: 1000, ambiguous: true }; }
      if (response.status === 401 && attempt === 0) { this.tokens.invalidate(token); continue; }
      const value = await json(response);
      if (response.ok) return typeof value?.name === 'string' && value.name.startsWith(this.parent + '/messages/') ?
        { kind: 'accepted' } : { kind: 'unavailable', retryAfterMs: 1000, ambiguous: true };
      const details = value?.error?.details;
      const code = Array.isArray(details) ? details.find(d => d?.['@type'] === FCM_ERROR)?.errorCode : null;
      if (response.status === 404 && code === 'UNREGISTERED') return { kind: 'unregistered' };
      if (response.status === 429 || response.status >= 500) return { kind: 'unavailable',
        retryAfterMs: retryDelay(response, this.now(), response.status === 429 ? 60_000 : 1000), ambiguous: response.status >= 500 };
      if (response.status === 401 || response.status === 403) return { kind: 'credentials' };
      return { kind: 'rejected' }; // INVALID_ARGUMENT alone never authorizes token removal.
    }
    return { kind: 'credentials' };
  }
}
