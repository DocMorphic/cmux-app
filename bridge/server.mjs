import { createServer } from 'node:http';
import { execFile as execFileCallback } from 'node:child_process';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { existsSync, lstatSync, mkdirSync, readFileSync, writeFileSync, chmodSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

const execFile = promisify(execFileCallback);
const defaultCli = '/Applications/cmux.app/Contents/Resources/bin/cmux';
const surfaceIdPattern = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
const allowedKeys = new Set(['enter', 'tab', 'escape', 'backspace', 'delete', 'up', 'down', 'left', 'right', 'home', 'end', 'pageup', 'pagedown']);

export function loadOrCreateToken(path = join(homedir(), '.config', 'cmux-app', 'bridge-token')) {
  mkdirSync(join(path, '..'), { recursive: true, mode: 0o700 });
  if (!existsSync(path)) {
    try {
      writeFileSync(path, randomBytes(32).toString('hex'), { flag: 'wx', mode: 0o600 });
    } catch (error) {
      if (error.code !== 'EEXIST') throw error;
    }
  }
  const stat = lstatSync(path);
  if (!stat.isFile() || stat.isSymbolicLink()) throw new Error('Bridge token must be a regular file');
  chmodSync(path, 0o600);
  const token = readFileSync(path, 'utf8').trim();
  if (!/^[0-9a-f]{64}$/.test(token)) throw new Error('Bridge token is malformed');
  return token;
}

export function isAuthorized(header, token) {
  if (typeof header !== 'string' || !header.startsWith('Bearer ')) return false;
  const candidate = Buffer.from(header.slice(7), 'utf8');
  const expected = Buffer.from(token, 'utf8');
  return candidate.length === expected.length && timingSafeEqual(candidate, expected);
}

export function validateSurfaceId(value) {
  if (typeof value !== 'string' || !surfaceIdPattern.test(value)) throw new Error('Invalid surface ID');
  return value;
}

function validateKey(value) {
  if (typeof value !== 'string') throw new Error('Invalid key');
  const key = value.toLowerCase();
  if (allowedKeys.has(key) || /^ctrl\+[a-z]$/.test(key) || /^alt\+[a-z]$/.test(key)) return key;
  throw new Error('Unsupported key');
}

async function readJsonBody(request) {
  let body = '';
  for await (const chunk of request) {
    body += chunk;
    if (body.length > 16_384) throw new Error('Request body is too large');
  }
  try { return JSON.parse(body); } catch { throw new Error('Invalid JSON body'); }
}

function send(response, status, payload, contentType = 'application/json') {
  response.writeHead(status, { 'Content-Type': `${contentType}; charset=utf-8`, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
  response.end(contentType === 'application/json' ? JSON.stringify(payload) : payload);
}

export function createBridge({ token, runCmux }) {
  return createServer(async (request, response) => {
    try {
      const url = new URL(request.url ?? '/', 'http://bridge.local');
      if (url.pathname === '/v1/health' && request.method === 'GET') {
        send(response, 200, { status: 'ok', protocol: 1 });
        return;
      }
      if (!isAuthorized(request.headers.authorization, token)) {
        send(response, 401, { error: 'Unauthorized' });
        return;
      }
      if (url.pathname === '/v1/tree' && request.method === 'GET') {
        const output = await runCmux(['--json', '--id-format', 'uuids', 'tree', '--all']);
        send(response, 200, JSON.parse(output));
        return;
      }
      if (url.pathname === '/v1/notifications' && request.method === 'GET') {
        const output = await runCmux(['--json', 'list-notifications']);
        send(response, 200, JSON.parse(output));
        return;
      }
      if (url.pathname === '/v1/notifications/read' && request.method === 'POST') {
        const body = await readJsonBody(request);
        const id = validateSurfaceId(body.id);
        await runCmux(['mark-notification-read', '--id', id]);
        send(response, 200, { marked: true });
        return;
      }
      if (url.pathname === '/v1/screen' && request.method === 'GET') {
        const surface = validateSurfaceId(url.searchParams.get('surface'));
        const lines = Number(url.searchParams.get('lines') ?? '120');
        if (!Number.isInteger(lines) || lines < 1 || lines > 500) throw new Error('Invalid line count');
        const output = await runCmux(['read-screen', '--surface', surface, '--scrollback', '--lines', String(lines)]);
        send(response, 200, { surface, text: output });
        return;
      }
      if (url.pathname === '/v1/input' && request.method === 'POST') {
        const body = await readJsonBody(request);
        const surface = validateSurfaceId(body.surface);
        if (typeof body.text !== 'string' || body.text.length < 1 || body.text.length > 4096 || body.text.includes('\0')) throw new Error('Invalid input text');
        await runCmux(['rpc', 'surface.send_text', JSON.stringify({ surface_id: surface, text: body.text })]);
        send(response, 200, { sent: true });
        return;
      }
      if (url.pathname === '/v1/key' && request.method === 'POST') {
        const body = await readJsonBody(request);
        const surface = validateSurfaceId(body.surface);
        const key = validateKey(body.key);
        await runCmux(['rpc', 'surface.send_key', JSON.stringify({ surface_id: surface, key })]);
        send(response, 200, { sent: true });
        return;
      }
      send(response, 404, { error: 'Not found' });
    } catch (error) {
      const expected = /^(Invalid|Unsupported|Request body)/.test(error.message);
      send(response, expected ? 400 : 502, { error: expected ? error.message : 'cmux command failed' });
    }
  });
}

export async function runInstalledCmux(args) {
  const { stdout } = await execFile(process.env.CMUX_APP_CLI || defaultCli, args, {
    timeout: 8_000,
    maxBuffer: 4 * 1024 * 1024,
    encoding: 'utf8'
  });
  return stdout;
}

async function main() {
  if (!process.env.CMUX_SOCKET_PATH) throw new Error('Start the bridge from a cmux terminal so its CLI can reach cmux');
  const token = loadOrCreateToken();
  let host = '127.0.0.1';
  if (process.argv.includes('--bind-tailscale')) {
    const { stdout } = await execFile('tailscale', ['ip', '-4'], { timeout: 5_000, encoding: 'utf8' });
    host = stdout.trim().split('\n')[0];
    if (!/^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.\d{1,3}\.\d{1,3}$/.test(host)) {
      throw new Error('Tailscale has no valid IPv4 address');
    }
  }
  const port = Number(process.env.CMUX_APP_BRIDGE_PORT || '58466');
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid bridge port');
  await runInstalledCmux(['--json', '--id-format', 'uuids', 'tree', '--all']);
  const server = createBridge({ token, runCmux: runInstalledCmux });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => {
      server.removeListener('error', reject);
      resolve();
    });
  });
  console.log(`cmux-app bridge listening on ${host}:${port}`);
  if (host !== '127.0.0.1') {
    const url = new URL('cmux-app://pair');
    url.searchParams.set('host', host);
    url.searchParams.set('port', String(port));
    url.searchParams.set('token', token);
    console.log(`Pairing URL (keep private): ${url.toString()}`);
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main().catch((error) => { console.error(error.message); process.exitCode = 1; });
}
