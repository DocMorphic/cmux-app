// Public synthetic recovery fixture only. It runs the actual production CLI
// through private pipes and deliberately withholds a committed capture receipt.
import { spawn } from 'node:child_process';
import { existsSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
const args = process.argv.slice(2);
if (args.length !== 2 || args[0] !== '--config') throw Error('Fixture configuration');
const root = dirname(args[1]);
const child = spawn(process.execPath, [fileURLToPath(new URL('./cmux-push-helper.mjs', import.meta.url)), ...args],
  { stdio: ['pipe', 'pipe', 'ignore'], env: { PATH: '/usr/bin:/bin' } });
const stop = () => { child.stdin.end(); };
process.stdin.on('error', stop); process.stdout.on('error', stop); child.stdin.on('error', stop);
process.stdin.pipe(child.stdin);
let inputPending = ''; const hosts = new Map();
process.stdin.on('data', chunk => {
  inputPending += chunk.toString('utf8');
  if (inputPending.length > 2 * 1_048_576) { child.kill('SIGKILL'); return; }
  let index;
  while ((index = inputPending.indexOf('\n')) >= 0) {
    const line = inputPending.slice(0, index); inputPending = inputPending.slice(index + 1);
    try {
      const frame = JSON.parse(line);
      if (frame.version === 1 && typeof frame.body === 'string') {
        const body = JSON.parse(Buffer.from(frame.body, 'base64').toString('utf8'));
        if (body.type === 'host' && body.snapshot?.state === 'ready') hosts.set(frame.sequence, body.snapshot.epoch);
      }
    } catch { child.kill('SIGKILL'); }
  }
});
let pending = '';
child.stdout.on('data', chunk => {
  pending += chunk.toString('utf8');
  if (pending.length > 8192) { child.kill('SIGKILL'); return; }
  let index;
  while ((index = pending.indexOf('\n')) >= 0) {
    const line = pending.slice(0, index); pending = pending.slice(index + 1);
    try {
      const receipt = JSON.parse(line);
      if (receipt.kind === 'state-updated' && hosts.has(receipt.sequence)) {
        writeFileSync(join(root, 'accepted-host-epoch'), hosts.get(receipt.sequence), { mode: 0o600 });
        hosts.delete(receipt.sequence);
      }
      if (['captured', 'duplicate'].includes(receipt.kind) && !existsSync(join(root, 'allow-receipts'))) {
        writeFileSync(join(root, 'committed-receipt-withheld'), 'actual helper committed; receipt withheld\n', { mode: 0o600 });
      } else process.stdout.write(line + '\n');
    } catch { child.kill('SIGKILL'); }
  }
});
child.on('error', () => { process.stdin.destroy(); process.exitCode = 1; });
child.on('close', code => { process.stdin.destroy(); process.exitCode = code === 0 ? 0 : 1; });
