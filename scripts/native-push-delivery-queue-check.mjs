import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, rmSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { NativePushSourceAdapter } from '../push/native-source.mjs';
import { NativePushSourceChannel } from '../push/native-source-channel.mjs';
import { PushSourceJournal } from '../push/source-journal.mjs';

const [casesPath, executable] = process.argv.slice(2);
const cases = JSON.parse(readFileSync(casesPath));
assert.equal(cases.checks.length, 10);
assert.ok(cases.checks.every(value => typeof value === 'string'));
assert.equal(cases.frames.length, 5);
const time = 1_800_000_000_000, directory = mkdtempSync(join(tmpdir(), 'cmux-native-owner-'));
const journal = new PushSourceJournal({ directory, key: Buffer.alloc(32, 28), now: () => time });
const helper = { senderKeyID: 'fixture-helper', macInstallationID: 'fixture-install', publicKey: Buffer.alloc(32, 1).toString('base64') };
const bootstrap = cases.frames[0];
assert.deepEqual(Object.keys(bootstrap).sort(), ['type', 'version', 'sessionID', 'key'].sort());
let clock = time;
const adapter = new NativePushSourceAdapter({ key: Buffer.from(bootstrap.key, 'base64'), sessionID: bootstrap.sessionID,
  helper, capture: request => journal.accept(request), changed: () => {}, now: () => clock });
try {
  assert.equal(adapter.accept(cases.frames[1]).kind, 'state-updated');
  clock += 31_000;
  assert.deepEqual(adapter.accept(cases.frames[2]), { sequence: 2, kind: 'source-unavailable', acknowledged: false });
  // Receipt explicitly confirmed the unused sequence; replace it with host state.
  clock = time + 10_000;
  assert.equal(adapter.accept(cases.frames[3]).kind, 'state-updated');
  assert.equal(cases.frames[3].sequence, cases.frames[2].sequence);
  assert.equal(cases.frames[4].body, cases.frames[2].body);
  assert.equal(adapter.accept(cases.frames[4]).kind, 'captured');
  assert.deepEqual(journal.status().counts, { pending: 1 });
} finally { adapter.close(); journal.close(); rmSync(directory, { recursive: true, force: true }); }

// Actual owner-side Swift queue and helper-side channel exchange private pipes.
const streamDirectory = mkdtempSync(join(tmpdir(), 'cmux-native-owner-stream-'));
const streamJournal = new PushSourceJournal({ directory: streamDirectory, key: Buffer.alloc(32, 29), now: () => time });
const child = spawn(executable, ['--stream'], { stdio: ['pipe', 'pipe', 'pipe'] });
let liveAdapter, liveClock = time, observations = 0, capturedRequest;
// Keep stream error ownership after channel closure; never echo child diagnostics.
child.stdin.on('error', () => {}); child.stdout.on('error', () => {}); child.stderr.resume();
const exit = new Promise((resolve, reject) => {
  child.once('error', reject);
  child.once('close', (code, signal) => resolve({ code, signal }));
});
const channel = new NativePushSourceChannel({ input: child.stdout, output: child.stdin,
  createAdapter: ({ key, sessionID }) => {
    liveAdapter = new NativePushSourceAdapter({ key, sessionID, helper, now: () => liveClock,
      capture: value => { capturedRequest = value; return streamJournal.accept(value); },
      changed: () => { if (++observations === 1) liveClock = time + 31_000; } });
    return liveAdapter;
  } });
const timer = setTimeout(() => child.kill('SIGKILL'), 15_000);
try {
  const completion = await channel.start();
  const result = await exit;
  assert.deepEqual(result, { code: 0, signal: null });
  assert.equal(completion.kind, 'closed');
  assert.equal(liveAdapter.readHost(), null);
  assert.equal(capturedRequest.expiresAt, time + 120_000);
  assert.equal(capturedRequest.event.body, 'literal\n"line"');
  assert.deepEqual(streamJournal.status().counts, { pending: 1 });
  console.log('Ten compiled native receipt/replay cases and actual Swift queue/private-pipe refresh/durable-capture interop passed');
} finally {
  clearTimeout(timer); await channel.stop();
  if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
  await exit.catch(() => {});
  child.stdin.destroy(); child.stdout.destroy(); child.stderr.destroy();
  streamJournal.close(); rmSync(streamDirectory, { recursive: true, force: true });
}
