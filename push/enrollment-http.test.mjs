import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import { request } from 'node:https';
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createPushEnrollmentServer } from './enrollment-http.mjs';
import { PushEnrollmentError } from './enrollment.mjs';
let temporary, key, cert;
before(() => {
  temporary = mkdtempSync(join(tmpdir(), 'cmux-enroll-tls-'));
  writeFileSync(join(temporary, 'openssl.cnf'), '[req]\ndistinguished_name=dn\nx509_extensions=ext\nprompt=no\n[dn]\nCN=localhost\n[ext]\nsubjectAltName=DNS:localhost,IP:127.0.0.1\n');
  const result = spawnSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-config', join(temporary, 'openssl.cnf'),
    '-keyout', join(temporary, 'key.pem'), '-out', join(temporary, 'cert.pem')], { timeout: 10_000, stdio: 'ignore' });
  assert.equal(result.status, 0, 'Could not create ephemeral test TLS certificate');
  key = readFileSync(join(temporary, 'key.pem')); cert = readFileSync(join(temporary, 'cert.pem'));
});
after(() => rmSync(temporary, { recursive: true, force: true }));
async function fixture(t, overrides = {}) {
  let endpoint; const calls = [];
  const server = createPushEnrollmentServer({ key, cert, endpoint: () => endpoint,
    enrollment: { begin: async body => { calls.push(['begin', body]); return { challenge: 'fixture' }; },
      finish: body => { calls.push(['finish', body]); return { receipt: 'fixture' }; } }, ...overrides });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  endpoint = `https://127.0.0.1:${server.address().port}/v1/push/enroll`;
  t.after(() => { server.closeAllConnections(); return new Promise(resolve => server.close(resolve)); });
  return { endpoint, calls };
}
function send(endpoint, { method = 'POST', headers = {}, body = JSON.stringify({ step: 'begin', request: { fixture: 'λ中' } }), chunked = false } = {}) {
  return new Promise((resolve, reject) => {
    const req = request(endpoint, { method, ca: cert, servername: 'localhost', agent: false, headers: { 'Content-Type': 'application/json', ...headers }, timeout: 3000 }, response => {
      const chunks = []; response.on('data', chunk => chunks.push(chunk));
      response.on('end', () => resolve({ status: response.statusCode, headers: response.headers, body: Buffer.concat(chunks).toString() }));
    });
    req.on('error', reject); req.on('timeout', () => req.destroy(Error('fixture timeout')));
    if (chunked) { req.write(body); req.end(); } else req.end(body);
  });
}
test('TLS begin/finish use only the fixed endpoint and return no-store JSON', async t => {
  const f = await fixture(t); const a = await send(f.endpoint);
  assert.equal(a.status, 200); assert.equal(a.headers['cache-control'], 'no-store'); assert.equal(a.headers['connection'], 'close');
  assert.deepEqual(f.calls, [['begin', { fixture: 'λ中' }]]);
  assert.equal((await send(f.endpoint, { body: JSON.stringify({ step: 'finish', request: { proof: 'fixture' } }) })).status, 200);
  assert.equal(f.calls[1][0], 'finish');
});
test('wrong host/path/method/origin/content encoding cannot reach enrollment', async t => {
  const f = await fixture(t);
  for (const [url, options, expected] of [
    [f.endpoint, { headers: { Host: 'other.invalid' } }, 421], [f.endpoint + '?query=1', {}, 404],
    [f.endpoint, { method: 'GET' }, 405], [f.endpoint, { headers: { Origin: 'https://other.invalid' } }, 403],
    [f.endpoint, { headers: { 'Content-Type': 'text/plain' } }, 415], [f.endpoint, { headers: { 'Content-Encoding': 'gzip' } }, 415],
    [f.endpoint, { body: JSON.stringify({ step: 'issue', request: {} }) }, 400],
    [f.endpoint, { body: JSON.stringify({ step: 'cancel', request: {} }) }, 400]
  ]) assert.equal((await send(url, options)).status, expected);
  assert.equal(f.calls.length, 0);
});
test('declared and streamed oversized bodies plus malformed UTF-8/JSON are rejected before dispatch', async t => {
  const f = await fixture(t);
  assert.equal((await send(f.endpoint, { headers: { 'Content-Length': '32769' }, body: 'x'.repeat(32769) })).status, 413);
  assert.equal((await send(f.endpoint, { chunked: true, body: 'x'.repeat(32769) })).status, 413);
  assert.equal((await send(f.endpoint, { body: Buffer.from([0xff]) })).status, 400);
  assert.equal((await send(f.endpoint, { body: '{bad-json' })).status, 400);
  assert.equal(f.calls.length, 0);
});
test('in-flight concurrency stays bounded and a late encryptor cannot answer after deadline', async t => {
  let release, started; const pending = new Promise(resolve => { release = resolve; }), entered = new Promise(resolve => { started = resolve; });
  const f = await fixture(t, { maxConcurrent: 1, requestTimeoutMs: 100,
    enrollment: { begin: async () => { started(); await pending; return {}; } } });
  const first = send(f.endpoint); await entered;
  const limited = await send(f.endpoint); assert.equal(limited.status, 429); assert.equal(limited.headers['retry-after'], '5');
  assert.equal((await first).status, 408); release();
});
test('protocol failures are coarse and distinguish replacement conflicts from temporary storage failure', async t => {
  let failure = new PushEnrollmentError('superseded');
  const f = await fixture(t, { enrollment: { begin: async () => { throw failure; } } });
  assert.equal((await send(f.endpoint)).status, 409);
  failure = new PushEnrollmentError('registration-unavailable'); assert.equal((await send(f.endpoint)).status, 503);
  failure = Error('fixture secret must never be reflected');
  const unknown = await send(f.endpoint); assert.equal(unknown.status, 503); assert.equal(unknown.body.includes('fixture secret'), false);
  failure = new PushEnrollmentError(); assert.equal((await send(f.endpoint)).status, 403);
});
test('a client that never completes its body hits the deadline and releases request capacity', async t => {
  const f = await fixture(t, { maxConcurrent: 1, requestTimeoutMs: 100 });
  const result = await new Promise((resolve, reject) => {
    const req = request(f.endpoint, { ca: cert, servername: 'localhost', agent: false, method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': '1000' } }, response => {
      response.resume(); response.on('end', () => { req.destroy(); resolve(response.statusCode); });
    });
    req.on('error', reject); req.write('{');
  });
  assert.equal(result, 408); assert.equal(f.calls.length, 0);
  assert.equal((await send(f.endpoint)).status, 200);
});
