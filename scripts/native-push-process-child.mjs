// Public fixture helper only: private pipes and encrypted local capture, no FCM.
import { appendFileSync, writeFileSync } from 'node:fs';
import { NativePushSourceChannel } from '../push/native-source-channel.mjs';
import { NativePushSourceAdapter } from '../push/native-source.mjs';
import { PushSourceJournal } from '../push/source-journal.mjs';
const [directory, mode, marker] = process.argv.slice(2);
writeFileSync(marker + '.pid', String(process.pid));
const journal = new PushSourceJournal({ directory, key: Buffer.alloc(32, 42) });
process.stdin.on('error', () => {}); process.stdout.on('error', () => {});
if (mode === 'malformed') { process.stdout.write('x'.repeat(514)); }
const channel = new NativePushSourceChannel({ input: process.stdin, output: process.stdout,
  createAdapter: ({ key, sessionID }) => new NativePushSourceAdapter({ key, sessionID,
    helper: { senderKeyID: 'fixture-helper', macInstallationID: 'fixture-install', publicKey: Buffer.alloc(32, 1).toString('base64') },
    changed: () => {}, capture: request => {
      const receipt = journal.accept(request);
      if (receipt.kind === 'captured') appendFileSync(marker, JSON.stringify({ expiry: request.expiresAt, body: request.event.body }) + '\n');
      return receipt;
    } }) });
if (mode === 'stall') { process.stdin.pause(); setInterval(() => {}, 10000); }
else { await channel.start(); journal.close(); }
