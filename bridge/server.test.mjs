import assert from 'node:assert/strict';
import { afterEach, test } from 'node:test';
import { createBridge, isAuthorized, validateSurfaceId } from './server.mjs';

const servers = [];
afterEach(async () => { for (const server of servers.splice(0)) await new Promise((resolve) => server.close(resolve)); });

async function start(runCmux) {
  const server = createBridge({ token: 'test-token', runCmux });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  servers.push(server);
  return `http://127.0.0.1:${server.address().port}`;
}

test('rejects unauthenticated requests and exposes only health', async () => {
  const base = await start(async () => '{}');
  assert.equal((await fetch(`${base}/v1/health`)).status, 200);
  assert.equal((await fetch(`${base}/v1/tree`)).status, 401);
  assert.equal(isAuthorized('Bearer test-token', 'test-token'), true);
  assert.equal(isAuthorized('Bearer wrong', 'test-token'), false);
});

test('returns tree and screen from scoped CLI calls', async () => {
  const calls = [];
  const id = '123e4567-e89b-12d3-a456-426614174000';
  const base = await start(async (args) => {
    calls.push(args);
    return args[0] === '--json' ? '{"windows":[]}' : 'terminal text';
  });
  const headers = { Authorization: 'Bearer test-token' };
  assert.deepEqual(await (await fetch(`${base}/v1/tree`, { headers })).json(), { windows: [] });
  assert.deepEqual(calls[0], ['--json', '--id-format', 'uuids', 'tree', '--all']);
  assert.equal((await (await fetch(`${base}/v1/screen?surface=${id}&lines=80`, { headers })).json()).text, 'terminal text');
  assert.deepEqual(calls[1], ['read-screen', '--surface', id, '--scrollback', '--lines', '80']);
});

test('validates input and never passes shell text through a shell', async () => {
  const calls = [];
  const id = '123e4567-e89b-12d3-a456-426614174000';
  const base = await start(async (args) => { calls.push(args); return ''; });
  const headers = { Authorization: 'Bearer test-token', 'Content-Type': 'application/json' };
  assert.equal((await fetch(`${base}/v1/input`, { method: 'POST', headers, body: JSON.stringify({ surface: id, text: 'echo hello; whoami\\n' }) })).status, 200);
  assert.deepEqual(calls[0], ['rpc', 'surface.send_text', JSON.stringify({ surface_id: id, text: 'echo hello; whoami\\n' })]);
  assert.equal((await fetch(`${base}/v1/key`, { method: 'POST', headers, body: JSON.stringify({ surface: id, key: 'ctrl+c' }) })).status, 200);
  assert.deepEqual(calls[1], ['rpc', 'surface.send_key', JSON.stringify({ surface_id: id, key: 'ctrl+c' })]);
  assert.equal((await fetch(`${base}/v1/key`, { method: 'POST', headers, body: JSON.stringify({ surface: id, key: 'rm -rf' }) })).status, 400);
  assert.throws(() => validateSurfaceId('surface:1'));
});
