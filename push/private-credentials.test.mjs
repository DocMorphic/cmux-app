import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync } from 'node:crypto';
import { mkdtempSync, writeFileSync, chmodSync, symlinkSync, linkSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { readPrivatePushFile, privateServiceAccountTokens } from './private-credentials.mjs';

const key = generateKeyPairSync('rsa', { modulusLength: 2048 }).privateKey;
const credentials = { type: 'service_account', project_id: 'cmux-fixture',
  client_email: 'push@cmux-fixture.iam.gserviceaccount.com', private_key_id: 'public-fixture',
  private_key: key.export({ type: 'pkcs8', format: 'pem' }), token_uri: 'https://oauth2.googleapis.com/token' };
function fixture(t, body = JSON.stringify(credentials)) {
  const directory = mkdtempSync(join(tmpdir(), 'cmux-private-credentials-'));
  chmodSync(directory, 0o700);
  const path = join(directory, 'provider.json'); writeFileSync(path, body, { mode: 0o600 });
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  return { directory, path, projectID: credentials.project_id, accountEmail: credentials.client_email };
}
const coarse = error => error.message === 'Push credentials unavailable';
test('explicit private activation binds the project/account and does not contact OAuth until get', async t => {
  const input = fixture(t); let calls = 0;
  const provider = privateServiceAccountTokens({ ...input, fetch: async (url, request) => {
    calls++; assert.equal(url, 'https://oauth2.googleapis.com/token');
    const assertion = new URLSearchParams(request.body).get('assertion');
    const claim = JSON.parse(Buffer.from(assertion.split('.')[1], 'base64url'));
    assert.equal(claim.iss, credentials.client_email);
    return new Response(JSON.stringify({ access_token: 'public-fixture', token_type: 'Bearer', expires_in: 3600 }));
  } });
  assert.equal(calls, 0); assert.equal(await provider.get(), 'public-fixture'); assert.equal(calls, 1);
  assert.throws(() => privateServiceAccountTokens({ ...input, projectID: 'other-project' }), coarse);
  assert.throws(() => privateServiceAccountTokens({ ...input, accountEmail: 'other@cmux-fixture.iam.gserviceaccount.com' }), coarse);
});
test('symlinks, hard links, nonfiles and oversized or empty inputs are rejected before parsing', t => {
  const f = fixture(t);
  const alias = join(f.directory, 'alias'); symlinkSync(f.path, alias);
  assert.throws(() => readPrivatePushFile(alias), coarse);
  assert.throws(() => readPrivatePushFile(f.directory), coarse);
  assert.throws(() => readPrivatePushFile(f.path, 8), coarse);
  linkSync(f.path, join(f.directory, 'hard-link')); assert.throws(() => readPrivatePushFile(f.path), coarse);
  const empty = fixture(t, ''); assert.throws(() => readPrivatePushFile(empty.path), coarse);
  assert.throws(() => readPrivatePushFile('relative.json'), coarse);
});
test('nonprivate file or containing directory permissions are rejected on Unix', { skip: process.platform === 'win32' }, t => {
  const f = fixture(t); chmodSync(f.path, 0o644); assert.throws(() => readPrivatePushFile(f.path), coarse);
  chmodSync(f.path, 0o600); chmodSync(f.directory, 0o755); assert.throws(() => readPrivatePushFile(f.path), coarse);
});
test('malformed JSON, invalid RSA keys and token endpoint substitution expose only coarse errors', t => {
  for (const body of ['sensitive-invalid-json', JSON.stringify({ ...credentials, private_key: 'sensitive-invalid-key' }),
    JSON.stringify({ ...credentials, token_uri: 'https://example.invalid/steal' })]) {
    const f = fixture(t, body); assert.throws(() => privateServiceAccountTokens(f), coarse);
  }
});
