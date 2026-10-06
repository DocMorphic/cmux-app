// Synthetic fixture process: the parent deliberately SIGKILLs it after durable queue admission.
import { writeSync } from 'node:fs';
import { PushSourceJournal } from '../source-journal.mjs';
import { PushOutbox } from '../outbox.mjs';
const directory = process.argv[2], key = Buffer.alloc(32, 21), time = 1_800_000_000_000;
const journal = new PushSourceJournal({ directory, key, now: () => time });
const outbox = new PushOutbox({ directory, key, now: () => time });
const recipient = { installationID: 'phone', keyID: 'key', senderKeyID: 'sender', tuple: {
  accountID: 'account', teamID: 'team', macDeviceID: 'mac', macInstanceTag: 'stable', macBuildID: 'build',
  iosBuildID: 'android', iosInstallationID: 'phone'
} };
await journal.drain({ forwarder: {
  prepare: async input => ({ kind: 'prepared', jobs: [{ eventID: input.event.correlationID,
    registration: { id: 'fixture-registration', generation: 'fixture-generation' },
    delivery: { recipient, token: 'fixture-token', expiresAt: input.expiresAt,
      envelope: { ...recipient, version: 2, encapsulatedKey: Buffer.alloc(32, 1).toString('base64'),
        ciphertext: Buffer.alloc(48, 2).toString('base64') } }
  }] }),
  enqueue: prepared => {
    outbox.enqueueBatch(prepared.jobs);
    writeSync(1, 'admitted\n');
    // Test-only deliberate crash window: queued in outbox, not yet acknowledged in source journal.
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0);
    throw new Error('fixture resumed unexpectedly');
  }
} });
