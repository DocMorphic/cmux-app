import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { NativePushSourceAdapter } from '../push/native-source.mjs';
import { NativePushSourceChannel } from '../push/native-source-channel.mjs';
import { PushSourceJournal } from '../push/source-journal.mjs';

// Only a synthetic Swift producer and a temporary, encrypted local journal.
// No production account, listener, Firebase resource or provider request.
const binary = process.argv[2];
assert.ok(binary, 'Pass the compiled synthetic Swift producer');
const directory = mkdtempSync(join(tmpdir(), 'cmux-source-interop-'));
const journal = new PushSourceJournal({ directory, key: Buffer.alloc(32, 28), now: () => 1_800_000_000_000 });
const child = spawn(binary, ['--stream'], { stdio: ['pipe', 'pipe', 'pipe'] });
let adapter, observedReady = false, captured = 0;
// The owner supervises pipe errors even after the channel invalidates authority.
child.stdin.on('error', () => {}); child.stdout.on('error', () => {}); child.stderr.resume();
const timeout = setTimeout(() => child.kill('SIGKILL'), 30_000);
const exited = new Promise((resolve, reject) => {
  child.once('error', reject); child.once('exit', (code, signal) => resolve({ code, signal }));
});
const channel = new NativePushSourceChannel({ input: child.stdout, output: child.stdin, createAdapter: options => {
  adapter = new NativePushSourceAdapter({ ...options, now: () => 1_800_000_000_000,
    helper: { senderKeyID: 'fixture-helper', macInstallationID: 'fixture-install', publicKey: Buffer.alloc(32, 1).toString('base64') },
    capture: request => { const receipt = journal.accept(request); if (receipt.kind === 'captured') captured++; return receipt; },
    changed: () => { if (adapter?.readHost()?.state === 'ready') observedReady = true; } });
  return adapter;
} });
try {
  const done = channel.start();
  const result = await exited; await done;
  assert.deepEqual(result, { code: 0, signal: null });
  assert.equal(observedReady, true); assert.equal(captured, 2);
  assert.deepEqual(journal.status().counts, { pending: 2 });
  assert.equal(adapter.readHost(), null, 'EOF immediately retires the live host lease');
  console.log('Swift private pipes: bootstrap, authenticated host, two durable captures, receipts and EOF retirement passed');
} finally {
  await channel.stop(); clearTimeout(timeout);
  if (child.exitCode === null && child.signalCode === null) { child.kill('SIGKILL'); await exited.catch(() => {}); }
  child.stdin.destroy(); child.stdout.destroy(); child.stderr.destroy();
  journal.close(); rmSync(directory, { recursive: true, force: true });
}
